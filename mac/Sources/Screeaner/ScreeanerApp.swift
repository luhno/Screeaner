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
        VStack(alignment: .leading, spacing: 12) {
            header
            Divider()
            settings
            if !model.hasScreenPermission || !model.hasInputPermission {
                Divider()
                permissions
            }
            Divider()
            footer
        }
        .padding(14)
        .frame(width: 340)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Circle()
                    .fill(model.tablets.isEmpty ? Color.secondary : Color.green)
                    .frame(width: 9, height: 9)
                Text("Screeaner").font(.headline)
            }
            Text(model.status).font(.callout).fixedSize(horizontal: false, vertical: true)
            ForEach(model.tablets) { tablet in
                VStack(alignment: .leading, spacing: 2) {
                    Text(tablet.name).font(.callout.weight(.medium))
                    if !tablet.detail.isEmpty {
                        Text(tablet.detail).font(.caption).foregroundStyle(.secondary)
                    }
                    if !tablet.stats.isEmpty {
                        Text(tablet.stats).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.top, 2)
            }
        }
    }

    private var settings: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("Resolução", selection: $hiDPI) {
                Text("Retina (HiDPI)").tag(true)
                Text("Nativa 1x (mais espaço)").tag(false)
            }
            Picker("Taxa de quadros", selection: $fps) {
                ForEach([30, 60, 90, 120], id: \.self) { Text("\($0) fps").tag($0) }
            }
            Picker("Qualidade máxima", selection: $bitrate) {
                ForEach([10, 20, 40, 80], id: \.self) { Text("\($0) Mbps").tag($0) }
            }
            Picker("Codec", selection: $codec) {
                Text("H.264 (menor latência)").tag(Codec.h264.rawValue)
                Text("HEVC (mais nítido)").tag(Codec.hevc.rawValue)
            }
            Toggle("Bitrate adaptativo", isOn: $adaptiveBitrate)
            Toggle("UDP com correção de erros no Wi‑Fi", isOn: $udpOverWifi)
            Toggle("Configurar USB automaticamente (adb)", isOn: $autoAdb)
                .disabled(!model.adbAvailable)
            Text(usbHint).font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .onChange(of: hiDPI) { _ in model.restartSessions() }
        .onChange(of: fps) { _ in model.restartSessions() }
        .onChange(of: bitrate) { _ in model.restartSessions() }
        .onChange(of: codec) { _ in model.restartSessions() }
        .onChange(of: adaptiveBitrate) { _ in model.restartSessions() }
        .onChange(of: udpOverWifi) { _ in model.restartSessions() }
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
        VStack(alignment: .leading, spacing: 6) {
            if !model.hasScreenPermission {
                Button("Permitir Gravação de Tela…") {
                    open("x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture")
                }
                Text("Necessária para capturar o display virtual.").font(.caption).foregroundStyle(.secondary)
            }
            if !model.hasInputPermission {
                Button("Permitir Acessibilidade…") { InputInjector.requestPermission() }
                Text("Necessária para o toque do tablet controlar o Mac.").font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var footer: some View {
        HStack {
            let addresses = AppModel.localIPAddresses()
            Text(addresses.isEmpty ? "Sem rede" : "IP: " + addresses.joined(separator: ", "))
                .font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
            Spacer()
            Text("v\(Proto.appVersion)").font(.caption).foregroundStyle(.secondary)
            Button("Sair") { NSApp.terminate(nil) }
        }
    }

    private func open(_ string: String) {
        if let url = URL(string: string) { NSWorkspace.shared.open(url) }
    }
}
