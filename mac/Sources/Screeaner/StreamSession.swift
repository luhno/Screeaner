import CoreVideo
import Foundation

enum SettingsKey {
    static let hiDPI = "hiDPI"
    static let fps = "fps"
    static let bitrateMbps = "bitrateMbps"
    static let codec = "codec"
    static let autoAdb = "autoAdb"

    static func registerDefaults() {
        UserDefaults.standard.register(defaults: [
            hiDPI: true, fps: 60, bitrateMbps: 20, codec: Codec.h264.rawValue, autoAdb: true,
        ])
    }
}

/// The whole pipeline for one tablet:
/// virtual display -> ScreenCaptureKit -> VideoToolbox -> socket, plus input the other way.
final class StreamSession {
    struct Stats {
        var fps = 0
        var mbps = 0.0
        var encodeMs = 0.0
        var dropped = 0
    }

    let connection: ClientConnection
    private(set) var clientName = "Tablet"
    private(set) var summary = ""
    /// Set when the session ended because of an error worth showing.
    private(set) var failure: String?

    private let captureQueue = DispatchQueue(label: "screeaner.capture", qos: .userInteractive)
    private var display: VirtualDisplay?
    private var capturer: ScreenCapturer?
    private var encoder: VideoEncoder?      // captureQueue only after start
    private var lastFrame: CVPixelBuffer?   // captureQueue only
    private var injector: InputInjector?

    private let lock = NSLock()
    private var stopped = false
    private var frames = 0
    private var bytes = 0
    private var encodeMicrosSum = 0
    private var dropped = 0

    /// Both called on the main queue.
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
                action: payload[payload.startIndex],
                x: payload.readFloat(at: 1),
                y: payload.readFloat(at: 5),
                pressure: payload.readFloat(at: 9),
                button: payload[payload.startIndex + 13]
            )
        case .scroll:
            guard payload.count >= 16 else { return }
            injector?.scroll(
                dx: payload.readFloat(at: 0),
                dy: payload.readFloat(at: 4),
                x: payload.readFloat(at: 8),
                y: payload.readFloat(at: 12)
            )
        case .keyframeRequest:
            captureQueue.async {
                guard let encoder = self.encoder else { return }
                encoder.forceKeyframe()
                // The screen may be static, so re-encode the last frame right away.
                if let frame = self.lastFrame { encoder.encode(frame) }
            }
        case .ping:
            connection.send(.pong, payload)
        default:
            break
        }
    }

    // MARK: - Pipeline setup (main queue)

    private func begin(_ hello: Hello) {
        guard display == nil, !isStopped else { return }
        let defaults = UserDefaults.standard
        let hiDPI = defaults.bool(forKey: SettingsKey.hiDPI)
        let width = min(max(hello.width, 640), 4096) & ~1
        let height = min(max(hello.height, 360), 4096) & ~1
        let fps = max(24, min(defaults.integer(forKey: SettingsKey.fps), Int(hello.refreshRate.rounded())))
        let wantsHEVC = defaults.string(forKey: SettingsKey.codec) == Codec.hevc.rawValue
        let codec: Codec = (wantsHEVC && hello.codecs.contains(Codec.hevc.rawValue)) ? .hevc : .h264
        let bitrate = max(2, defaults.integer(forKey: SettingsKey.bitrateMbps)) * 1_000_000
        clientName = hello.name

        guard let display = VirtualDisplay(
            name: "Screeaner – \(hello.name)",
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
            guard let self else { return }
            self.connection.sendVideo(data, ptsMicros: pts, isKey: isKey, encodeMicros: encodeMicros)
            self.lock.lock()
            self.frames += 1
            self.bytes += data.count
            self.encodeMicrosSum += Int(encodeMicros)
            self.lock.unlock()
        }
        self.encoder = encoder
        injector = InputInjector(displayID: display.displayID)

        let config = StreamConfig(codec: codec.rawValue, width: width, height: height, fps: fps)
        if let json = try? JSONEncoder().encode(config) {
            connection.send(.config, json)
        }

        let capturer = ScreenCapturer(queue: captureQueue)
        capturer.onFrame = { [weak self] pixelBuffer in self?.captured(pixelBuffer) }
        capturer.onStop = { [weak self] error in
            DispatchQueue.main.async { self?.fail("Captura interrompida: \(error.localizedDescription)") }
        }
        self.capturer = capturer
        summary = "\(width)×\(height) · \(fps) Hz · \(codec == .hevc ? "HEVC" : "H.264")"
        onStatus?("Iniciando captura…")

        let displayID = display.displayID
        Task {
            do {
                try await capturer.start(displayID: displayID, width: width, height: height, fps: fps)
                DispatchQueue.main.async { self.onStatus?("Transmitindo para \(self.clientName)") }
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
        // Never queue latency: if the socket is behind, skip this frame entirely.
        // Dropping before the encoder keeps the reference chain intact.
        if connection.isCongested {
            lock.lock()
            dropped += 1
            lock.unlock()
            return
        }
        encoder?.encode(pixelBuffer)
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
        lock.unlock()
        guard !alreadyStopped else { return }

        connection.close()
        DispatchQueue.main.async {
            self.capturer?.stop()
            self.capturer = nil
            self.captureQueue.async {
                self.encoder?.invalidate()
                self.encoder = nil
                self.lastFrame = nil
            }
            self.display = nil // unplugs the virtual monitor
            self.onEnded?()
        }
    }

    /// Returns the counters accumulated since the previous call (call once per second).
    func takeStats() -> Stats {
        lock.lock()
        defer { lock.unlock() }
        let stats = Stats(
            fps: frames,
            mbps: Double(bytes) * 8 / 1_000_000,
            encodeMs: frames > 0 ? Double(encodeMicrosSum) / Double(frames) / 1000 : 0,
            dropped: dropped
        )
        frames = 0
        bytes = 0
        encodeMicrosSum = 0
        dropped = 0
        return stats
    }
}
