import AppKit
import CoreGraphics
import Foundation
import Network
import ServiceManagement
import ScreeanerCore

/// One row of the tablet list in the menu.
struct TabletRow: Identifiable, Equatable {
    let id: ObjectIdentifier
    var name: String
    var detail: String
    var stats: String
    /// Latest status of this tablet ("Transmitindo", an error, an update in progress…).
    var status = ""
    /// Streaming; false while pairing or for an old app that can only be updated.
    var live = false
    /// The encoder fell back to normal rate control (about one frame more latency).
    var encoderNormal = false
    /// "H.264" or "HEVC", to word the encoder hint.
    var codec = ""
    /// The tablet's device id once the handshake is done.
    var deviceID: String?
    /// nil when the tablet is not in the paired list (still pairing, old app).
    var favorite: Bool?
}

/// A message shown at the top of the menu until it expires (info) or is closed (error).
struct Notice: Equatable {
    let text: String
    let isError: Bool
}

/// App-wide state. Everything here runs on the main queue.
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published var status = "Aguardando o tablet…"
    @Published var notice: Notice?
    @Published var tablets: [TabletRow] = []
    @Published var usbDevices = 0
    @Published var hasScreenPermission = CGPreflightScreenCaptureAccess()
    @Published var hasInputPermission = InputInjector.hasPermission
    /// Tablets waiting for the user to compare the code and allow them.
    @Published var pairingRequests: [PairingRequest] = []
    @Published var pairedTablets: [PairedTablet] = []
    @Published var macScreenNames: [String] = []
    /// Cached: SMAppService is asked when the menu opens, not on every redraw.
    @Published private(set) var launchAtLoginEnabled = false
    @Published private(set) var launchAtLoginNeedsApproval = false
    /// Cached: the network interfaces are read every few seconds, not on every redraw.
    @Published private(set) var ipAddresses: [String] = []
    /// Internet access (no server in between): what the router said.
    @Published private(set) var online = OnlineAccess.Status()
    /// Recent connection attempts from the internet, to shrug off floods.
    private var internetAttempts: [Date] = []

    private let server = StreamServer()
    private let udpHub = UdpHub()
    private let adb = AdbBridge()
    private let clipboard = ClipboardSync()
    private let pairingPanel = PairingPanel()
    private var sessions: [StreamSession] = []
    /// "device id:version code" pairs adb already failed on, so it is not retried on every reconnect.
    private var adbUpdateFailed = Set<String>()
    /// "device:version code" offers already made this launch, so a reconnect does not ask again.
    private var updatesOffered = Set<String>()
    private var timer: Timer?
    private var ticks = 0
    private var noticeExpiry: DispatchWorkItem?
    private var restartWork: DispatchWorkItem?

    var adbAvailable: Bool { AdbBridge.adbPath != nil }
    /// Tablets actually streaming (pairing and legacy sessions do not count).
    var liveCount: Int { tablets.filter(\.live).count }

    private init() {
        SettingsKey.registerDefaults()
    }

    func start() {
        // A crash while a screen was dimmed must not leave it dark.
        Brightness.restoreLeftovers()
        pairedTablets = PairingStore.shared.tablets
        refreshLaunchAtLogin()
        ipAddresses = Self.localIPAddresses()

        OnlineAccess.shared.onChange = { [weak self] status in
            guard let self else { return }
            let addressesChanged = status.ipv6 != self.online.ipv6 || status.publicIPv4 != self.online.publicIPv4
                || status.tcpPort != self.online.tcpPort || status.enabled != self.online.enabled
            self.online = status
            if addressesChanged { self.sessions.forEach { $0.sendEndpoints() } }
        }
        OnlineAccess.shared.setEnabled(UserDefaults.standard.bool(forKey: SettingsKey.onlineAccess))

        server.onError = { [weak self] message in
            DispatchQueue.main.async { self?.show(message, error: true) }
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
            guard let self, !self.sessions.isEmpty, UserDefaults.standard.bool(forKey: SettingsKey.clipboard) else { return }
            self.sessions.forEach { $0.sendClipboard(text) }
        }
        clipboard.start()

        if !hasScreenPermission { CGRequestScreenCaptureAccess() }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        tick()
    }

    /// Quitting: end every stream and give the built-in screen its brightness back
    /// now (the asynchronous clean-up of `stop()` would never run).
    func shutdown() {
        timer?.invalidate()
        for session in sessions { session.stop() }
        Brightness.restoreLeftovers()
        OnlineAccess.shared.shutdown()
    }

    // MARK: - Notices

    /// Shows a message at the top of the menu. Info disappears after a while; errors stay until closed.
    func show(_ text: String, error: Bool = false) {
        noticeExpiry?.cancel()
        notice = Notice(text: text, isError: error)
        guard !error else { return }
        let work = DispatchWorkItem { [weak self] in
            if self?.notice?.text == text { self?.notice = nil }
        }
        noticeExpiry = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: work)
    }

    func dismissNotice() {
        noticeExpiry?.cancel()
        notice = nil
    }

    // MARK: - Sessions

    private func accept(_ connection: ClientConnection) {
        if connection.isInternet {
            // Closed unless the user turned internet access on; and never a flood.
            let now = Date()
            internetAttempts = internetAttempts.filter { now.timeIntervalSince($0) < 60 } + [now]
            guard OnlineAccess.shared.isEnabled, internetAttempts.count <= 20 else {
                connection.close()
                return
            }
        }
        let session = StreamSession(connection: connection)
        session.onHello = { [weak self, weak session] in
            guard let self, let session, let device = session.deviceID else { return }
            // The same tablet reconnecting (Wi-Fi hiccup, mode change) replaces its old session.
            for other in self.sessions where other !== session && other.deviceID == device {
                other.stop()
            }
            self.updateIfOutdated(session)
        }
        session.onLegacyClient = { [weak self, weak session] in
            guard let self, let session else { return }
            self.updateLegacy(session)
        }
        session.onStatus = { [weak self] _ in self?.refreshRows() }
        session.onEnded = { [weak self, weak session] in
            guard let self, let session else { return }
            self.sessions.removeAll { $0 === session }
            if let failure = session.failure {
                self.show("\(session.clientName): \(failure)", error: true)
            }
            self.refreshRows()
        }
        session.onPairingRequest = { [weak self] request in
            guard let self else { return }
            self.pairingRequests.append(request)
            self.pairingPanel.show(model: self)
        }
        session.onPairingFinished = { [weak self] id in self?.dismissRequest(id) }
        session.onPaired = { [weak self] in
            self?.pairedTablets = PairingStore.shared.tablets
            self?.refreshRows()
        }
        session.onClipboard = { [weak self] text in self?.clipboard.apply(text) }
        sessions.append(session)
        session.start()
        refreshRows()
    }

    /// A tablet with an app from before the secure protocol (0.6 or older)
    /// can only be updated: it cannot stream with this Mac.
    private func updateLegacy(_ session: StreamSession) {
        guard let apk = TabletApk.bundled else {
            show("\(session.clientName) tem um app antigo (v\(session.clientVersion ?? "?")): "
                 + "instale a versão \(Proto.appVersion) no tablet", error: true)
            return
        }
        show("\(session.clientName) tem um app antigo: enviando a v\(apk.versionName)…")
        update(session, to: apk)
    }

    /// Brings an outdated tablet to the app version bundled here: adb over USB
    /// (no taps; the app reopens by itself), else an offer the tablet accepts with one tap.
    private func updateIfOutdated(_ session: StreamSession) {
        guard let apk = TabletApk.bundled,
              TabletVersion.isOutdated(tabletVersion: session.clientVersion, bundledCode: apk.versionCode)
        else { return }
        update(session, to: apk)
    }

    private func update(_ session: StreamSession, to apk: TabletApk) {
        let key = "\(session.deviceID ?? session.clientName):\(apk.versionCode)"
        guard session.connection.isLoopback, adbAvailable, !adbUpdateFailed.contains(key) else {
            // Ask once per launch; the tablet also remembers a "Depois".
            if updatesOffered.insert(key).inserted || session.isLegacy { session.offerUpdate(apk) }
            return
        }
        show("Atualizando o app de \(session.clientName) para v\(apk.versionName)…")
        adb.install(apk.url, model: session.clientName) { [weak self, weak session] installed in
            guard let self else { return }
            if installed {
                self.show("App de \(session?.clientName ?? "tablet") atualizado para v\(apk.versionName)")
            } else {
                self.adbUpdateFailed.insert(key)
                self.show("Não deu para atualizar pelo USB: o tablet vai perguntar pelo Wi‑Fi")
                session?.offerUpdate(apk)
            }
        }
    }

    // MARK: - Pairing

    func respond(to request: PairingRequest, decision: PairingDecision) {
        dismissRequest(request.id)
        request.decide(decision)
    }

    /// Favorite mode: a favorite connects by itself without a code; once there is one,
    /// the other paired tablets need approval (unless the menu setting is off).
    /// Tablets already streaming are never cut.
    func setFavorite(_ deviceId: String, _ favorite: Bool) {
        let hadFavorites = PairingStore.shared.hasFavorites
        PairingStore.shared.setFavorite(deviceId: deviceId, favorite)
        pairedTablets = PairingStore.shared.tablets
        refreshRows()
        let hasFavorites = PairingStore.shared.hasFavorites
        let favoritesOnly = UserDefaults.standard.bool(forKey: SettingsKey.favoritesOnly)
        if favorite && !hadFavorites && favoritesOnly {
            show("Modo favorito ligado: só os tablets com ★ conectam sozinhos; os outros pedem aprovação")
        } else if !hasFavorites && hadFavorites {
            show("Sem favoritos: todos os tablets autorizados voltam a conectar sozinhos")
        }
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

    func setOnlineAccess(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: SettingsKey.onlineAccess)
        OnlineAccess.shared.setEnabled(enabled)
        if !enabled {
            for session in sessions where session.connection.isInternet { session.stop() }
        }
    }

    func refreshOnlineAccess() {
        OnlineAccess.shared.refresh()
    }

    /// Video settings changed: streaming tablets reconnect with the new ones.
    /// Debounced, so clicking through several values costs one restart; tablets
    /// still pairing or being updated are left alone.
    func restartSessions(onlyMirror: Bool = false) {
        restartWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            for session in self.sessions where session.hasStarted && !session.isLegacy {
                if onlyMirror && session.displayMode != .mirror { continue }
                session.stop()
            }
        }
        restartWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6, execute: work)
    }

    /// The clipboard setting applies live: no reconnect.
    func clipboardSettingChanged(_ enabled: Bool) {
        for session in sessions { session.setClipboardAllowed(enabled) }
    }

    func autoAdbChanged(_ enabled: Bool) {
        if !enabled { usbDevices = 0 }
    }

    func refreshLaunchAtLogin() {
        let status = SMAppService.mainApp.status
        let enabled = status == .enabled
        let approval = status == .requiresApproval
        if enabled != launchAtLoginEnabled { launchAtLoginEnabled = enabled }
        if approval != launchAtLoginNeedsApproval { launchAtLoginNeedsApproval = approval }
    }

    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            show("Não foi possível alterar a abertura no login: \(error.localizedDescription)", error: true)
        }
        refreshLaunchAtLogin()
        if launchAtLoginNeedsApproval {
            show("Aprove o Screeaner em Ajustes do Sistema › Itens de Início para abrir com o Mac")
        }
    }

    func openLoginItemsSettings() {
        SMAppService.openSystemSettingsLoginItems()
    }

    /// Called when the menu opens: values that are too costly to poll every second.
    func menuOpened() {
        refreshLaunchAtLogin()
        let addresses = Self.localIPAddresses()
        if addresses != ipAddresses { ipAddresses = addresses }
    }

    private func refreshRows(stats: [ObjectIdentifier: String] = [:]) {
        let previous = Dictionary(uniqueKeysWithValues: tablets.map { ($0.id, $0.stats) })
        let rows = sessions.map { session in
            let id = ObjectIdentifier(session)
            let live = session.hasStarted && !session.isLegacy
            return TabletRow(
                id: id, name: session.clientName, detail: session.summary,
                stats: live ? (stats[id] ?? previous[id] ?? "") : "",
                status: session.lastStatus,
                live: live,
                encoderNormal: session.lowLatencyEncoder == false,
                codec: session.codecName,
                deviceID: session.deviceID,
                favorite: session.deviceID.flatMap { id in pairedTablets.first { $0.id == id }?.favorite }
            )
        }
        // Only publish real changes: every publish redraws the whole menu.
        if rows != tablets { tablets = rows }
        let waiting = sessions.isEmpty ? "Aguardando o tablet…" : (liveCount == 0 ? "Conectando…" : status)
        if waiting != status { status = waiting }
    }

    private func tick() {
        ticks += 1
        if !sessions.isEmpty {
            var lines: [ObjectIdentifier: String] = [:]
            for session in sessions where session.hasStarted && !session.isLegacy {
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
                if self?.usbDevices != count { self?.usbDevices = count }
            }
        }
        if ticks % 2 == 0 {
            let screen = CGPreflightScreenCaptureAccess()
            if screen != hasScreenPermission { hasScreenPermission = screen }
            let input = InputInjector.hasPermission
            if input != hasInputPermission { hasInputPermission = input }
            let names = DisplayArranger.macScreens().map { $0.name }
            if names != macScreenNames { macScreenNames = names }
        }
        if ticks % 10 == 0 {
            let addresses = Self.localIPAddresses()
            if addresses != ipAddresses { ipAddresses = addresses }
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
