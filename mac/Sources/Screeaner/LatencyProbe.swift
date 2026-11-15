import AppKit
import QuartzCore

/// The square behind the tablet's touch-to-screen latency test: a small
/// borderless window in the middle of one virtual display, above everything,
/// that turns black or white when the tablet asks. The tablet times how long
/// the request takes to come back as pixels in the video, so the square goes
/// through exactly the same path as any app on that display.
/// Main thread only.
final class LatencyProbe {
    enum State: UInt8 {
        case hidden = 0
        case black = 1
        case white = 2
    }

    /// Big enough that the tablet's sampling patch (96 px around the centre)
    /// always falls inside, at 1x or Retina.
    static let sidePoints: CGFloat = 160

    private let displayID: CGDirectDisplayID
    private var window: NSWindow?

    init(displayID: CGDirectDisplayID) {
        self.displayID = displayID
    }

    /// Applies the state and returns the Mac clock (µs, the clock frame
    /// timestamps use) right after the change reached the window server.
    func apply(_ state: State) -> UInt64 {
        switch state {
        case .hidden:
            window?.orderOut(nil)
            window = nil
        case .black, .white:
            guard let window = window ?? makeWindow() else { break }
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            window.contentView?.layer?.backgroundColor = state == .white
                ? CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1)
                : CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 1)
            CATransaction.commit()
            if !window.isVisible { window.orderFrontRegardless() }
            CATransaction.flush()
        }
        return Proto.hostClockMicros()
    }

    private func makeWindow() -> NSWindow? {
        let key = NSDeviceDescriptionKey("NSScreenNumber")
        guard let screen = NSScreen.screens.first(where: {
            ($0.deviceDescription[key] as? NSNumber)?.uint32Value == displayID
        }) else { return nil }

        let side = Self.sidePoints
        let frame = NSRect(x: screen.frame.midX - side / 2, y: screen.frame.midY - side / 2, width: side, height: side)
        let window = NSWindow(contentRect: frame, styleMask: .borderless, backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.level = .screenSaver
        window.ignoresMouseEvents = true
        window.hasShadow = false
        window.isOpaque = true
        window.backgroundColor = .black
        window.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle, .fullScreenAuxiliary]
        let view = NSView(frame: NSRect(origin: .zero, size: frame.size))
        view.wantsLayer = true
        view.layer?.backgroundColor = CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 1)
        window.contentView = view
        window.setFrame(frame, display: false)
        self.window = window
        return window
    }
}
