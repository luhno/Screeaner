import AppKit
import SwiftUI

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Menu-bar only, even when launched outside an .app bundle (`swift run`).
        NSApp.setActivationPolicy(.accessory)
        AppModel.shared.start()
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

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            if model.tablets.isEmpty {
                waiting
            } else {
                ForEach(model.tablets) { tablet in card(tablet) }
            }
            settings
            if !model.hasScreenPermission || !model.hasInputPermission {
                permissions
            }
            footer
        }
        .padding(18)
        .frame(width: 360)
        .background(Dot.canvas)
        .foregroundStyle(Dot.ink)
        .environment(\.colorScheme, .dark)
    }

    private var header: some View {
        HStack(alignment: .center) {
            Text("SCREEANER")
                .font(Dot.display(20))
                .tracking(0.8)
            Spacer()
            HStack(spacing: 6) {
                DotMarker(color: model.tablets.isEmpty ? Dot.inkSecondary : Dot.signal)
                Text(model.tablets.isEmpty ? "AGUARDANDO" : "AO VIVO")
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
            Text(tablet.name).font(Dot.text(14, .semibold))
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
                    Text("Encoder normal: cerca de 1 quadro a mais de atraso. Teste o H.264.")
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
            VStack(spacing: 0) {
                toggleRow("Bitrate adaptativo", isOn: $adaptiveBitrate)
                toggleRow("UDP com correção de erros no Wi‑Fi", isOn: $udpOverWifi)
                toggleRow("USB automático (adb)", isOn: $autoAdb)
                    .disabled(!model.adbAvailable)
                    .opacity(model.adbAvailable ? 1 : 0.45)
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
            let addresses = AppModel.localIPAddresses()
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
