import Foundation

/// Reads the tablet app's version code out of the version string it sends
/// in its hello, e.g. "0.5.0 (5)" -> 5. Nil when there is none.
public enum TabletVersion {
    public static func code(from version: String?) -> Int? {
        guard let version,
              let open = version.lastIndex(of: "("),
              let close = version.lastIndex(of: ")"), open < close
        else { return nil }
        return Int(version[version.index(after: open)..<close].trimmingCharacters(in: .whitespaces))
    }

    /// True when the tablet runs an older build than the one this Mac app carries.
    public static func isOutdated(tabletVersion: String?, bundledCode: Int) -> Bool {
        guard let code = code(from: tabletVersion) else { return false }
        return code < bundledCode
    }
}
