import ApplicationServices
import CoreGraphics
import Foundation

/// Turns tablet touches into mouse events on the virtual display.
/// Requires the Accessibility permission.
final class InputInjector {
    private let displayID: CGDirectDisplayID
    private let source = CGEventSource(stateID: .hidSystemState)
    private var lastDownTime: TimeInterval = 0
    private var lastDownPoint = CGPoint.zero
    private var clickCount: Int64 = 1

    init(displayID: CGDirectDisplayID) {
        self.displayID = displayID
    }

    static var hasPermission: Bool { AXIsProcessTrusted() }

    static func requestPermission() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
    }

    private func point(_ x: Float, _ y: Float) -> CGPoint {
        let bounds = CGDisplayBounds(displayID)
        let cx = CGFloat(min(max(x, 0), 1))
        let cy = CGFloat(min(max(y, 0), 1))
        return CGPoint(
            x: bounds.origin.x + cx * (bounds.width - 1),
            y: bounds.origin.y + cy * (bounds.height - 1)
        )
    }

    /// action: 0 down, 1 move (dragging), 2 up, 3 hover. button: 0 left, 1 right.
    func touch(action: UInt8, x: Float, y: Float, pressure: Float, button: UInt8) {
        let location = point(x, y)
        let right = button == 1
        let type: CGEventType
        switch action {
        case 0: type = right ? .rightMouseDown : .leftMouseDown
        case 1: type = right ? .rightMouseDragged : .leftMouseDragged
        case 2: type = right ? .rightMouseUp : .leftMouseUp
        default: type = .mouseMoved
        }

        if action == 0 {
            // Consecutive taps close in time and space become double/triple clicks.
            let now = ProcessInfo.processInfo.systemUptime
            let distance = hypot(location.x - lastDownPoint.x, location.y - lastDownPoint.y)
            clickCount = (now - lastDownTime < 0.4 && distance < 12) ? min(clickCount + 1, 3) : 1
            lastDownTime = now
            lastDownPoint = location
        }

        guard let event = CGEvent(
            mouseEventSource: source,
            mouseType: type,
            mouseCursorPosition: location,
            mouseButton: right ? .right : .left
        ) else { return }
        if action != 3 {
            event.setIntegerValueField(.mouseEventClickState, value: clickCount)
            event.setDoubleValueField(.mouseEventPressure, value: Double(action == 2 ? 0 : max(pressure, 0.01)))
        }
        event.post(tap: .cghidEventTap)
    }

    /// dx/dy are fractions of the display size; content follows the fingers.
    func scroll(dx: Float, dy: Float, x: Float, y: Float) {
        let bounds = CGDisplayBounds(displayID)
        let location = point(x, y)
        // Scroll events go to whatever is under the cursor.
        CGEvent(mouseEventSource: source, mouseType: .mouseMoved, mouseCursorPosition: location, mouseButton: .left)?
            .post(tap: .cghidEventTap)
        let vertical = Int32((CGFloat(dy) * bounds.height).rounded())
        let horizontal = Int32((CGFloat(dx) * bounds.width).rounded())
        guard vertical != 0 || horizontal != 0 else { return }
        CGEvent(
            scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2,
            wheel1: vertical, wheel2: horizontal, wheel3: 0
        )?.post(tap: .cghidEventTap)
    }
}
