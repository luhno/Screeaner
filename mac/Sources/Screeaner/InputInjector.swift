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
    private var penInProximity = false
    private var penTool: UInt8 = 1
    /// Identifies our virtual pen to apps that track tablet devices.
    private static let tabletDeviceID: Int64 = 0x5343

    /// Part of the tablet screen (normalized) that shows the display; smaller
    /// than the whole screen when a mirrored Mac screen is letterboxed.
    private let area: CGRect

    init(displayID: CGDirectDisplayID, area: CGRect = DisplayArranger.unitArea) {
        self.displayID = displayID
        self.area = area.width > 0 && area.height > 0 ? area : DisplayArranger.unitArea
    }

    static var hasPermission: Bool { AXIsProcessTrusted() }

    static func requestPermission() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
    }

    private func point(_ x: Float, _ y: Float) -> CGPoint {
        let bounds = CGDisplayBounds(displayID)
        let cx = min(max((CGFloat(x) - area.minX) / area.width, 0), 1)
        let cy = min(max((CGFloat(y) - area.minY) / area.height, 0), 1)
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

    /// action: 0 down, 1 move (dragging), 2 up, 3 hover, 4 pen enters range, 5 pen leaves.
    /// button: 0 left, 1 right. tool: 0 finger or mouse, 1 pen, 2 eraser.
    /// Pen input carries pressure and tilt (-1...1) as tablet events, which
    /// drawing apps read like a graphics tablet.
    func touch(action: UInt8, x: Float, y: Float, pressure: Float, button: UInt8,
               tiltX: Float = 0, tiltY: Float = 0, tool: UInt8 = 0) {
        let location = point(x, y)
        let isPen = tool == 1 || tool == 2
        if action == 4 || action == 5 {
            proximity(entering: action == 4, tool: tool)
            if action == 4 { moveCursor(to: location) }
            return
        }
        if isPen && !penInProximity { proximity(entering: true, tool: tool) }
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
        if isPen {
            event.setIntegerValueField(.mouseEventSubtype, value: Int64(CGEventMouseSubtype.tabletPoint.rawValue))
            event.setDoubleValueField(.tabletEventPointPressure, value: Double(action == 2 || action == 3 ? 0 : pressure))
            event.setDoubleValueField(.tabletEventTiltX, value: Double(max(-1, min(1, tiltX))))
            event.setDoubleValueField(.tabletEventTiltY, value: Double(max(-1, min(1, tiltY))))
            event.setIntegerValueField(.tabletEventDeviceID, value: Self.tabletDeviceID)
            event.setIntegerValueField(.tabletEventPointX, value: Int64(location.x))
            event.setIntegerValueField(.tabletEventPointY, value: Int64(location.y))
        }
        // Shift-click, Cmd-click etc. with the tablet's keyboard.
        if !modifiers.isEmpty { event.flags.formUnion(modifiers) }
        event.post(tap: .cghidEventTap)
    }

    /// Tells apps a pen (or eraser) came into or left the tablet's range.
    private func proximity(entering: Bool, tool: UInt8) {
        guard entering != penInProximity || (entering && tool != penTool) else { return }
        penInProximity = entering
        penTool = tool
        guard let event = CGEvent(source: source) else { return }
        event.type = .tabletProximity
        event.setIntegerValueField(.tabletProximityEventVendorID, value: 0x5343)
        event.setIntegerValueField(.tabletProximityEventTabletID, value: 1)
        event.setIntegerValueField(.tabletProximityEventPointerID, value: 1)
        event.setIntegerValueField(.tabletProximityEventDeviceID, value: Self.tabletDeviceID)
        event.setIntegerValueField(.tabletProximityEventSystemTabletID, value: 1)
        event.setIntegerValueField(.tabletProximityEventVendorPointerType, value: tool == 2 ? 0x080A : 0x0802)
        event.setIntegerValueField(.tabletProximityEventVendorPointerSerialNumber, value: 1)
        // Capabilities: absolute x/y, buttons, pressure, tilt x/y.
        event.setIntegerValueField(.tabletProximityEventCapabilityMask, value: 0x0001 | 0x0002 | 0x0040 | 0x0400 | 0x0080 | 0x0100)
        event.setIntegerValueField(.tabletProximityEventPointerType, value: tool == 2 ? 3 : 1) // 1 pen, 3 eraser
        event.setIntegerValueField(.tabletProximityEventEnterProximity, value: entering ? 1 : 0)
        event.post(tap: .cghidEventTap)
    }

    /// dx/dy are fractions of the display size; content follows the fingers.
    func scroll(dx: Float, dy: Float, x: Float, y: Float) {
        let bounds = CGDisplayBounds(displayID)
        // Scroll events go to whatever is under the cursor.
        moveCursor(to: point(x, y))
        let vertical = Int32((CGFloat(dy) / area.height * bounds.height).rounded())
        let horizontal = Int32((CGFloat(dx) / area.width * bounds.width).rounded())
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
    func key(action: UInt8, androidKeyCode: Int, scanCode: Int = 0, isRepeat: Bool) {
        guard let keyCode = KeyMap.macKey(androidKeyCode: androidKeyCode, scanCode: scanCode) else { return }
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
        if penInProximity { proximity(entering: false, tool: penTool) }
        let held = heldModifierKeys
        heldModifierKeys.removeAll()
        modifiers = []
        for keyCode in held {
            post(keyCode: keyCode, down: false, isModifier: true, isRepeat: false)
        }
    }
}
