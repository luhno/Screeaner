import CoreGraphics
import Foundation

/// App-wide state. Everything here runs on the main queue.
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published var status = "Aguardando o tablet…"
    @Published var connected = false
    @Published var detail = ""
    @Published var statsLine = ""
    @Published var usbDevices = 0
    @Published var hasScreenPermission = CGPreflightScreenCaptureAccess()
    @Published var hasInputPermission = InputInjector.hasPermission

    private let server = StreamServer()
    private let adb = AdbBridge()
    private var session: StreamSession?
    private var timer: Timer?
    private var ticks = 0

    var adbAvailable: Bool { AdbBridge.adbPath != nil }

    private init() {
        SettingsKey.registerDefaults()
    }

    func start() {
        server.onError = { [weak self] message in
            DispatchQueue.main.async { self?.status = message }
        }
        server.onClient = { [weak self] connection in
            DispatchQueue.main.async { self?.accept(connection) }
        }
        server.start()

        if !hasScreenPermission { CGRequestScreenCaptureAccess() }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        tick()
    }

    private func accept(_ connection: ClientConnection) {
        // Newest connection wins: a reconnecting tablet replaces its stale session.
        session?.stop()
        let session = StreamSession(connection: connection)
        session.onStatus = { [weak self, weak session] message in
            guard let self, self.session === session else { return }
            self.status = message
            self.detail = session?.summary ?? ""
        }
        session.onEnded = { [weak self, weak session] in
            guard let self, self.session === session else { return }
            self.session = nil
            self.connected = false
            self.statsLine = ""
            self.detail = ""
            self.status = session?.failure ?? "Aguardando o tablet…"
        }
        self.session = session
        connected = true
        status = "Tablet conectado (\(connection.peer))"
        session.start()
    }

    /// Settings changed: drop the session; the tablet reconnects with the new ones.
    func restartSession() {
        session?.stop()
    }

    private func tick() {
        ticks += 1
        if let session {
            let stats = session.takeStats()
            var line = String(format: "%d fps · %.1f Mbps · encode %.1f ms", stats.fps, stats.mbps, stats.encodeMs)
            if stats.dropped > 0 { line += " · \(stats.dropped) descartados" }
            statsLine = line
        } else if ticks % 3 == 1, UserDefaults.standard.bool(forKey: SettingsKey.autoAdb) {
            adb.refresh { [weak self] count in self?.usbDevices = count }
        }
        if ticks % 2 == 0 {
            hasScreenPermission = CGPreflightScreenCaptureAccess()
            hasInputPermission = InputInjector.hasPermission
        }
    }

    static func localIPAddresses() -> [String] {
        var result: [String] = []
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0 else { return result }
        defer { freeifaddrs(list) }
        var cursor = list
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            let flags = Int32(entry.pointee.ifa_flags)
            guard let address = entry.pointee.ifa_addr,
                  address.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0
            else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count),
                           nil, 0, NI_NUMERICHOST) == 0 {
                result.append(String(cString: host))
            }
        }
        return result
    }
}
