import AppKit
import SwiftUI

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Menu-bar only, even when launched outside an .app bundle (`swift run`).
        NSApp.setActivationPolicy(.accessory)
        AppModel.shared.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        // Ends the streams and restores a dimmed built-in screen before the process goes.
        AppModel.shared.shutdown()
    }
}

@main
struct ScreeanerApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        MenuBarExtra("Screeaner", systemImage: "rectangle.on.rectangle") {
            MenuView(model: AppModel.shared)
        }
        .menuBarExtraStyle(.window)
    }
}

struct MenuView: View {
    @ObservedObject var model: AppModel
    @AppStorage(SettingsKey.hiDPI) private var hiDPI = true
    @AppStorage(SettingsKey.fps) private var fps = 60
    @AppStorage(SettingsKey.bitrateMbps) private var bitrate = 20
    @AppStorage(SettingsKey.codec) private var codec = Codec.h264.rawValue
    @AppStorage(SettingsKey.autoAdb) private var autoAdb = true
    @AppStorage(SettingsKey.adaptiveBitrate) private var adaptiveBitrate = true
    @AppStorage(SettingsKey.udpOverWifi) private var udpOverWifi = true
    @AppStorage(SettingsKey.clipboard) private var clipboard = true
    @AppStorage(SettingsKey.autoOpenApp) private var autoOpenApp = true
    @AppStorage(SettingsKey.mirrorScreen) private var mirrorScreen = ""
    @AppStorage(SettingsKey.favoritesOnly) private var favoritesOnly = true
    @AppStorage(SettingsKey.autoUpdate) private var autoUpdate = true
    @State private var showPaired = false
    @State private var contentHeight: CGFloat = 0

    /// Taller than this, the middle of the menu scrolls (header and footer stay put).
    private var maxScrollHeight: CGFloat {
        let screen = NSScreen.main?.visibleFrame.height ?? 800
        return max(240, screen - 190)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            if let notice = model.notice { noticeBanner(notice) }
            ScrollView(.vertical, showsIndicators: contentHeight > maxScrollHeight) {
                content
                    .background(GeometryReader { geometry in
                        Color.clear.preference(key: ContentHeightKey.self, value: geometry.size.height)
                    })
            }
            .frame(height: min(max(contentHeight, 1), maxScrollHeight))
            .onPreferenceChange(ContentHeightKey.self) { contentHeight = $0 }
            footer
        }
        .padding(18)
        .frame(width: 360)
        .background(Dot.canvas)
        .foregroundStyle(Dot.ink)
        .environment(\.colorScheme, .dark)
        .onAppear { model.menuOpened() }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(model.pairingRequests) { request in
                PairingRequestView(request: request) { decision in
                    model.respond(to: request, decision: decision)
                }
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 14).fill(Dot.surface))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Dot.signal, lineWidth: 1))
            }
            if model.tablets.isEmpty {
                waiting
            } else {
                ForEach(model.tablets) { tablet in card(tablet) }
            }
            settings
            onlineSection
            paired
            updateSection
            if !model.hasScreenPermission || !model.hasInputPermission {
                permissions
            }
        }
    }

    private func noticeBanner(_ notice: Notice) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            DotMarker(color: notice.isError ? Dot.signal : Dot.ink)
            Text(notice.text).font(Dot.text(12.5))
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
            Spacer(minLength: 4)
            Button {
                model.dismissNotice()
            } label: {
                Text("×").font(Dot.text(14, .medium)).foregroundStyle(Dot.inkSecondary)
            }
            .buttonStyle(.plain)
            .help("Fechar")
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(Dot.surface))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(notice.isError ? Dot.signal : Dot.line, lineWidth: 1))
    }

    private var header: some View {
        HStack(alignment: .center) {
            Text("SCREEANER")
                .font(Dot.display(20))
                .tracking(0.8)
            Spacer()
            HStack(spacing: 6) {
                DotMarker(color: model.liveCount == 0 ? Dot.inkSecondary : Dot.signal)
                Text(model.liveCount == 0 ? "AGUARDANDO" : (model.liveCount == 1 ? "AO VIVO" : "AO VIVO · \(model.liveCount)"))
                    .font(Dot.mono(10.5))
                    .tracking(1)
                    .foregroundStyle(Dot.inkSecondary)
            }
        }
    }

    private var waiting: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(model.status).font(Dot.text(14, .medium))
                .fixedSize(horizontal: false, vertical: true)
            Text(usbHint).font(Dot.text(12)).foregroundStyle(Dot.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Dot.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Dot.line, lineWidth: 1))
    }

    private func card(_ tablet: TabletRow) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                if tablet.live { DotMarker(color: Dot.signal) }
                Text(tablet.name).font(Dot.text(14, .semibold))
                Spacer(minLength: 4)
                if let id = tablet.deviceID, let favorite = tablet.favorite {
                    starButton(favorite: favorite) { model.setFavorite(id, !favorite) }
                }
            }
            if !tablet.status.isEmpty && tablet.status != "Transmitindo" {
                Text(tablet.status).font(Dot.text(12)).foregroundStyle(Dot.inkSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !tablet.detail.isEmpty {
                Text(tablet.detail).font(Dot.mono(11)).foregroundStyle(Dot.inkSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !tablet.stats.isEmpty {
                Text(tablet.stats).font(Dot.mono(12)).monospacedDigit()
                    .fixedSize(horizontal: false, vertical: true)
            }
            if tablet.encoderNormal {
                Rectangle().fill(Dot.line).frame(height: 1)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    DotMarker(color: Dot.signal)
                    Text(tablet.codec == "HEVC"
                         ? "Encoder normal: cerca de 1 quadro a mais de atraso. Teste o H.264."
                         : "Encoder normal: cerca de 1 quadro a mais de atraso. Rode o benchmark no tablet para achar a melhor configuração.")
                        .font(Dot.text(12))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Dot.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Dot.line, lineWidth: 1))
    }

    private var settings: some View {
        VStack(alignment: .leading, spacing: 14) {
            section("01 / Resolução") {
                DotSegmented(options: [(label: "Retina", value: true), (label: "Nativa 1×", value: false)], selection: $hiDPI)
            }
            section("02 / Quadros por segundo") {
                DotSegmented(options: [30, 60, 90, 120].map { (label: "\($0)", value: $0) }, selection: $fps, mono: true)
            }
            section("03 / Qualidade máxima · Mbps") {
                DotSegmented(options: [10, 20, 40, 80].map { (label: "\($0)", value: $0) }, selection: $bitrate, mono: true)
            }
            section("04 / Codec") {
                DotSegmented(
                    options: [(label: "H.264", value: Codec.h264.rawValue), (label: "HEVC", value: Codec.hevc.rawValue)],
                    selection: $codec
                )
            }
            if model.macScreenNames.count > 1 {
                section("05 / Tela espelhada") {
                    let options = [(label: "Principal", value: "")] + model.macScreenNames.map { (label: $0, value: $0) }
                    // Long names or many screens do not fit as segments: use a menu.
                    if options.count <= 3 && model.macScreenNames.allSatisfy({ $0.count <= 14 }) {
                        DotSegmented(options: options, selection: $mirrorScreen)
                    } else {
                        Picker("", selection: $mirrorScreen) {
                            ForEach(options, id: \.value) { Text($0.label).tag($0.value) }
                        }
                        .labelsHidden()
                        .pickerStyle(.menu)
                    }
                    if !mirrorScreen.isEmpty && !model.macScreenNames.contains(mirrorScreen) {
                        Text("“\(mirrorScreen)” não está conectada: espelhando a tela principal.")
                            .font(Dot.text(11.5)).foregroundStyle(Dot.inkSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            VStack(spacing: 0) {
                toggleRow("Bitrate adaptativo", isOn: $adaptiveBitrate)
                toggleRow("UDP com correção de erros no Wi‑Fi", isOn: $udpOverWifi)
                toggleRow("USB automático (adb)", isOn: $autoAdb)
                    .disabled(!model.adbAvailable)
                    .opacity(model.adbAvailable ? 1 : 0.45)
                toggleRow("Abrir o app no tablet ao conectar o USB", isOn: $autoOpenApp)
                    .disabled(!model.adbAvailable || !autoAdb)
                    .opacity(model.adbAvailable && autoAdb ? 1 : 0.45)
                toggleRow("Área de transferência compartilhada", isOn: $clipboard)
                toggleRow("Abrir o Screeaner ao iniciar o Mac", isOn: Binding(
                    get: { model.launchAtLoginEnabled || model.launchAtLoginNeedsApproval },
                    set: { model.setLaunchAtLogin($0) }
                ))
                if model.launchAtLoginNeedsApproval {
                    HStack(spacing: 8) {
                        Text("Falta aprovar em Ajustes › Itens de Início.")
                            .font(Dot.text(11.5)).foregroundStyle(Dot.inkSecondary)
                        Spacer()
                        Button("Abrir") { model.openLoginItemsSettings() }.buttonStyle(DotButtonStyle())
                    }
                    .padding(.vertical, 6)
                }
            }
            .overlay(alignment: .top) { Rectangle().fill(Dot.line).frame(height: 1) }
            if !model.tablets.isEmpty {
                Text(usbHint).font(Dot.text(11.5)).foregroundStyle(Dot.inkSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .onChange(of: hiDPI) { _ in model.restartSessions() }
        .onChange(of: fps) { _ in model.restartSessions() }
        .onChange(of: bitrate) { _ in model.restartSessions() }
        .onChange(of: codec) { _ in model.restartSessions() }
        .onChange(of: adaptiveBitrate) { _ in model.restartSessions() }
        .onChange(of: udpOverWifi) { _ in model.restartSessions() }
        .onChange(of: clipboard) { model.clipboardSettingChanged($0) }
        .onChange(of: mirrorScreen) { _ in model.restartSessions(onlyMirror: true) }
        .onChange(of: autoAdb) { model.autoAdbChanged($0) }
    }

    /// Updates from GitHub Releases: the switch, the state and what can be done now.
    private var updateSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            toggleRow("Atualizar sozinho pelo GitHub", isOn: $autoUpdate)
            HStack(alignment: .center, spacing: 8) {
                DotMarker(color: updateNeedsAttention ? Dot.signal : Dot.inkSecondary)
                Text(updateText).font(Dot.text(12)).foregroundStyle(Dot.inkSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 4)
                switch model.update {
                case .available:
                    Button("Baixar e instalar") { model.installUpdate() }.buttonStyle(DotButtonStyle(primary: true))
                case .ready:
                    Button("Instalar agora") { model.installUpdate() }.buttonStyle(DotButtonStyle(primary: true))
                case .checking, .downloading, .installing:
                    EmptyView()
                default:
                    Button("Verificar") { model.checkForUpdates() }.buttonStyle(DotButtonStyle())
                }
            }
        }
        .onChange(of: autoUpdate) { on in if on { model.checkForUpdates() } }
    }

    private var updateNeedsAttention: Bool {
        switch model.update {
        case .available, .ready, .failed: return true
        default: return false
        }
    }

    private var updateText: String {
        switch model.update {
        case .idle: return "v\(Proto.appVersion) · procura versões novas no GitHub"
        case .checking: return "Procurando versões novas…"
        case .upToDate(let date): return "v\(Proto.appVersion) é a mais nova · \(date.formatted(date: .omitted, time: .shortened))"
        case .available(let version): return "v\(version) disponível"
        case .downloading(let version): return "Baixando a v\(version)…"
        case .ready(let version):
            return model.liveCount > 0
                ? "v\(version) pronta: instala quando a transmissão terminar"
                : "v\(version) pronta para instalar"
        case .installing(let version): return "Instalando a v\(version)…"
        case .failed(let message): return message
        }
    }

    /// Internet access with no server in between: the switch and what the router said.
    private var onlineSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            toggleRow("Acesso pela internet (sem servidor)", isOn: Binding(
                get: { model.online.enabled },
                set: { model.setOnlineAccess($0) }
            ))
            if model.online.enabled {
                let online = model.online
                if online.checking && online.ipv6.isEmpty && online.publicIPv4 == nil {
                    onlineLine("Verificando o roteador…", ok: nil)
                } else {
                    if let address = online.ipv6.first {
                        onlineLine("IPv6 · \(address)", ok: true)
                        if online.ipv6Opened != true {
                            Text("Se o tablet não conectar de fora, libere no roteador a entrada IPv6 das portas TCP e UDP \(Proto.port) para este Mac (firewall IPv6).")
                                .font(Dot.text(11.5)).foregroundStyle(Dot.inkSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    } else {
                        onlineLine("IPv6 · sem endereço global (ative o IPv6 no roteador)", ok: false)
                    }
                    if online.cgnat {
                        onlineLine("IPv4 · compartilhado pela operadora (CGNAT): só o IPv6 funciona", ok: false)
                    } else if online.doubleNat {
                        onlineLine("IPv4 · atrás de dois roteadores: ponha o da operadora em modo bridge, ou use o IPv6", ok: false)
                    } else if let ip = online.publicIPv4, let port = online.tcpPort {
                        onlineLine("IPv4 · \(ip):\(port) · porta aberta (\(online.method ?? "roteador"))", ok: true)
                    } else if let ip = online.publicIPv4 {
                        onlineLine("IPv4 · \(ip) · o roteador não abriu a porta: ative o UPnP ou encaminhe TCP e UDP \(Proto.port)", ok: false)
                    } else {
                        onlineLine("IPv4 · o roteador não respondeu (UPnP e NAT-PMP desligados?)", ok: false)
                    }
                }
                if let date = online.lastRemote {
                    onlineLine("Última conexão de fora: \(online.lastRemoteName ?? "tablet"), \(date.formatted(date: .omitted, time: .shortened))", ok: true)
                }
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("Só tablets já pareados entram pela internet, criptografados de ponta a ponta. Conecte uma vez em casa: o tablet aprende o endereço sozinho.")
                        .font(Dot.text(11.5)).foregroundStyle(Dot.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 4)
                    Button("Verificar") { model.refreshOnlineAccess() }.buttonStyle(DotButtonStyle())
                }
            }
        }
    }

    private func onlineLine(_ text: String, ok: Bool?) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            DotMarker(color: ok == false ? Dot.signal : (ok == true ? Dot.ink : Dot.inkSecondary))
            Text(text).font(Dot.mono(10.5))
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
    }

    /// ★ / ☆: favorites connect by themselves, without a code.
    private func starButton(favorite: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(favorite ? "★" : "☆")
                .font(.system(size: 17))
                .foregroundStyle(favorite ? Dot.signal : Dot.inkSecondary)
                .frame(width: 28, height: 24)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help(favorite ? "Favorito: conecta sozinho. Clique para desmarcar." : "Marcar como favorito: conecta sozinho, sem código")
        .accessibilityLabel(favorite ? "Desmarcar favorito" : "Marcar como favorito")
    }

    private var favoriteCount: Int { model.pairedTablets.filter(\.favorite).count }

    /// Tablets allowed to connect without a code; removing one makes it pair again.
    /// With a favorite (★) marked, only favorites connect by themselves.
    private var paired: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                showPaired.toggle()
            } label: {
                HStack {
                    DotLabel(text: "Tablets autorizados · \(model.pairedTablets.count)"
                             + (favoriteCount > 0 ? " · ★ \(favoriteCount)" : ""))
                    Spacer()
                    Text(showPaired ? "ocultar" : "mostrar").font(Dot.mono(10.5)).foregroundStyle(Dot.inkSecondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if showPaired {
                if model.pairedTablets.isEmpty {
                    Text("Nenhum ainda. Na primeira conexão, o Mac e o tablet mostram o mesmo código para você confirmar.")
                        .font(Dot.text(12)).foregroundStyle(Dot.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if !model.pairedTablets.isEmpty {
                    Text(favoriteCount == 0
                         ? "Marque ★ no tablet que deve conectar sozinho, sem código nem aprovação."
                         : (favoritesOnly
                            ? "Modo favorito: os tablets com ★ conectam sozinhos; os outros pedem aprovação no Mac."
                            : "Todos os autorizados conectam sozinhos; ★ só marca os preferidos."))
                        .font(Dot.text(12)).foregroundStyle(Dot.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if favoriteCount > 0 {
                        toggleRow("Só favoritos conectam sozinhos", isOn: $favoritesOnly)
                    }
                }
                ForEach(model.pairedTablets) { tablet in
                    HStack(alignment: .center, spacing: 10) {
                        starButton(favorite: tablet.favorite) { model.setFavorite(tablet.id, !tablet.favorite) }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(tablet.name).font(Dot.text(13, .medium))
                            Text("pareado em \(tablet.pairedAt.formatted(date: .abbreviated, time: .shortened))")
                                .font(Dot.mono(10.5)).foregroundStyle(Dot.inkSecondary)
                        }
                        Spacer()
                        Button("Remover") { model.revoke(tablet) }.buttonStyle(DotButtonStyle())
                    }
                    .padding(.vertical, 6)
                    .overlay(alignment: .bottom) { Rectangle().fill(Dot.line).frame(height: 1) }
                }
            }
        }
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            DotLabel(text: title)
            content()
        }
    }

    private func toggleRow(_ title: String, isOn: Binding<Bool>) -> some View {
        Toggle(title, isOn: isOn)
            .toggleStyle(DotToggleStyle())
            .padding(.vertical, 9)
            .overlay(alignment: .bottom) { Rectangle().fill(Dot.line).frame(height: 1) }
    }

    private var usbHint: String {
        if !model.adbAvailable {
            return "adb não encontrado. Para usar USB: brew install android-platform-tools"
        }
        if model.usbDevices > 0 {
            return "USB pronto (\(model.usbDevices) dispositivo\(model.usbDevices > 1 ? "s" : "")). Abra o app no tablet."
        }
        return "Conecte o tablet por USB (depuração USB ativada) ou use o Wi‑Fi."
    }

    private var permissions: some View {
        VStack(alignment: .leading, spacing: 10) {
            DotLabel(text: "Permissões")
            if !model.hasScreenPermission {
                permissionRow("Gravação de Tela", "Para capturar o display virtual.") {
                    open("x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture")
                }
            }
            if !model.hasInputPermission {
                permissionRow("Acessibilidade", "Para o toque do tablet controlar o Mac.") {
                    InputInjector.requestPermission()
                }
            }
        }
    }

    private func permissionRow(_ title: String, _ detail: String, action: @escaping () -> Void) -> some View {
        HStack(alignment: .center, spacing: 10) {
            DotMarker(color: Dot.signal)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Dot.text(13, .medium))
                Text(detail).font(Dot.text(11.5)).foregroundStyle(Dot.inkSecondary)
            }
            Spacer()
            Button("Permitir", action: action).buttonStyle(DotButtonStyle(primary: true))
        }
    }

    private var footer: some View {
        HStack(alignment: .center) {
            let addresses = model.ipAddresses
            VStack(alignment: .leading, spacing: 2) {
                Text(addresses.isEmpty ? "sem rede" : addresses.joined(separator: " · "))
                    .textSelection(.enabled)
                Text("v\(Proto.appVersion)" + (TabletApk.bundled.map { " · tablet v\($0.versionName)" } ?? ""))
            }
            .font(Dot.mono(10.5))
            .foregroundStyle(Dot.inkSecondary)
            Spacer()
            Button("Sair") { NSApp.terminate(nil) }.buttonStyle(DotButtonStyle())
        }
        .padding(.top, 2)
    }

    private func open(_ string: String) {
        if let url = URL(string: string) { NSWorkspace.shared.open(url) }
    }
}

/// Height of the menu's scrolling middle, measured so the window hugs short content.
private struct ContentHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
