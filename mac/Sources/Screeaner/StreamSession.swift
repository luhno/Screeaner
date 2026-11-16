import CoreGraphics
import CoreMedia
import CoreVideo
import CryptoKit
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
    static let clipboard = "clipboard"
    static let autoOpenApp = "autoOpenApp"
    /// Name of the Mac screen the mirror mode shows (empty = main screen).
    static let mirrorScreen = "mirrorScreen"

    static func registerDefaults() {
        UserDefaults.standard.register(defaults: [
            hiDPI: true, fps: 60, bitrateMbps: 20, codec: Codec.h264.rawValue, autoAdb: true,
            adaptiveBitrate: true, udpOverWifi: true, clipboard: true, autoOpenApp: true, mirrorScreen: "",
        ])
    }
}

/// A tablet waiting for the user to allow it, showing `code` on both screens.
struct PairingRequest: Identifiable {
    let id = UUID()
    let name: String
    let code: String
    let decide: (Bool) -> Void
}

/// Everything for one tablet: the pairing handshake, then
/// virtual display -> ScreenCaptureKit -> VideoToolbox -> TCP or UDP+FEC (encrypted),
/// plus input, clipboard, audio, latency probes and tablet app updates.
/// Several sessions can run side by side.
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

    private enum Handshake {
        case waitingHello
        case waitingReveal(hello: SecureChannel.Hello, helloPayload: Data, serverPayload: Data,
                           ephemeral: P256.KeyAgreement.PrivateKey, known: Bool)
        case waitingConfirm(session: SecureChannel.Session, hello: SecureChannel.Hello, needsApproval: Bool)
        /// An app from before the secure protocol: it can only be updated.
        case legacy
        case done
    }

    let connection: ClientConnection
    /// Echoed by the tablet in its UDP hello so the flow can be matched to this session.
    let token = UInt32.random(in: 1...UInt32.max)
    // Main queue
    private(set) var clientName = "Tablet"
    private(set) var deviceID: String?
    /// Whether VideoToolbox accepted low-latency rate control; nil before the stream starts.
    private(set) var lowLatencyEncoder: Bool?
    /// The tablet app's version string, e.g. "0.7.0 (7)".
    private(set) var clientVersion: String?
    private(set) var summary = ""
    /// Set when the session ended because of an error worth showing.
    private(set) var failure: String?

    // Connection queue: handshake and input
    private var handshake = Handshake.waitingHello
    private var approved = false
    private var confirmed = false
    private var injector: InputInjector?

    // Main queue: pipeline state
    private let captureQueue = DispatchQueue(label: "screeaner.capture", qos: .userInteractive)
    private var display: VirtualDisplay?
    private var began = false
    private var capturer: ScreenCapturer?
    private var controller: BitrateController?
    private var adaptive = true
    private var pendingRequest: UUID?
    private var mode = DisplayMode.extend
    private var position = DisplayPosition.auto
    private var captureID: CGDirectDisplayID = 0
    private var touchArea = DisplayArranger.unitArea
    private var streamWidth = 0
    private var streamHeight = 0
    private var fps = 60
    private var codec = Codec.h264
    private var hiDPI = true
    private var useUdp = false
    private var wantsAudio = false
    private var frameCap = true
    private var streamOverride: Hello.StreamOverride?
    private var mirrored: [CGDirectDisplayID] = []
    private var dimmed: [CGDirectDisplayID: Float] = [:]
    private var probe: LatencyProbe?

    // captureQueue only
    private var encoder: VideoEncoder?
    private var lastFrame: CVPixelBuffer?
    private var lastEncodeTime: TimeInterval = 0
    private var refinements = 0
    private var refineTimer: DispatchSourceTimer?

    // Guarded by `lock`
    private let lock = NSLock()
    private var stopped = false
    private var established = false
    private var videoKeys: SecureChannel.Session?
    private var clipboardEnabled = false
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
    var onPairingRequest: ((PairingRequest) -> Void)?
    var onPairingFinished: ((UUID) -> Void)?
    var onPaired: (() -> Void)?
    var onClipboard: ((String) -> Void)?
    var onLegacyClient: (() -> Void)?

    init(connection: ClientConnection) {
        self.connection = connection
        connection.onMessage = { [weak self] type, payload in self?.handle(type, payload) }
        connection.onClose = { [weak self] in self?.stop() }
    }

    func start() {
        connection.start()
        // Nobody gets to sit half-connected forever.
        DispatchQueue.main.asyncAfter(deadline: .now() + 120) { [weak self] in
            guard let self, !self.isEstablished, !self.isStopped else { return }
            self.connection.send(.secDenied, Data("Tempo esgotado esperando a aprovação no Mac".utf8))
            self.stop()
        }
    }

    // MARK: - Pairing handshake (connection queue)

    private var isEstablished: Bool {
        lock.lock()
        defer { lock.unlock() }
        return established
    }

    private func abortHandshake() {
        connection.close()
    }

    private func handleHandshake(_ type: MsgType, _ payload: Data) {
        let store = PairingStore.shared
        switch (type, handshake) {
        case (.secHello, .waitingHello):
            guard let hello = try? SecureChannel.Hello(payload: payload) else { return abortHandshake() }
            let known = store.isPaired(deviceId: hello.deviceId, publicKey: hello.staticPublic)
            let ephemeral = P256.KeyAgreement.PrivateKey()
            let server = SecureChannel.buildServer(
                staticPublic: store.identity.publicKey.x963Representation,
                ephemeralPublic: ephemeral.publicKey.x963Representation,
                nonce: SecureChannel.randomBytes(SecureChannel.nonceSize),
                knowsTablet: known, name: Host.current().localizedName ?? "Mac"
            )
            handshake = .waitingReveal(hello: hello, helloPayload: payload, serverPayload: server,
                                       ephemeral: ephemeral, known: known)
            connection.send(.secServer, server)
            DispatchQueue.main.async { self.clientName = hello.name }

        case let (.secReveal, .waitingReveal(hello, helloPayload, serverPayload, ephemeral, known)):
            guard let reveal = try? SecureChannel.Reveal(payload: payload),
                  SecureChannel.sha256(reveal.ephemeralPublic, reveal.nonce) == hello.commit,
                  let ee = try? SecureChannel.ecdh(ephemeral, reveal.ephemeralPublic),
                  let ss = try? SecureChannel.ecdh(store.identity, hello.staticPublic)
            else { return abortHandshake() }
            let transcript = SecureChannel.transcript(hello: helloPayload, server: serverPayload, reveal: payload)
            let session = SecureChannel.Session(ee: ee, ss: ss, transcript: transcript, tabletSide: false)
            handshake = .waitingConfirm(session: session, hello: hello, needsApproval: !known)
            if !known { askUser(name: hello.name, code: session.sas) }

        case (.hello, .waitingHello):
            // 0.6 or older: plain HELLO, no pairing. Offer it the update instead.
            guard let hello = try? JSONDecoder().decode(Hello.self, from: payload) else { return abortHandshake() }
            handshake = .legacy
            connection.allowLegacyUpdates()
            DispatchQueue.main.async {
                self.clientName = hello.name
                self.clientVersion = hello.version
                self.summary = "App antigo (v\(hello.version ?? "?")): só pode ser atualizado"
                self.onLegacyClient?()
            }

        case (.updateRequest, .legacy):
            sendUpdate()

        case (_, .legacy):
            break // anything else an old app sends is ignored

        case let (.secConfirm, .waitingConfirm(session, _, _)):
            guard Self.constantTimeEqual(payload, session.tabletConfirmation()) else { return abortHandshake() }
            confirmed = true
            finishHandshake()

        default:
            abortHandshake()
        }
    }

    private func askUser(name: String, code: String) {
        let request = PairingRequest(name: name, code: code) { [weak self] allowed in
            guard let self else { return }
            self.connection.queue.async {
                if allowed {
                    self.approved = true
                    self.finishHandshake()
                } else {
                    self.connection.send(.secDenied, Data("Pareamento recusado no Mac".utf8))
                    self.connection.close()
                }
            }
        }
        DispatchQueue.main.async {
            self.pendingRequest = request.id
            self.onPairingRequest?(request)
        }
    }

    private func finishHandshake() {
        guard case let .waitingConfirm(session, hello, needsApproval) = handshake,
              confirmed, approved || !needsApproval
        else { return }
        if needsApproval {
            PairingStore.shared.add(deviceId: hello.deviceId, name: hello.name, publicKey: hello.staticPublic)
        }
        // Last clear message, then everything is encrypted.
        connection.send(.secOk, session.macConfirmation())
        connection.establish(session)
        handshake = .done
        lock.lock()
        established = true
        videoKeys = session
        lock.unlock()
        DispatchQueue.main.async {
            self.deviceID = hello.deviceId
            if let request = self.pendingRequest {
                self.pendingRequest = nil
                self.onPairingFinished?(request)
            }
            if needsApproval { self.onPaired?() }
            self.onStatus?("Tablet conectado com segurança")
        }
    }

    private static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var difference: UInt8 = 0
        for (x, y) in zip(a, b) { difference |= x ^ y }
        return difference == 0
    }

    // MARK: - Incoming messages (connection queue)

    private func handle(_ type: MsgType, _ payload: Data) {
        guard case .done = handshake else {
            handleHandshake(type, payload)
            return
        }
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
            let extended = payload.count >= 23
            injector?.touch(
                action: payload[first],
                x: payload.readFloat(at: 1),
                y: payload.readFloat(at: 5),
                pressure: payload.readFloat(at: 9),
                button: payload[first + 13],
                tiltX: extended ? payload.readFloat(at: 14) : 0,
                tiltY: extended ? payload.readFloat(at: 18) : 0,
                tool: extended ? payload[first + 22] : 0
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
                scanCode: payload.count >= 6 ? Int(payload.readBE(UInt16.self, at: 4)) : 0,
                isRepeat: payload[first + 3] & 1 != 0
            )
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
        case .resize:
            guard let request = try? JSONDecoder().decode(Resize.self, from: payload) else { return }
            DispatchQueue.main.async { self.resize(request) }
        case .clipboard:
            lock.lock()
            let enabled = clipboardEnabled
            lock.unlock()
            guard enabled, let text = String(data: payload, encoding: .utf8) else { return }
            DispatchQueue.main.async { self.onClipboard?(text) }
        case .probe:
            guard payload.count >= 5 else { return }
            let state = LatencyProbe.State(rawValue: payload[first]) ?? .hidden
            let seq = payload.readBE(UInt32.self, at: 1)
            DispatchQueue.main.async { self.runProbe(state, seq: seq) }
        case .updateRequest:
            sendUpdate()
        default:
            break
        }
    }

    private func sendKeyframeNow() {
        captureQueue.async {
            guard let encoder = self.encoder else { return }
            encoder.forceKeyframe()
            // The screen may be static, so re-encode the last frame right away.
            if let frame = self.lastFrame, CVPixelBufferGetWidth(frame) == encoder.width {
                self.lastEncodeTime = ProcessInfo.processInfo.systemUptime
                encoder.encode(frame)
            }
        }
    }

    // MARK: - Clipboard and probes (main queue)

    func sendClipboard(_ text: String) {
        lock.lock()
        let enabled = clipboardEnabled && established
        lock.unlock()
        let data = Data(text.utf8)
        guard enabled, !data.isEmpty, data.count <= 1_000_000 else { return }
        connection.send(.clipboard, data)
    }

    /// Main queue: paints the latency test square and tells the tablet when it did.
    private func runProbe(_ state: LatencyProbe.State, seq: UInt32) {
        guard began, captureID != 0, !isStopped else { return }
        if probe == nil, state != .hidden { probe = LatencyProbe(displayID: captureID) }
        let paintedAt = probe?.apply(state) ?? Proto.hostClockMicros()
        if state == .hidden { probe = nil }
        var reply = Data(capacity: 12)
        reply.appendBE(seq)
        reply.appendBE(paintedAt)
        connection.send(.probeAck, reply)
    }

    // MARK: - Tablet app update

    /// Tells the tablet a newer app is available; it answers with `updateRequest` if the user agrees.
    func offerUpdate(_ apk: TabletApk) {
        connection.queueAsync {
            guard let loaded = apk.load(), loaded.data.count <= Proto.maxPayload else { return }
            let offer = UpdateOffer(
                versionName: apk.versionName, versionCode: apk.versionCode,
                size: loaded.data.count, sha256: loaded.sha256
            )
            if let json = try? JSONEncoder().encode(offer) { self.connection.send(.updateOffer, json) }
        }
    }

    /// Connection queue: the tablet accepted; send the APK in one (encrypted) message.
    private func sendUpdate() {
        guard let apk = TabletApk.bundled, let loaded = apk.load(), loaded.data.count <= Proto.maxPayload else { return }
        connection.send(.updateApk, loaded.data)
    }

    // MARK: - UDP path

    /// The tablet opened its UDP flow (main queue). Video moves to it at the next keyframe.
    func attachUdp(_ flow: NWConnection) {
        lock.lock()
        let previous = udpFlow
        let alive = !stopped && established
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

    private func clampSize(_ width: Int, _ height: Int) -> (Int, Int) {
        (min(max(width, 360), 4096) & ~1, min(max(height, 360), 4096) & ~1)
    }

    private func makeEncoder(width: Int, height: Int, bitrate: Int) -> VideoEncoder? {
        guard let encoder = try? VideoEncoder(
            width: width, height: height, codec: codec, fps: fps, bitrate: bitrate, capFrameSize: frameCap
        ) else { return nil }
        lowLatencyEncoder = encoder.isLowLatency
        encoder.onEncoded = { [weak self] data, isKey, pts, encodeMicros in
            self?.transmit(data, isKey: isKey, ptsMicros: pts, encodeMicros: encodeMicros)
        }
        return encoder
    }

    private func configMessage() -> Data? {
        let config = StreamConfig(
            codec: codec.rawValue, width: streamWidth, height: streamHeight, fps: fps,
            token: useUdp ? token : 0, udpPort: Int(Proto.port), mode: mode.rawValue,
            server: Host.current().localizedName ?? "Mac", version: Proto.appVersion, audio: wantsAudio,
            lowLatency: lowLatencyEncoder ?? false, frameCap: frameCap
        )
        return try? JSONEncoder().encode(config)
    }

    private func updateSummary(_ hello: Hello? = nil) {
        let orientation = streamWidth >= streamHeight ? "" : " · retrato"
        let tabletApp = hello?.version.map { " · app \($0)" } ?? ""
        let extras = (wantsAudio ? " · áudio" : "") + (streamOverride != nil ? " · benchmark" : "")
        summary = "\(streamWidth)×\(streamHeight) · \(fps) Hz · \(codec == .hevc ? "HEVC" : "H.264")"
            + "\(orientation)\(tabletApp)\(extras)\n\(mode.label(position))"
    }

    private func begin(_ hello: Hello) {
        guard !began, !isStopped else { return }
        began = true
        let defaults = UserDefaults.standard
        hiDPI = defaults.bool(forKey: SettingsKey.hiDPI)
        // Portrait tablets simply report width < height.
        (streamWidth, streamHeight) = clampSize(hello.width, hello.height)
        // The benchmark asks for specific settings for one stream; the menu keeps its own.
        streamOverride = hello.streamOverride
        let requestedFps = streamOverride?.fps ?? defaults.integer(forKey: SettingsKey.fps)
        fps = max(24, min(requestedFps, Int(hello.refreshRate.rounded())))
        let wantsHEVC = (streamOverride?.codec ?? defaults.string(forKey: SettingsKey.codec)) == Codec.hevc.rawValue
        frameCap = streamOverride?.frameCap ?? true
        codec = (wantsHEVC && hello.codecs.contains(Codec.hevc.rawValue)) ? .hevc : .h264
        let bitrate = max(2, defaults.integer(forKey: SettingsKey.bitrateMbps)) * 1_000_000
        useUdp = (hello.udp ?? false) && defaults.bool(forKey: SettingsKey.udpOverWifi) && !connection.isLoopback
        wantsAudio = hello.audio ?? false
        adaptive = defaults.bool(forKey: SettingsKey.adaptiveBitrate)
        controller = BitrateController(maximum: bitrate)
        clientName = hello.name
        clientVersion = hello.version
        lock.lock()
        clipboardEnabled = (hello.clipboard ?? false) && defaults.bool(forKey: SettingsKey.clipboard)
        lock.unlock()
        onHello?() // lets the app retire a stale session of the same tablet first

        mode = DisplayMode(rawValue: hello.mode ?? "") ?? .extend
        position = DisplayPosition(rawValue: hello.position ?? "") ?? .auto
        if mode == .mirror {
            // Show the Mac's own screen: nothing to create, just capture it.
            captureID = DisplayArranger.mirrorSource()
            touchArea = DisplayArranger.fittedArea(of: captureID, width: streamWidth, height: streamHeight)
        } else {
            guard let display = VirtualDisplay(
                name: "Screeaner – \(hello.name)",
                serial: VirtualDisplay.serial(for: deviceID ?? hello.name),
                pixelWidth: streamWidth, pixelHeight: streamHeight,
                hiDPI: hiDPI, refreshRate: Double(fps), dpi: hello.densityDpi
            ) else {
                fail("Não foi possível criar o display virtual")
                return
            }
            self.display = display
            captureID = display.displayID
        }

        guard let encoder = makeEncoder(width: streamWidth, height: streamHeight, bitrate: bitrate) else {
            fail("Não foi possível criar o encoder de vídeo")
            return
        }
        let injector = InputInjector(displayID: captureID, area: touchArea)
        connection.queue.async { self.injector = injector }
        if let json = configMessage() { connection.send(.config, json) }

        let capturer = ScreenCapturer(queue: captureQueue)
        capturer.onFrame = { [weak self] pixelBuffer in self?.captured(pixelBuffer) }
        capturer.onAudio = { [weak self] pcm in self?.connection.send(.audio, pcm) }
        capturer.onStop = { [weak self] error in
            DispatchQueue.main.async { self?.fail("Captura interrompida: \(error.localizedDescription)") }
        }
        self.capturer = capturer
        updateSummary(hello)
        onStatus?("Iniciando captura…")

        captureQueue.async {
            self.encoder = encoder
            self.startRefineTimer()
        }

        let displayID = captureID
        let (width, height, rate, audio) = (streamWidth, streamHeight, fps, wantsAudio)
        Task {
            do {
                try await capturer.start(displayID: displayID, width: width, height: height, fps: rate, audio: audio)
                DispatchQueue.main.async {
                    guard !self.isStopped else { return }
                    self.applyArrangement()
                    self.onStatus?("Transmitindo")
                }
            } catch {
                DispatchQueue.main.async {
                    self.fail("Captura falhou (verifique a permissão de Gravação de Tela): \(error.localizedDescription)")
                }
            }
        }
    }

    /// Positions the tablet's display, makes it main, or takes over the Mac's screens.
    private func applyArrangement() {
        guard mode != .mirror else { return }
        let makeMain = mode == .main || mode == .only
        if !DisplayArranger.arrange(captureID, position: position, makeMain: makeMain) {
            onStatus?("O macOS não aceitou reposicionar o display do tablet")
        }
        if mode == .only, mirrored.isEmpty {
            mirrored = DisplayArranger.mirrorMacScreens(onto: captureID)
            dimmed = Brightness.dimBuiltIn()
        }
    }

    /// The tablet rotated: change the display mode and the stream size in
    /// place, keeping the connection, the pairing and the windows.
    private func resize(_ request: Resize) {
        guard began, !isStopped, let capturer else { return }
        let (width, height) = clampSize(request.width, request.height)
        guard width != streamWidth || height != streamHeight else { return }
        streamWidth = width
        streamHeight = height
        display?.apply(pixelWidth: width, pixelHeight: height, hiDPI: hiDPI, refreshRate: Double(fps))
        if mode == .mirror {
            touchArea = DisplayArranger.fittedArea(of: captureID, width: width, height: height)
            let injector = InputInjector(displayID: captureID, area: touchArea)
            connection.queue.async { self.injector = injector }
        }
        guard let encoder = makeEncoder(width: width, height: height, bitrate: controller?.current ?? 20_000_000),
              let json = configMessage()
        else {
            fail("Não foi possível reconfigurar o vídeo")
            return
        }
        updateSummary()
        // Swap the encoder and announce the new size before any frame of that size.
        captureQueue.async {
            self.encoder?.invalidate()
            self.encoder = encoder
            self.lastFrame = nil
            self.connection.send(.config, json)
        }
        Task {
            try? await capturer.update(width: width, height: height)
            DispatchQueue.main.async {
                guard !self.isStopped else { return }
                self.applyArrangement()
                self.onStatus?("Transmitindo")
            }
        }
    }

    // MARK: - Frames (capture queue)

    private func captured(_ pixelBuffer: CVPixelBuffer) {
        // Frames still at the old size right after a rotation are skipped.
        guard let encoder, CVPixelBufferGetWidth(pixelBuffer) == encoder.width,
              CVPixelBufferGetHeight(pixelBuffer) == encoder.height
        else { return }
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
        encoder.encode(pixelBuffer)
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
        let keys = videoKeys
        lock.unlock()

        var sent = data.count
        if overUdp, let flow, let keys {
            var blob = Data(capacity: 12 + data.count)
            blob.appendBE(ptsMicros)
            blob.appendBE(encodeMicros)
            blob.append(data)
            // Encrypt the whole frame, then protect the ciphertext with FEC.
            guard let sealed = try? keys.sealVideo(frameId: UInt64(frameId), blob) else { return }
            let packets = FecPacketizer.packets(frameId: frameId, blob: sealed, isKey: isKey, redundancy: parity)
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
        connection.queue.async {
            self.injector?.releaseAll()
            self.injector = nil
        }
        DispatchQueue.main.async {
            if let request = self.pendingRequest {
                self.pendingRequest = nil
                self.onPairingFinished?(request)
            }
            _ = self.probe?.apply(.hidden)
            self.probe = nil
            self.capturer?.stop()
            self.capturer = nil
            self.captureQueue.async {
                self.refineTimer?.cancel()
                self.refineTimer = nil
                self.encoder?.invalidate()
                self.encoder = nil
                self.lastFrame = nil
            }
            Brightness.restore(self.dimmed)
            self.dimmed = [:]
            DisplayArranger.unmirror(self.mirrored)
            self.mirrored = []
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
