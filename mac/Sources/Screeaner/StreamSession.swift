import CoreMedia
import CoreVideo
import Foundation
import Network
import ScreeanerCore

enum SettingsKey {
    static let hiDPI = "hiDPI"
    static let fps = "fps"
    static let bitrateMbps = "bitrateMbps"
    static let codec = "codec"
    static let autoAdb = "autoAdb"
    static let adaptiveBitrate = "adaptiveBitrate"
    static let udpOverWifi = "udpOverWifi"

    static func registerDefaults() {
        UserDefaults.standard.register(defaults: [
            hiDPI: true, fps: 60, bitrateMbps: 20, codec: Codec.h264.rawValue, autoAdb: true,
            adaptiveBitrate: true, udpOverWifi: true,
        ])
    }
}

/// The whole pipeline for one tablet:
/// virtual display -> ScreenCaptureKit -> VideoToolbox -> TCP or UDP+FEC,
/// plus input the other way. Several sessions can run side by side.
final class StreamSession {
    struct Stats {
        var fps = 0
        var mbps = 0.0
        var encodeMs = 0.0
        var skipped = 0
        var targetMbps = 0.0
        var lossPercent = 0.0
        var transport = "TCP"
    }

    let connection: ClientConnection
    /// Echoed by the tablet in its UDP hello so the flow can be matched to this session.
    let token = UInt32.random(in: 1...UInt32.max)
    private(set) var clientName = "Tablet"
    private(set) var deviceID: String?
    private(set) var summary = ""
    /// Set when the session ended because of an error worth showing.
    private(set) var failure: String?

    private let captureQueue = DispatchQueue(label: "screeaner.capture", qos: .userInteractive)
    private var display: VirtualDisplay?
    private var capturer: ScreenCapturer?
    private var injector: InputInjector?
    private var probe: LatencyProbe?             // main queue
    private var controller: BitrateController?   // main queue
    private var adaptive = true                  // main queue

    // captureQueue only
    private var encoder: VideoEncoder?
    private var lastFrame: CVPixelBuffer?
    private var lastEncodeTime: TimeInterval = 0
    private var refinements = 0
    private var refineTimer: DispatchSourceTimer?

    // Guarded by `lock`
    private let lock = NSLock()
    private var stopped = false
    private var frames = 0
    private var bytes = 0
    private var encodeMicrosSum = 0
    private var skipped = 0
    private var udpFlow: NWConnection?
    private var udpActive = false
    private var nextFrameId: UInt32 = 0
    private var redundancy = 0.2
    private var smoothedLoss = 0.0
    private var clientRttMicros: UInt32 = 0
    private var clientPacketsExpected = 0
    private var clientPacketsLost = 0
    private var clientFramesLost = 0

    /// All called on the main queue.
    var onHello: (() -> Void)?
    var onStatus: ((String) -> Void)?
    var onEnded: (() -> Void)?

    init(connection: ClientConnection) {
        self.connection = connection
        connection.onMessage = { [weak self] type, payload in self?.handle(type, payload) }
        connection.onClose = { [weak self] in self?.stop() }
    }

    func start() {
        connection.start()
    }

    // MARK: - Incoming messages (connection queue)

    private func handle(_ type: MsgType, _ payload: Data) {
        let first = payload.startIndex
        switch type {
        case .hello:
            guard let hello = try? JSONDecoder().decode(Hello.self, from: payload) else {
                stop()
                return
            }
            DispatchQueue.main.async { self.begin(hello) }
        case .touch:
            guard payload.count >= 14 else { return }
            injector?.touch(
                action: payload[first],
                x: payload.readFloat(at: 1),
                y: payload.readFloat(at: 5),
                pressure: payload.readFloat(at: 9),
                button: payload[first + 13]
            )
        case .scroll:
            guard payload.count >= 16 else { return }
            injector?.scroll(
                dx: payload.readFloat(at: 0),
                dy: payload.readFloat(at: 4),
                x: payload.readFloat(at: 8),
                y: payload.readFloat(at: 12)
            )
        case .zoom:
            guard payload.count >= 13 else { return }
            injector?.zoom(
                phase: payload[first],
                delta: payload.readFloat(at: 1),
                x: payload.readFloat(at: 5),
                y: payload.readFloat(at: 9)
            )
        case .key:
            guard payload.count >= 4 else { return }
            injector?.key(
                action: payload[first],
                androidKeyCode: Int(payload.readBE(UInt16.self, at: 1)),
                isRepeat: payload[first + 3] & 1 != 0
            )
        case .probe:
            guard payload.count >= 5 else { return }
            let state = LatencyProbe.State(rawValue: payload[first]) ?? .hidden
            let seq = payload.readBE(UInt32.self, at: 1)
            DispatchQueue.main.async { self.runProbe(state, seq: seq) }
        case .keyframeRequest:
            sendKeyframeNow()
        case .ping:
            // Echo plus our clock (the one frame timestamps use), so the tablet
            // can line the two clocks up and measure real per-frame latency.
            var reply = payload
            reply.appendBE(Proto.hostClockMicros())
            connection.send(.pong, reply)
        case .stats:
            guard payload.count >= 16 else { return }
            lock.lock()
            clientRttMicros = payload.readBE(UInt32.self, at: 0)
            clientPacketsExpected += Int(payload.readBE(UInt32.self, at: 4))
            clientPacketsLost += Int(payload.readBE(UInt32.self, at: 8))
            clientFramesLost += Int(payload.readBE(UInt16.self, at: 12))
            lock.unlock()
        default:
            break
        }
    }

    private func sendKeyframeNow() {
        captureQueue.async {
            guard let encoder = self.encoder else { return }
            encoder.forceKeyframe()
            // The screen may be static, so re-encode the last frame right away.
            if let frame = self.lastFrame {
                self.lastEncodeTime = ProcessInfo.processInfo.systemUptime
                encoder.encode(frame)
            }
        }
    }

    /// Main queue: paints the latency test square and tells the tablet when it did.
    private func runProbe(_ state: LatencyProbe.State, seq: UInt32) {
        guard let display, !isStopped else { return }
        if probe == nil, state != .hidden { probe = LatencyProbe(displayID: display.displayID) }
        let paintedAt = probe?.apply(state) ?? Proto.hostClockMicros()
        if state == .hidden { probe = nil }
        var reply = Data(capacity: 12)
        reply.appendBE(seq)
        reply.appendBE(paintedAt)
        connection.send(.probeAck, reply)
    }

    // MARK: - UDP path

    /// The tablet opened its UDP flow (main queue). Video moves to it at the next keyframe.
    func attachUdp(_ flow: NWConnection) {
        lock.lock()
        let previous = udpFlow
        let alive = !stopped
        if alive {
            udpFlow = flow
            udpActive = false
        }
        lock.unlock()
        guard alive else {
            flow.cancel()
            return
        }
        if previous !== flow { previous?.cancel() }

        flow.stateUpdateHandler = { [weak self, weak flow] state in
            guard let self, let flow else { return }
            switch state {
            case .failed, .cancelled:
                // Fall back to TCP; the tablet accepts it again from a keyframe.
                self.lock.lock()
                let wasCurrent = self.udpFlow === flow
                if wasCurrent {
                    self.udpFlow = nil
                    self.udpActive = false
                }
                self.lock.unlock()
                if wasCurrent { self.sendKeyframeNow() }
            default:
                break
            }
        }
        sendKeyframeNow()
    }

    // MARK: - Pipeline setup (main queue)

    private func begin(_ hello: Hello) {
        guard display == nil, !isStopped else { return }
        let defaults = UserDefaults.standard
        let hiDPI = defaults.bool(forKey: SettingsKey.hiDPI)
        // Portrait tablets simply report width < height.
        let width = min(max(hello.width, 360), 4096) & ~1
        let height = min(max(hello.height, 360), 4096) & ~1
        let fps = max(24, min(defaults.integer(forKey: SettingsKey.fps), Int(hello.refreshRate.rounded())))
        let wantsHEVC = defaults.string(forKey: SettingsKey.codec) == Codec.hevc.rawValue
        let codec: Codec = (wantsHEVC && hello.codecs.contains(Codec.hevc.rawValue)) ? .hevc : .h264
        let bitrate = max(2, defaults.integer(forKey: SettingsKey.bitrateMbps)) * 1_000_000
        let useUdp = (hello.udp ?? false) && defaults.bool(forKey: SettingsKey.udpOverWifi)
            && !connection.isLoopback
        adaptive = defaults.bool(forKey: SettingsKey.adaptiveBitrate)
        controller = BitrateController(maximum: bitrate)
        clientName = hello.name
        deviceID = hello.id
        onHello?() // lets the app retire a stale session of the same tablet first

        guard let display = VirtualDisplay(
            name: "Screeaner – \(hello.name)",
            serial: VirtualDisplay.serial(for: hello.id ?? hello.name),
            pixelWidth: width, pixelHeight: height,
            hiDPI: hiDPI, refreshRate: Double(fps), dpi: hello.densityDpi
        ) else {
            fail("Não foi possível criar o display virtual")
            return
        }
        self.display = display

        let encoder: VideoEncoder
        do {
            encoder = try VideoEncoder(width: width, height: height, codec: codec, fps: fps, bitrate: bitrate)
        } catch {
            fail(error.localizedDescription)
            return
        }
        encoder.onEncoded = { [weak self] data, isKey, pts, encodeMicros in
            self?.transmit(data, isKey: isKey, ptsMicros: pts, encodeMicros: encodeMicros)
        }
        injector = InputInjector(displayID: display.displayID)

        let config = StreamConfig(
            codec: codec.rawValue, width: width, height: height, fps: fps,
            token: useUdp ? token : 0, udpPort: Int(Proto.port),
            server: Host.current().localizedName ?? "Mac", version: Proto.appVersion
        )
        if let json = try? JSONEncoder().encode(config) {
            connection.send(.config, json)
        }

        let capturer = ScreenCapturer(queue: captureQueue)
        capturer.onFrame = { [weak self] pixelBuffer in self?.captured(pixelBuffer) }
        capturer.onStop = { [weak self] error in
            DispatchQueue.main.async { self?.fail("Captura interrompida: \(error.localizedDescription)") }
        }
        self.capturer = capturer
        let orientation = width >= height ? "" : " · retrato"
        let tabletApp = hello.version.map { " · app \($0)" } ?? ""
        summary = "\(width)×\(height) · \(fps) Hz · \(codec == .hevc ? "HEVC" : "H.264")\(orientation)\(tabletApp)"
        onStatus?("Iniciando captura…")

        captureQueue.async {
            self.encoder = encoder
            self.startRefineTimer()
        }

        let displayID = display.displayID
        Task {
            do {
                try await capturer.start(displayID: displayID, width: width, height: height, fps: fps)
                DispatchQueue.main.async { self.onStatus?("Transmitindo") }
            } catch {
                DispatchQueue.main.async {
                    self.fail("Captura falhou (verifique a permissão de Gravação de Tela): \(error.localizedDescription)")
                }
            }
        }
    }

    // MARK: - Frames (capture queue)

    private func captured(_ pixelBuffer: CVPixelBuffer) {
        lastFrame = pixelBuffer
        refinements = 0
        // Never queue latency: if the TCP socket is behind, skip this frame
        // entirely. Dropping before the encoder keeps the reference chain intact.
        lock.lock()
        let overUdp = udpActive
        lock.unlock()
        if !overUdp && connection.isCongested {
            lock.lock()
            skipped += 1
            lock.unlock()
            return
        }
        lastEncodeTime = ProcessInfo.processInfo.systemUptime
        encoder?.encode(pixelBuffer)
    }

    /// When the screen stops changing, re-encode the last frame a couple of
    /// times: it sharpens whatever the rate control blurred during motion and,
    /// over UDP, gives the tablet a newer frame to detect a lost final frame.
    private func startRefineTimer() {
        let timer = DispatchSource.makeTimerSource(queue: captureQueue)
        timer.schedule(deadline: .now() + 0.1, repeating: 0.05)
        timer.setEventHandler { [weak self] in
            guard let self, let frame = self.lastFrame, self.refinements < 2 else { return }
            let now = ProcessInfo.processInfo.systemUptime
            guard now - self.lastEncodeTime >= 0.1, !self.connection.isCongested else { return }
            self.refinements += 1
            self.lastEncodeTime = now
            self.encoder?.encode(frame)
        }
        timer.resume()
        refineTimer = timer
    }

    /// Called on a VideoToolbox thread with one encoded frame.
    private func transmit(_ data: Data, isKey: Bool, ptsMicros: UInt64, encodeMicros: UInt32) {
        lock.lock()
        let flow = udpFlow
        // Only switch transports on a keyframe so the tablet can start decoding cleanly.
        if flow != nil, !udpActive, isKey { udpActive = true }
        let overUdp = flow != nil && udpActive
        let frameId = nextFrameId
        if overUdp { nextFrameId &+= 1 }
        let parity = redundancy
        lock.unlock()

        var sent = data.count
        if overUdp, let flow {
            var blob = Data(capacity: 12 + data.count)
            blob.appendBE(ptsMicros)
            blob.appendBE(encodeMicros)
            blob.append(data)
            let packets = FecPacketizer.packets(frameId: frameId, blob: blob, isKey: isKey, redundancy: parity)
            sent = 0
            flow.batch {
                for packet in packets {
                    sent += packet.count
                    flow.send(content: packet, completion: .idempotent)
                }
            }
        } else {
            connection.sendVideo(data, ptsMicros: ptsMicros, isKey: isKey, encodeMicros: encodeMicros)
        }

        lock.lock()
        frames += 1
        bytes += sent
        encodeMicrosSum += Int(encodeMicros)
        lock.unlock()
    }

    // MARK: - Lifecycle

    private var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    private func fail(_ message: String) {
        failure = message
        onStatus?(message)
        stop()
    }

    func stop() {
        lock.lock()
        let alreadyStopped = stopped
        stopped = true
        let flow = udpFlow
        udpFlow = nil
        udpActive = false
        lock.unlock()
        guard !alreadyStopped else { return }

        flow?.cancel()
        connection.close()
        DispatchQueue.main.async {
            self.capturer?.stop()
            self.capturer = nil
            self.captureQueue.async {
                self.refineTimer?.cancel()
                self.refineTimer = nil
                self.encoder?.invalidate()
                self.encoder = nil
                self.lastFrame = nil
            }
            _ = self.probe?.apply(.hidden)
            self.probe = nil
            self.injector?.releaseAll()
            self.injector = nil
            self.display = nil // unplugs the virtual monitor
            self.onEnded?()
        }
    }

    /// Once per second on the main queue: reads the counters, lets the rate
    /// controller react and returns what happened for the UI.
    func tick() -> Stats {
        lock.lock()
        var stats = Stats(
            fps: frames,
            mbps: Double(bytes) * 8 / 1_000_000,
            encodeMs: frames > 0 ? Double(encodeMicrosSum) / Double(frames) / 1000 : 0,
            skipped: skipped
        )
        let loss = clientPacketsExpected > 0 ? Double(clientPacketsLost) / Double(clientPacketsExpected) : 0
        let lostFrames = clientFramesLost
        let rttMs = clientRttMicros > 0 ? Double(clientRttMicros) / 1000 : nil
        smoothedLoss = smoothedLoss * 0.7 + loss * 0.3
        redundancy = BitrateController.redundancy(forLoss: smoothedLoss)
        stats.transport = udpActive ? "UDP+FEC" : (connection.isLoopback ? "USB" : "TCP")
        stats.lossPercent = loss * 100
        frames = 0
        bytes = 0
        encodeMicrosSum = 0
        skipped = 0
        clientPacketsExpected = 0
        clientPacketsLost = 0
        clientFramesLost = 0
        lock.unlock()

        if adaptive, var controller {
            let sample = BitrateController.Sample(
                skippedFrames: stats.skipped, lostFrames: lostFrames, packetLoss: loss, rttMs: rttMs
            )
            if let bitrate = controller.update(sample) {
                captureQueue.async { self.encoder?.setBitrate(bitrate) }
            }
            self.controller = controller
        }
        stats.targetMbps = Double(controller?.current ?? 0) / 1_000_000
        return stats
    }
}
