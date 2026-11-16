import CoreGraphics

/// Android key codes (android.view.KeyEvent) -> macOS virtual key codes.
/// Both describe physical key positions, so the Mac's own keyboard layout
/// decides which character each key produces.
enum KeyMap {
    static let mac: [Int: CGKeyCode] = {
        var map: [Int: CGKeyCode] = [:]
        // KEYCODE_A (29) ... KEYCODE_Z (54)
        let letters: [CGKeyCode] = [
            0, 11, 8, 2, 14, 3, 5, 4, 34, 38, 40, 37, 46,
            45, 31, 35, 12, 15, 1, 17, 32, 9, 13, 7, 16, 6,
        ]
        for (offset, code) in letters.enumerated() { map[29 + offset] = code }
        // KEYCODE_0 (7) ... KEYCODE_9 (16)
        let digits: [CGKeyCode] = [29, 18, 19, 20, 21, 23, 22, 26, 28, 25]
        for (offset, code) in digits.enumerated() { map[7 + offset] = code }
        // KEYCODE_F1 (131) ... KEYCODE_F12 (142)
        let functionKeys: [CGKeyCode] = [122, 120, 99, 118, 96, 97, 98, 100, 101, 109, 103, 111]
        for (offset, code) in functionKeys.enumerated() { map[131 + offset] = code }

        let others: [Int: CGKeyCode] = [
            66: 36,   // ENTER
            160: 76,  // NUMPAD_ENTER
            67: 51,   // DEL (backspace)
            112: 117, // FORWARD_DEL
            61: 48,   // TAB
            62: 49,   // SPACE
            111: 53,  // ESCAPE
            19: 126, 20: 125, 21: 123, 22: 124, // DPAD up, down, left, right
            55: 43,   // COMMA
            56: 47,   // PERIOD
            69: 27,   // MINUS
            70: 24,   // EQUALS
            71: 33,   // LEFT_BRACKET
            72: 30,   // RIGHT_BRACKET
            73: 42,   // BACKSLASH
            74: 41,   // SEMICOLON
            75: 39,   // APOSTROPHE
            76: 44,   // SLASH
            68: 50,   // GRAVE
            92: 116,  // PAGE_UP
            93: 121,  // PAGE_DOWN
            122: 115, // MOVE_HOME
            123: 119, // MOVE_END
            115: 57,  // CAPS_LOCK
            59: 56, 60: 60,   // SHIFT left, right
            57: 58, 58: 61,   // ALT left, right -> Option
            113: 59, 114: 62, // CTRL left, right
            117: 55, 118: 54, // META left, right -> Command
        ]
        map.merge(others) { _, new in new }
        return map
    }()

    static func modifier(for key: CGKeyCode) -> CGEventFlags? {
        switch key {
        case 56, 60: return .maskShift
        case 58, 61: return .maskAlternate
        case 59, 62: return .maskControl
        case 55, 54: return .maskCommand
        default: return nil
        }
    }
}
