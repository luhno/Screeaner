import AppKit

/// Watches the Mac's clipboard for text and applies text coming from tablets,
/// without echoing a tablet's own text back to it. Main queue only.
final class ClipboardSync {
    private var timer: Timer?
    private var lastChangeCount = NSPasteboard.general.changeCount
    private var lastText: String?

    /// New text copied on the Mac.
    var onCopy: ((String) -> Void)?

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in self?.poll() }
    }

    private func poll() {
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount != lastChangeCount else { return }
        lastChangeCount = pasteboard.changeCount
        guard let text = pasteboard.string(forType: .string), !text.isEmpty, text != lastText else { return }
        lastText = text
        onCopy?(text)
    }

    /// Text copied on a tablet.
    func apply(_ text: String) {
        guard text != lastText else { return }
        lastText = text
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        lastChangeCount = pasteboard.changeCount
    }
}
