import AppKit
import CoreGraphics
import Foundation
import Network
import ServiceManagement

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
    /// Tablets waiting for the user to compare the code and allow them.
    @Published var pairingRequests: [PairingRequest] = []
    @Published var pairedTablets: [PairedTablet] = []
    @Published var macScreenNames: [String] = []

    private let server = StreamServer()
    private let udpHub = UdpHub()
    private let adb = AdbBridge()
    private let clipboard = ClipboardSync()
    private let pairingPanel = PairingPanel()
    private var sessions: [StreamSession] = []
    private var timer: Timer?
    private var ticks = 0

    var adbAvailable: Bool { AdbBridge.adbPath != nil }

    private init() {
        SettingsKey.registerDefaults()
    }

    func start() {
        // A crash while a screen was dimmed must not leave it dark.
        Brightness.restoreLeftovers()
        pairedTablets = PairingStore.shared.tablets

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

        clipboard.onCopy = { [weak self] text in
            guard UserDefaults.standard.bool(forKey: SettingsKey.clipboard) else { return }
            self?.sessions.forEach { $0.sendClipboard(text) }
        }
        clipboard.start()

        if !hasScreenPermission { CGRequestScreenCaptureAccess() }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        tick()
    }

    private func accept(_ connection: ClientConnection) {
        let session = StreamSession(connection: connection)
        session.onHello = { [weak self, weak session] in
            guard let self, let session, let device = session.deviceID else { return }
            // The same tablet reconnecting (Wi-Fi hiccup, mode change) replaces its old session.
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
        session.onPairingRequest = { [weak self] request in
            guard let self else { return }
            self.pairingRequests.append(request)
            self.pairingPanel.show(model: self)
        }
        session.onPairingFinished = { [weak self] id in self?.dismissRequest(id) }
        session.onPaired = { [weak self] in self?.pairedTablets = PairingStore.shared.tablets }
        session.onClipboard = { [weak self] text in self?.clipboard.apply(text) }
        sessions.append(session)
        session.start()
        refreshRows()
    }

    // MARK: - Pairing

    func respond(to request: PairingRequest, allowed: Bool) {
        dismissRequest(request.id)
        request.decide(allowed)
    }

    private func dismissRequest(_ id: UUID) {
        pairingRequests.removeAll { $0.id == id }
        if pairingRequests.isEmpty { pairingPanel.hide() }
    }

    /// Forgets a tablet: it must show a code and be allowed again to connect.
    func revoke(_ tablet: PairedTablet) {
        PairingStore.shared.remove(deviceId: tablet.id)
        pairedTablets = PairingStore.shared.tablets
        for session in sessions where session.deviceID == tablet.id { session.stop() }
    }

    // MARK: - Settings

    /// Settings changed: drop the sessions; the tablets reconnect with the new ones.
    func restartSessions() {
        for session in sessions { session.stop() }
    }

    var launchAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set {
            do {
                if newValue { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                status = "Não foi possível alterar a abertura no login: \(error.localizedDescription)"
            }
            objectWillChange.send()
        }
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
        let defaults = UserDefaults.standard
        if ticks % 3 == 1, defaults.bool(forKey: SettingsKey.autoAdb) {
            adb.refresh(openApp: defaults.bool(forKey: SettingsKey.autoOpenApp)) { [weak self] count in
                self?.usbDevices = count
            }
        }
        if ticks % 2 == 0 {
            hasScreenPermission = CGPreflightScreenCaptureAccess()
            hasInputPermission = InputInjector.hasPermission
            let names = DisplayArranger.macScreens().map { $0.name }
            if names != macScreenNames { macScreenNames = names }
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
