import CryptoKit
import Foundation

/// The tablet app this Mac app carries (Contents/Resources/Screeaner.apk,
/// put there by scripts/bundle.sh). Tablets running an older build get it:
/// silently through adb over USB, or with one tap over Wi-Fi.
///
/// Android only installs an update signed with the same key as the installed
/// app, so a tampered APK is refused by the tablet itself.
struct TabletApk {
    let url: URL
    let versionCode: Int
    let versionName: String

    static let bundled: TabletApk? = {
        guard let url = Bundle.main.url(forResource: "Screeaner", withExtension: "apk"),
              let info = Bundle.main.infoDictionary,
              let code = (info["ScreeanerTabletVersionCode"] as? NSNumber)?.intValue
                ?? (info["ScreeanerTabletVersionCode"] as? String).flatMap(Int.init)
        else { return nil }
        let name = info["ScreeanerTabletVersionName"] as? String ?? "\(code)"
        return TabletApk(url: url, versionCode: code, versionName: name)
    }()

    /// The APK bytes and their SHA-256 (hex), or nil if the file cannot be read.
    /// Read and hashed once per launch: it is offered on every reconnect.
    func load() -> (data: Data, sha256: String)? {
        Self.cacheLock.lock()
        defer { Self.cacheLock.unlock() }
        if let cached = Self.cache, cached.url == url { return (cached.data, cached.sha256) }
        guard let data = try? Data(contentsOf: url) else { return nil }
        let hex = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        Self.cache = (url, data, hex)
        return (data, hex)
    }

    private static let cacheLock = NSLock()
    private static var cache: (url: URL, data: Data, sha256: String)?
}

struct UpdateOffer: Encodable {
    let versionName: String
    let versionCode: Int
    let size: Int
    let sha256: String
}
