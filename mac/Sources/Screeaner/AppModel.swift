import CoreGraphics
import Foundation
import Network

/// One row of the tablet list in the menu.
struct TabletRow: Identifiable {
    let id: ObjectIdentifier
    var name: String
    var detail: String
    var stats: String
}

/// App-wide state. Everything here runs on the main queue.
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published var status = "Aguardando o tablet…"
    @Published var tablets: [TabletRow] = []
    @Published var usbDevices = 0
    @Published var hasScreenPermission = CGPreflightScreenCaptureAccess()
    @Published var hasInputPermission = InputInjector.hasPermission

    private let server = StreamServer()
    private let udpHub = UdpHub()
    private let adb = AdbBridge()
    private var sessions: [StreamSession] = []
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

        udpHub.onFlow = { [weak self] token, flow in
            if let session = self?.sessions.first(where: { $0.token == token }) {
                session.attachUdp(flow)
            } else {
                flow.cancel()
            }
        }
        udpHub.start()

        if !hasScreenPermission { CGRequestScreenCaptureAccess() }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        tick()
    }

    private func accept(_ connection: ClientConnection) {
        let session = StreamSession(connection: connection)
        session.onHello = { [weak self, weak session] in
            guard let self, let session, let device = session.deviceID else { return }
            // The same tablet reconnecting (rotation, Wi-Fi hiccup) replaces its old session.
            for other in self.sessions where other !== session && other.deviceID == device {
                other.stop()
            }
        }
        session.onStatus = { [weak self, weak session] message in
            guard let self, let session else { return }
            if session.failure != nil { self.status = message }
            self.refreshRows()
        }
        session.onEnded = { [weak self, weak session] in
            guard let self, let session else { return }
            self.sessions.removeAll { $0 === session }
            if let failure = session.failure {
                self.status = failure
            } else if self.sessions.isEmpty {
                self.status = "Aguardando o tablet…"
            }
            self.refreshRows()
        }
        sessions.append(session)
        status = "Tablet conectado"
        session.start()
        refreshRows()
    }

    /// Settings changed: drop the sessions; the tablets reconnect with the new ones.
    func restartSessions() {
        for session in sessions { session.stop() }
    }

    private func refreshRows(stats: [ObjectIdentifier: String] = [:]) {
        let previous = Dictionary(uniqueKeysWithValues: tablets.map { ($0.id, $0.stats) })
        tablets = sessions.map { session in
            let id = ObjectIdentifier(session)
            return TabletRow(
                id: id, name: session.clientName, detail: session.summary,
                stats: stats[id] ?? previous[id] ?? ""
            )
        }
        if !sessions.isEmpty, sessions.allSatisfy({ $0.failure == nil }) {
            status = sessions.count == 1 ? "1 tablet conectado" : "\(sessions.count) tablets conectados"
        }
    }

    private func tick() {
        ticks += 1
        if !sessions.isEmpty {
            var lines: [ObjectIdentifier: String] = [:]
            for session in sessions {
                let stats = session.tick()
                var line = String(
                    format: "%@ · %d fps · %.1f/%.0f Mbps · enc %.1f ms",
                    stats.transport, stats.fps, stats.mbps, stats.targetMbps, stats.encodeMs
                )
                if stats.lossPercent >= 0.1 { line += String(format: " · perda %.1f%%", stats.lossPercent) }
                if stats.skipped > 0 { line += " · \(stats.skipped) pulados" }
                lines[ObjectIdentifier(session)] = line
            }
            refreshRows(stats: lines)
        }
        // Keep looking for USB tablets even while one is connected (multi-tablet).
        if ticks % 3 == 1, UserDefaults.standard.bool(forKey: SettingsKey.autoAdb) {
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
