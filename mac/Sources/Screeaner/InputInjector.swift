import AppKit
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
    /// Buttons held through relative (captured trackpad / mouse) input: 0 left, 1 right, 2 middle.
    private var heldButtons = Set<UInt8>()
    /// Fractions of a line left over from high-resolution wheels.
    private var wheelRemainderX: Float = 0
    private var wheelRemainderY: Float = 0
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
        let type: CGEventType
        switch action {
        case 0: type = Self.eventType(down: true, button: button)
        case 1: type = Self.dragType(button: button)
        case 2: type = Self.eventType(down: false, button: button)
        default: type = .mouseMoved
        }

        if action == 0 { countClick(at: location) }

        guard let event = CGEvent(
            mouseEventSource: source,
            mouseType: type,
            mouseCursorPosition: location,
            mouseButton: Self.cgButton(button)
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

    private static func cgButton(_ button: UInt8) -> CGMouseButton {
        switch button {
        case 1: return .right
        case 2: return .center
        default: return .left
        }
    }

    private static func eventType(down: Bool, button: UInt8) -> CGEventType {
        switch button {
        case 1: return down ? .rightMouseDown : .rightMouseUp
        case 2: return down ? .otherMouseDown : .otherMouseUp
        default: return down ? .leftMouseDown : .leftMouseUp
        }
    }

    private static func dragType(button: UInt8) -> CGEventType {
        switch button {
        case 1: return .rightMouseDragged
        case 2: return .otherMouseDragged
        default: return .leftMouseDragged
        }
    }

    /// Consecutive clicks close in time and space become double/triple clicks.
    private func countClick(at location: CGPoint) {
        let now = ProcessInfo.processInfo.systemUptime
        let distance = hypot(location.x - lastDownPoint.x, location.y - lastDownPoint.y)
        let interval = max(0.3, min(NSEvent.doubleClickInterval, 0.8))
        clickCount = (now - lastDownTime < interval && distance < 12) ? min(clickCount + 1, 3) : 1
        lastDownTime = now
        lastDownPoint = location
    }

    // MARK: - Relative pointer (a mouse or trackpad on the tablet driving the whole Mac)

    private var cursorLocation: CGPoint {
        CGEvent(source: nil)?.location ?? .zero
    }

    /// `x`/`y` normalized on the tablet, or NaN for "where the cursor is".
    private func location(_ x: Float, _ y: Float) -> CGPoint {
        x.isNaN || y.isNaN ? cursorLocation : point(x, y)
    }

    /// Keeps the cursor on some screen: across the gap between two screens it stops at the edge.
    private static func clampToScreens(_ target: CGPoint, from current: CGPoint) -> CGPoint {
        var display = CGDirectDisplayID()
        var count: UInt32 = 0
        if CGGetDisplaysWithPoint(target, 1, &display, &count) == .success, count > 0 { return target }
        if CGGetDisplaysWithPoint(current, 1, &display, &count) != .success || count == 0 {
            display = CGMainDisplayID()
        }
        let bounds = CGDisplayBounds(display)
        return CGPoint(
            x: min(max(target.x, bounds.minX), bounds.maxX - 1),
            y: min(max(target.y, bounds.minY), bounds.maxY - 1)
        )
    }

    /// dx/dy in points. Moves across every Mac screen, dragging while a button is held.
    func moveRelative(dx: Float, dy: Float) {
        guard dx.isFinite, dy.isFinite else { return }
        let current = cursorLocation
        let target = Self.clampToScreens(
            CGPoint(x: current.x + CGFloat(dx), y: current.y + CGFloat(dy)), from: current
        )
        let button = heldButtons.min()
        let type = button.map { Self.dragType(button: $0) } ?? .mouseMoved
        guard let event = CGEvent(
            mouseEventSource: source, mouseType: type, mouseCursorPosition: target,
            mouseButton: Self.cgButton(button ?? 0)
        ) else { return }
        // Games and 3D apps read the raw deltas.
        event.setIntegerValueField(.mouseEventDeltaX, value: Int64(dx.rounded()))
        event.setIntegerValueField(.mouseEventDeltaY, value: Int64(dy.rounded()))
        if button != nil { event.setIntegerValueField(.mouseEventClickState, value: clickCount) }
        if !modifiers.isEmpty { event.flags.formUnion(modifiers) }
        event.post(tap: .cghidEventTap)
    }

    /// A mouse button pressed or released, at the tablet position (x, y) or at the cursor (NaN).
    func button(down: Bool, button: UInt8, x: Float, y: Float) {
        let location = location(x, y)
        if down {
            guard heldButtons.insert(button).inserted else { return }
            countClick(at: location)
        } else {
            guard heldButtons.remove(button) != nil else { return }
        }
        guard let event = CGEvent(
            mouseEventSource: source, mouseType: Self.eventType(down: down, button: button),
            mouseCursorPosition: location, mouseButton: Self.cgButton(button)
        ) else { return }
        event.setIntegerValueField(.mouseEventClickState, value: clickCount)
        if !modifiers.isEmpty { event.flags.formUnion(modifiers) }
        event.post(tap: .cghidEventTap)
    }

    /// A mouse wheel, in lines (fractions add up), at (x, y) or at the cursor.
    /// Positive dy scrolls up, as on Android and macOS.
    func wheel(dx: Float, dy: Float, x: Float, y: Float) {
        guard dx.isFinite, dy.isFinite else { return }
        if !x.isNaN { moveCursor(to: point(x, y)) }
        wheelRemainderX += dx
        wheelRemainderY += dy
        let vertical = Int32(wheelRemainderY.rounded(.towardZero))
        let horizontal = Int32(wheelRemainderX.rounded(.towardZero))
        wheelRemainderY -= Float(vertical)
        wheelRemainderX -= Float(horizontal)
        guard vertical != 0 || horizontal != 0 else { return }
        guard let event = CGEvent(
            scrollWheelEvent2Source: source, units: .line, wheelCount: 2,
            wheel1: vertical, wheel2: horizontal, wheel3: 0
        ) else { return }
        if !modifiers.isEmpty { event.flags.formUnion(modifiers) }
        event.post(tap: .cghidEventTap)
    }

    /// Trackpad scrolling in points (content follows the fingers), at (x, y) or at the cursor.
    func scrollPoints(dx: Float, dy: Float, x: Float, y: Float) {
        guard dx.isFinite, dy.isFinite else { return }
        if !x.isNaN { moveCursor(to: point(x, y)) }
        let vertical = Int32(dy.rounded())
        let horizontal = Int32(dx.rounded())
        guard vertical != 0 || horizontal != 0 else { return }
        guard let event = CGEvent(
            scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2,
            wheel1: vertical, wheel2: horizontal, wheel3: 0
        ) else { return }
        // Continuous (trackpad-like) scrolling: smooth in every app.
        event.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
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
        if phase == 0 && !x.isNaN { moveCursor(to: point(x, y)) }
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

    /// Text typed with the tablet's own keyboard layout (accents and AltGr already
    /// resolved there): the characters win over the Mac's layout. `androidKeyCode`
    /// still gives the event a sensible key code for apps that look at it.
    func text(action: UInt8, androidKeyCode: Int, scanCode: Int, text: String, isRepeat: Bool) {
        let utf16 = Array(text.utf16)
        guard !utf16.isEmpty, utf16.count <= 32 else { return }
        let keyCode = KeyMap.macKey(androidKeyCode: androidKeyCode, scanCode: scanCode) ?? 0
        guard let event = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: action == 0) else { return }
        event.keyboardSetUnicodeString(stringLength: utf16.count, unicodeString: utf16)
        // The characters are final: no Option/Control/Command, which would turn them into shortcuts.
        event.flags = modifiers.intersection(.maskShift)
        if isRepeat { event.setIntegerValueField(.keyboardEventAutorepeat, value: 1) }
        event.post(tap: .cghidEventTap)
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
        for held in heldButtons.sorted() { button(down: false, button: held, x: .nan, y: .nan) }
        let held = heldModifierKeys
        heldModifierKeys.removeAll()
        modifiers = []
        for keyCode in held {
            post(keyCode: keyCode, down: false, isModifier: true, isRepeat: false)
        }
    }
}
