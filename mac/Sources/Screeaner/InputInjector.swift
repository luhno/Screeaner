import ApplicationServices
import CoreGraphics
import Foundation

/// Turns tablet input (touch, stylus, gestures, hardware keyboard) into
/// macOS events on the virtual display. Requires the Accessibility permission.
/// All methods are called from the session's connection queue.
final class InputInjector {
    private let displayID: CGDirectDisplayID
    private let source = CGEventSource(stateID: .hidSystemState)
    private var lastDownTime: TimeInterval = 0
    private var lastDownPoint = CGPoint.zero
    private var clickCount: Int64 = 1
    private var modifiers: CGEventFlags = []
    private var heldModifierKeys = Set<CGKeyCode>()

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

    private func moveCursor(to location: CGPoint) {
        CGEvent(mouseEventSource: source, mouseType: .mouseMoved, mouseCursorPosition: location, mouseButton: .left)?
            .post(tap: .cghidEventTap)
    }

    // MARK: - Pointer

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
        // Shift-click, Cmd-click etc. with the tablet's keyboard.
        if !modifiers.isEmpty { event.flags.formUnion(modifiers) }
        event.post(tap: .cghidEventTap)
    }

    /// dx/dy are fractions of the display size; content follows the fingers.
    func scroll(dx: Float, dy: Float, x: Float, y: Float) {
        let bounds = CGDisplayBounds(displayID)
        // Scroll events go to whatever is under the cursor.
        moveCursor(to: point(x, y))
        let vertical = Int32((CGFloat(dy) * bounds.height).rounded())
        let horizontal = Int32((CGFloat(dx) * bounds.width).rounded())
        guard vertical != 0 || horizontal != 0 else { return }
        guard let event = CGEvent(
            scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2,
            wheel1: vertical, wheel2: horizontal, wheel3: 0
        ) else { return }
        if !modifiers.isEmpty { event.flags.formUnion(modifiers) }
        event.post(tap: .cghidEventTap)
    }

    /// Pinch gesture. phase: 0 begin, 1 change, 2 end. delta is the relative
    /// change in finger distance since the previous event.
    ///
    /// macOS has no public API to synthesize a magnify gesture; this posts the
    /// same undocumented gesture event the trackpad driver produces.
    func zoom(phase: UInt8, delta: Float, x: Float, y: Float) {
        if phase == 0 { moveCursor(to: point(x, y)) }
        guard let event = CGEvent(source: nil),
              let gestureType = CGEventType(rawValue: 29),      // NSEventTypeGesture
              let subtypeField = CGEventField(rawValue: 110),   // gesture HID type
              let magnificationField = CGEventField(rawValue: 113),
              let phaseField = CGEventField(rawValue: 132)
        else { return }
        event.type = gestureType
        event.setIntegerValueField(subtypeField, value: 8)      // kIOHIDEventTypeZoom
        event.setDoubleValueField(magnificationField, value: phase == 1 ? Double(delta) : 0)
        event.setIntegerValueField(phaseField, value: phase == 0 ? 1 : (phase == 1 ? 2 : 4))
        event.post(tap: .cgSessionEventTap)
    }

    // MARK: - Keyboard

    /// action: 0 down, 1 up. Unknown keys are ignored.
    func key(action: UInt8, androidKeyCode: Int, isRepeat: Bool) {
        guard let keyCode = KeyMap.mac[androidKeyCode] else { return }
        let down = action == 0
        let modifier = KeyMap.modifier(for: keyCode)
        if let modifier {
            if down {
                heldModifierKeys.insert(keyCode)
                modifiers.insert(modifier)
            } else {
                heldModifierKeys.remove(keyCode)
                // Left and right variants share a flag: keep it while either is held.
                if !heldModifierKeys.contains(where: { KeyMap.modifier(for: $0) == modifier }) {
                    modifiers.remove(modifier)
                }
            }
        }
        post(keyCode: keyCode, down: down, isModifier: modifier != nil, isRepeat: isRepeat)
    }

    private func post(keyCode: CGKeyCode, down: Bool, isModifier: Bool, isRepeat: Bool) {
        guard let event = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: down) else { return }
        if isModifier {
            event.type = .flagsChanged
            event.flags = modifiers
        } else {
            if !modifiers.isEmpty { event.flags.formUnion(modifiers) }
            if isRepeat { event.setIntegerValueField(.keyboardEventAutorepeat, value: 1) }
        }
        event.post(tap: .cghidEventTap)
    }

    /// Releases anything still held, so a dropped connection never leaves Cmd or Shift stuck.
    func releaseAll() {
        let held = heldModifierKeys
        heldModifierKeys.removeAll()
        modifiers = []
        for keyCode in held {
            post(keyCode: keyCode, down: false, isModifier: true, isRepeat: false)
        }
    }
}
