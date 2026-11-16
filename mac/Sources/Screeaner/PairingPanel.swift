import AppKit
import SwiftUI

/// Floating window asking the user to allow a tablet, with the code to compare.
/// Main queue only.
final class PairingPanel {
    private var panel: NSPanel?

    func show(model: AppModel) {
        if panel == nil {
            let panel = NSPanel(
                contentRect: NSRect(x: 0, y: 0, width: 360, height: 220),
                styleMask: [.titled, .nonactivatingPanel, .utilityWindow],
                backing: .buffered, defer: false
            )
            panel.title = "Parear tablet"
            panel.level = .floating
            panel.isReleasedWhenClosed = false
            panel.hidesOnDeactivate = false
            panel.contentView = NSHostingView(rootView: PairingView(model: model))
            panel.center()
            self.panel = panel
        }
        NSApp.activate(ignoringOtherApps: true)
        panel?.makeKeyAndOrderFront(nil)
    }

    func hide() {
        panel?.orderOut(nil)
    }
}

struct PairingView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ForEach(model.pairingRequests) { request in
                PairingRequestView(request: request) { allowed in
                    model.respond(to: request, allowed: allowed)
                }
            }
        }
        .padding(18)
        .frame(width: 360)
    }
}

struct PairingRequestView: View {
    let request: PairingRequest
    let decide: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(request.name) quer usar este Mac como monitor")
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)
            Text("Confira se o tablet mostra o mesmo código:")
                .font(.callout).foregroundStyle(.secondary)
            Text(PairingRequestView.format(request.code))
                .font(.system(size: 34, weight: .semibold, design: .monospaced))
                .textSelection(.enabled)
            Text("Ao permitir, o tablet poderá ver a tela e controlar o mouse e o teclado deste Mac.")
                .font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Button("Recusar", role: .cancel) { decide(false) }
                Spacer()
                Button("Permitir") { decide(true) }
                    .keyboardShortcut(.defaultAction)
            }
        }
    }

    /// "953187" -> "953 187"
    static func format(_ code: String) -> String {
        guard code.count == 6 else { return code }
        return String(code.prefix(3)) + " " + String(code.suffix(3))
    }
}
