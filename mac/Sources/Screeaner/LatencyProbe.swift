import AppKit
import QuartzCore

/// The square behind the tablet's touch-to-screen latency test: a small
/// borderless window in the middle of one virtual display, above everything,
/// that turns black or white when the tablet asks. The tablet times how long
/// the request takes to come back as pixels in the video, so the square goes
/// through exactly the same path as any app on that display.
///
/// For the benchmark it can also cover the display with a moving pattern, so
/// every run streams the same, reproducible amount of motion.
/// Main thread only.
final class LatencyProbe {
    enum State: UInt8 {
        case hidden = 0
        case black = 1
        case white = 2
        case motionOn = 3
        case motionOff = 4
    }

    /// Big enough that the tablet's sampling patch (96 px around the centre)
    /// always falls inside, at 1x or Retina.
    static let sidePoints: CGFloat = 160

    private let displayID: CGDirectDisplayID
    private var window: NSWindow?
    private var motionWindow: NSWindow?

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
            setMotion(false)
        case .motionOn:
            setMotion(true)
        case .motionOff:
            setMotion(false)
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

    private var screen: NSScreen? {
        let key = NSDeviceDescriptionKey("NSScreenNumber")
        return NSScreen.screens.first { ($0.deviceDescription[key] as? NSNumber)?.uint32Value == displayID }
    }

    private func makeWindow() -> NSWindow? {
        guard let screen else { return nil }
        let side = Self.sidePoints
        let frame = NSRect(x: screen.frame.midX - side / 2, y: screen.frame.midY - side / 2, width: side, height: side)
        let window = Self.overlayWindow(frame: frame, level: .screenSaver)
        let view = NSView(frame: NSRect(origin: .zero, size: frame.size))
        view.wantsLayer = true
        view.layer?.backgroundColor = CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 1)
        window.contentView = view
        window.setFrame(frame, display: false)
        self.window = window
        return window
    }

    // MARK: - Benchmark motion

    /// Seconds for the pattern to slide one display width: a brisk scroll.
    private static let slideSeconds: CFTimeInterval = 3
    /// Size of each coloured block, in points.
    private static let blockPoints: CGFloat = 24

    /// A strip of random coloured blocks twice the display width, sliding left
    /// forever. Its second half repeats the first, so the loop is seamless.
    /// It sits below the test square, so both can run at once.
    private func setMotion(_ on: Bool) {
        guard on else {
            motionWindow?.orderOut(nil)
            motionWindow = nil
            return
        }
        guard motionWindow == nil, let screen, let image = Self.blocks(for: screen.frame.size) else { return }
        let frame = screen.frame
        let window = Self.overlayWindow(frame: frame, level: .floating)
        let view = NSView(frame: NSRect(origin: .zero, size: frame.size))
        view.wantsLayer = true
        let strip = CALayer()
        strip.contents = image
        strip.magnificationFilter = .nearest
        strip.contentsGravity = .resize
        strip.frame = CGRect(x: 0, y: 0, width: frame.width * 2, height: frame.height)
        view.layer?.addSublayer(strip)
        let slide = CABasicAnimation(keyPath: "position.x")
        slide.fromValue = frame.width
        slide.toValue = 0
        slide.duration = Self.slideSeconds
        slide.repeatCount = .infinity
        strip.add(slide, forKey: "slide")
        window.contentView = view
        window.setFrame(frame, display: false)
        window.orderFrontRegardless()
        motionWindow = window
    }

    /// One pixel per block; the layer scales it up without smoothing.
    private static func blocks(for size: CGSize) -> CGImage? {
        let columns = max(1, Int((size.width / blockPoints).rounded(.up)))
        let rows = max(1, Int((size.height / blockPoints).rounded(.up)))
        guard let context = CGContext(
            data: nil, width: columns * 2, height: rows, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        // Fixed seed: every benchmark streams exactly the same picture.
        var seed: UInt64 = 0x5343_524E
        func next() -> CGFloat {
            seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
            return CGFloat(seed >> 56) / 255
        }
        for row in 0..<rows {
            for column in 0..<columns {
                context.setFillColor(CGColor(srgbRed: next(), green: next(), blue: next(), alpha: 1))
                context.fill(CGRect(x: column, y: row, width: 1, height: 1))
                context.fill(CGRect(x: column + columns, y: row, width: 1, height: 1))
            }
        }
        return context.makeImage()
    }

    private static func overlayWindow(frame: NSRect, level: NSWindow.Level) -> NSWindow {
        let window = NSWindow(contentRect: frame, styleMask: .borderless, backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.level = level
        window.ignoresMouseEvents = true
        window.hasShadow = false
        window.isOpaque = true
        window.backgroundColor = .black
        window.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle, .fullScreenAuxiliary]
        return window
    }
}
