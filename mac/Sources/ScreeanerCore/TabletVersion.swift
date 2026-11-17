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

/// Release versions ("0.9.0", tags like "v0.10.1-beta").
public enum AppVersion {
    /// "0.10.0" > "0.9.3"; a leading "v" and a suffix like "-beta" are ignored.
    public static func isNewer(_ candidate: String, than current: String) -> Bool {
        func parts(_ text: String) -> [Int] {
            let trimmed = text.hasPrefix("v") ? String(text.dropFirst()) : text
            return trimmed.split(separator: "-").first.map { $0.split(separator: ".").map { Int($0) ?? 0 } } ?? []
        }
        let a = parts(candidate)
        let b = parts(current)
        for index in 0..<max(a.count, b.count) {
            let x = index < a.count ? a[index] : 0
            let y = index < b.count ? b[index] : 0
            if x != y { return x > y }
        }
        return false
    }
}
