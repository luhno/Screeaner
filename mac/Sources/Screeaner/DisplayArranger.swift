import CoreGraphics

/// How the tablet is used, chosen on the tablet and sent in its hello.
enum DisplayMode: String {
    /// A new desktop area next to the Mac's screen.
    case extend
    /// Same as extend, but the tablet becomes the main display (menu bar and Dock).
    case main
    /// The tablet shows the Mac's own screen; no virtual display is created.
    case mirror
}

/// Where an extended tablet sits relative to the Mac's screen.
enum DisplayPosition: String {
    /// Keep whatever macOS remembers for this tablet.
    case auto
    case left, right, above, below
}

extension DisplayMode {
    func label(_ position: DisplayPosition) -> String {
        switch self {
        case .mirror: return "Espelhando a tela do Mac"
        case .main: return "Monitor principal"
        case .extend:
            switch position {
            case .auto: return "Extensão"
            case .left: return "Extensão à esquerda"
            case .right: return "Extensão à direita"
            case .above: return "Extensão acima"
            case .below: return "Extensão abaixo"
            }
        }
    }
}

/// Moves displays around with Quartz Display Services. Changes use
/// `.forAppOnly`, so macOS restores its own arrangement if the app quits.
enum DisplayArranger {
    static let unitArea = CGRect(x: 0, y: 0, width: 1, height: 1)

    /// Displays that can be positioned (hardware mirrors follow their source).
    static func activeDisplays() -> [CGDirectDisplayID] {
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &count) == .success, count > 0 else { return [] }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        guard CGGetActiveDisplayList(count, &ids, &count) == .success else { return [] }
        return ids.prefix(Int(count)).filter { CGDisplayMirrorsDisplay($0) == kCGNullDirectDisplay }
    }

    static func isTablet(_ display: CGDirectDisplayID) -> Bool {
        CGDisplayVendorNumber(display) == VirtualDisplay.vendorID
    }

    /// The Mac's own screen: the main display, unless a tablet has taken that role.
    static func macScreen() -> CGDirectDisplayID {
        let main = CGMainDisplayID()
        if !isTablet(main) { return main }
        return activeDisplays().first { !isTablet($0) } ?? main
    }

    /// Where `source` lands inside a width×height frame when scaled to fit
    /// (ScreenCaptureKit letterboxes it centered), in normalized coordinates.
    static func fittedArea(of source: CGDirectDisplayID, width: Int, height: Int) -> CGRect {
        let bounds = CGDisplayBounds(source)
        guard bounds.width > 0, bounds.height > 0, width > 0, height > 0 else { return unitArea }
        let scale = min(CGFloat(width) / bounds.width, CGFloat(height) / bounds.height)
        let w = bounds.width * scale / CGFloat(width)
        let h = bounds.height * scale / CGFloat(height)
        return CGRect(x: (1 - w) / 2, y: (1 - h) / 2, width: w, height: h)
    }

    /// Places the tablet next to the Mac's screen and/or makes it the main
    /// display. Returns false if macOS refused the new arrangement.
    @discardableResult
    static func arrange(_ display: CGDirectDisplayID, position: DisplayPosition, makeMain: Bool) -> Bool {
        guard position != .auto || makeMain else { return true }
        var origins: [CGDirectDisplayID: CGPoint] = [:]
        for id in activeDisplays() { origins[id] = CGDisplayBounds(id).origin }
        guard origins[display] != nil else { return false }

        let anchorID = macScreen()
        if position != .auto, anchorID != display, let anchorOrigin = origins[anchorID] {
            let anchor = CGRect(origin: anchorOrigin, size: CGDisplayBounds(anchorID).size)
            let size = CGDisplayBounds(display).size
            let origin: CGPoint
            switch position {
            case .right: origin = CGPoint(x: anchor.maxX, y: anchor.minY)
            case .left: origin = CGPoint(x: anchor.minX - size.width, y: anchor.minY)
            case .above: origin = CGPoint(x: anchor.midX - size.width / 2, y: anchor.minY - size.height)
            case .below: origin = CGPoint(x: anchor.midX - size.width / 2, y: anchor.maxY)
            case .auto: origin = anchorOrigin
            }
            origins[display] = CGPoint(x: origin.x.rounded(), y: origin.y.rounded())
        }

        if makeMain, let target = origins[display] {
            // The display at (0,0) is the main one. Shifting every display by the
            // same amount moves the tablet there while keeping the arrangement.
            for (id, point) in origins {
                origins[id] = CGPoint(x: point.x - target.x, y: point.y - target.y)
            }
        }

        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success, let config else { return false }
        for (id, point) in origins {
            CGConfigureDisplayOrigin(config, id, Int32(point.x), Int32(point.y))
        }
        return CGCompleteDisplayConfiguration(config, .forAppOnly) == .success
    }
}
