import CoreGraphics
import CVirtualDisplay
import Foundation

/// A virtual monitor sized to match the tablet panel. It lives exactly as long
/// as this object: releasing it unplugs the display.
final class VirtualDisplay {
    /// Marks our displays so they can be told apart from real monitors.
    static let vendorID: UInt32 = 0x5343
    private let display: CGVirtualDisplay
    var displayID: CGDirectDisplayID { display.displayID }

    /// Must be called on the main thread. `serial` identifies the tablet so
    /// macOS restores where the user placed this particular display.
    init?(name: String, serial: UInt32, pixelWidth: Int, pixelHeight: Int,
          hiDPI: Bool, refreshRate: Double, dpi: Int) {
        let descriptor = CGVirtualDisplayDescriptor()
        descriptor.setDispatchQueue(DispatchQueue.main)
        descriptor.name = name
        // Room for both orientations, so rotating only changes the mode.
        let longest = UInt32(max(pixelWidth, pixelHeight))
        descriptor.maxPixelsWide = longest
        descriptor.maxPixelsHigh = longest
        let safeDpi = Double(max(80, min(dpi, 800)))
        descriptor.sizeInMillimeters = CGSize(
            width: Double(pixelWidth) / safeDpi * 25.4,
            height: Double(pixelHeight) / safeDpi * 25.4
        )
        descriptor.vendorID = Self.vendorID
        descriptor.productID = 0x524E
        descriptor.serialNum = serial

        guard let display = CGVirtualDisplay(descriptor: descriptor) else { return nil }
        self.display = display
        guard apply(pixelWidth: pixelWidth, pixelHeight: pixelHeight, hiDPI: hiDPI, refreshRate: refreshRate) else {
            return nil
        }
    }

    /// Changes the resolution in place (e.g. when the tablet rotates); windows stay put.
    @discardableResult
    func apply(pixelWidth: Int, pixelHeight: Int, hiDPI: Bool, refreshRate: Double) -> Bool {
        let settings = CGVirtualDisplaySettings()
        settings.hiDPI = hiDPI ? 1 : 0
        // In HiDPI the mode is expressed in points; the backing store is 2x.
        let scale = hiDPI ? 2 : 1
        settings.modes = [
            CGVirtualDisplayMode(
                width: UInt(pixelWidth / scale),
                height: UInt(pixelHeight / scale),
                refreshRate: refreshRate
            )
        ]
        return display.applySettings(settings)
    }

    /// FNV-1a, so the same tablet always maps to the same serial number.
    static func serial(for identifier: String) -> UInt32 {
        var hash: UInt32 = 2_166_136_261
        for byte in identifier.utf8 {
            hash = (hash ^ UInt32(byte)) &* 16_777_619
        }
        return hash == 0 ? 1 : hash
    }
}
