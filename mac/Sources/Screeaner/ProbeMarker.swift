import AppKit

/// A small square drawn at the top-left corner of a display for the
/// end-to-end latency test: the tablet sends a probe, the square flips color,
/// and the tablet times how long it takes to see the flip on its screen.
/// Main queue only.
final class ProbeMarker {
    private var window: NSWindow?
    private var hideWork: DispatchWorkItem?
    static let size: CGFloat = 56

    /// Shows the marker (black) on `display`. Returns its rect normalized to the display, or nil.
    func show(on display: CGDirectDisplayID) -> CGRect? {
        guard let screen = NSScreen.screens.first(where: { $0.displayID == display }) else { return nil }
        let frame = screen.frame
        let rect = NSRect(x: frame.minX, y: frame.maxY - Self.size, width: Self.size, height: Self.size)
        let window = self.window ?? NSWindow(contentRect: rect, styleMask: .borderless, backing: .buffered, defer: false)
        window.setFrame(rect, display: true)
        window.level = .screenSaver
        window.ignoresMouseEvents = true
        window.isReleasedWhenClosed = false
        window.hasShadow = false
        window.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]
        window.backgroundColor = .black
        window.orderFrontRegardless()
        self.window = window
        scheduleHide()
        return CGRect(x: 0, y: 0, width: Self.size / frame.width, height: Self.size / frame.height)
    }

    func set(lit: Bool) {
        window?.backgroundColor = lit ? .white : .black
        window?.displayIfNeeded()
        scheduleHide()
    }

    func hide() {
        hideWork?.cancel()
        window?.orderOut(nil)
    }

    /// Removes the marker shortly after the last probe.
    private func scheduleHide() {
        hideWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.window?.orderOut(nil) }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }
}
