import AppKit
import SwiftUI

/// Floating window asking the user to allow a tablet, with the code to compare.
/// Main queue only.
final class PairingPanel {
    private var panel: NSPanel?

    func show(model: AppModel) {
        if panel == nil {
            let panel = NSPanel(
                contentRect: NSRect(x: 0, y: 0, width: 380, height: 300),
                styleMask: [.titled, .nonactivatingPanel, .utilityWindow],
                backing: .buffered, defer: false
            )
            panel.title = "Parear tablet"
            panel.level = .floating
            panel.isReleasedWhenClosed = false
            panel.hidesOnDeactivate = false
            panel.appearance = NSAppearance(named: .darkAqua)
            panel.contentView = NSHostingView(rootView: PairingView(model: model))
            panel.center()
            self.panel = panel
        }
        // Shown on top without taking the keyboard: a Return typed in another
        // app must never answer a pairing request.
        panel?.orderFrontRegardless()
    }

    func hide() {
        panel?.orderOut(nil)
    }
}

struct PairingView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            ForEach(model.pairingRequests) { request in
                PairingRequestView(request: request) { decision in
                    model.respond(to: request, decision: decision)
                }
            }
        }
        .padding(20)
        .frame(width: 380)
        .background(Dot.canvas)
        .foregroundStyle(Dot.ink)
        .environment(\.colorScheme, .dark)
    }
}

/// "Tablet X wants to use this Mac", the six-digit code, a favorite switch and Allow / Refuse.
struct PairingRequestView: View {
    let request: PairingRequest
    let decide: (PairingDecision) -> Void
    @State private var favorite = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                DotMarker(color: Dot.signal)
                DotLabel(text: request.known ? "Conexão" : "Pareamento")
            }
            Text(request.known
                 ? "\(request.name) quer conectar"
                 : "\(request.name) quer usar este Mac como monitor")
                .font(Dot.text(14, .semibold))
                .fixedSize(horizontal: false, vertical: true)
            if request.known {
                Text("Ele já é autorizado, mas só os tablets com ★ conectam sozinhos.")
                    .font(Dot.text(12)).foregroundStyle(Dot.inkSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text("Confira se o tablet mostra o mesmo código:")
                .font(Dot.text(12)).foregroundStyle(Dot.inkSecondary)
            Text(PairingRequestView.format(request.code))
                .font(Dot.display(40))
                .tracking(2)
                .textSelection(.enabled)
            Text("Ao permitir, o tablet poderá ver a tela e controlar o mouse e o teclado deste Mac.")
                .font(Dot.text(11.5)).foregroundStyle(Dot.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Toggle("★ Favorito: conecta sozinho, sem código", isOn: $favorite)
                .toggleStyle(DotToggleStyle())
                .padding(.vertical, 4)
            HStack {
                Button("Recusar") { decide(.refuse) }.buttonStyle(DotButtonStyle())
                Spacer()
                // Deliberately no default (Return) shortcut: allowing must be a click.
                Button("Permitir") { decide(favorite ? .allowFavorite : .allow) }
                    .buttonStyle(DotButtonStyle(primary: true))
            }
        }
    }

    /// "953187" -> "953 187"
    static func format(_ code: String) -> String {
        guard code.count == 6 else { return code }
        return String(code.prefix(3)) + " " + String(code.suffix(3))
    }
}
