import CryptoKit
import Foundation

/// A tablet the user approved once; it reconnects without a code
/// (unless the Mac only lets favorites in and it is not one).
struct PairedTablet: Identifiable, Equatable {
    let id: String          // the tablet's device id
    let name: String
    let publicKey: Data     // its static P-256 key, X9.63
    let pairedAt: Date
    /// Connects by itself even when the Mac only lets favorites in (see `SettingsKey.favoritesOnly`).
    let favorite: Bool
}

/// How far this Mac trusts a tablet that is connecting.
enum TabletTrust {
    case unknown, paired, favorite
}

/// This Mac's long-term identity key and the list of approved tablets.
/// Used from the main queue and the connection queues, so the list is guarded by a lock.
final class PairingStore {
    static let shared = PairingStore()

    private let defaultsKey = "pairedTablets"
    private let lock = NSLock()
    /// Device ids refused (or not answered) recently, so their retries do not keep popping the panel.
    private var snoozedUntil: [String: Date] = [:]
    let identity: P256.KeyAgreement.PrivateKey

    private init() {
        identity = Self.loadOrCreateIdentity()
    }

    /// Stored in Application Support (readable only by this user) rather than
    /// the Keychain, whose access rules would break on every ad-hoc rebuild.
    private static func loadOrCreateIdentity() -> P256.KeyAgreement.PrivateKey {
        let fm = FileManager.default
        let base = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fm.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support")
        let directory = base.appendingPathComponent("Screeaner", isDirectory: true)
        let file = directory.appendingPathComponent("identity.key")
        if let data = try? Data(contentsOf: file),
           let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: data) {
            return key
        }
        let key = P256.KeyAgreement.PrivateKey()
        try? fm.createDirectory(at: directory, withIntermediateDirectories: true,
                                attributes: [.posixPermissions: 0o700])
        fm.createFile(atPath: file.path, contents: key.rawRepresentation, attributes: [.posixPermissions: 0o600])
        return key
    }

    var tablets: [PairedTablet] {
        lock.lock()
        defer { lock.unlock() }
        return readTablets()
    }

    private func readTablets() -> [PairedTablet] {
        guard let raw = UserDefaults.standard.dictionary(forKey: defaultsKey) as? [String: [String: Any]] else {
            return []
        }
        return raw.compactMap { id, entry in
            guard let name = entry["name"] as? String,
                  let keyText = entry["key"] as? String,
                  let key = Data(base64Encoded: keyText)
            else { return nil }
            let date = Date(timeIntervalSince1970: entry["date"] as? Double ?? 0)
            let favorite = entry["favorite"] as? Bool ?? false
            return PairedTablet(id: id, name: name, publicKey: key, pairedAt: date, favorite: favorite)
        }
        // Favorites first, then in pairing order.
        .sorted { ($0.favorite ? 0 : 1, $0.pairedAt) < ($1.favorite ? 0 : 1, $1.pairedAt) }
    }

    func trust(deviceId: String, publicKey: Data) -> TabletTrust {
        lock.lock()
        defer { lock.unlock() }
        guard let tablet = readTablets().first(where: { $0.id == deviceId && $0.publicKey == publicKey }) else {
            return .unknown
        }
        return tablet.favorite ? .favorite : .paired
    }

    var hasFavorites: Bool {
        lock.lock()
        defer { lock.unlock() }
        return readTablets().contains(where: \.favorite)
    }

    func isFavorite(deviceId: String) -> Bool? {
        lock.lock()
        defer { lock.unlock() }
        return readTablets().first(where: { $0.id == deviceId })?.favorite
    }

    /// Approves a tablet. `favorite` nil keeps what it had (a re-approval does not drop the star);
    /// the pairing date is kept when the key is the same.
    func add(deviceId: String, name: String, publicKey: Data, favorite: Bool? = nil) {
        lock.lock()
        defer { lock.unlock() }
        var raw = UserDefaults.standard.dictionary(forKey: defaultsKey) ?? [:]
        let old = raw[deviceId] as? [String: Any]
        let sameKey = (old?["key"] as? String) == publicKey.base64EncodedString()
        raw[deviceId] = [
            "name": name,
            "key": publicKey.base64EncodedString(),
            "date": sameKey ? (old?["date"] as? Double ?? Date().timeIntervalSince1970) : Date().timeIntervalSince1970,
            "favorite": favorite ?? (sameKey ? (old?["favorite"] as? Bool ?? false) : false),
        ]
        UserDefaults.standard.set(raw, forKey: defaultsKey)
        snoozedUntil.removeValue(forKey: deviceId)
    }

    func setFavorite(deviceId: String, _ favorite: Bool) {
        lock.lock()
        defer { lock.unlock() }
        var raw = UserDefaults.standard.dictionary(forKey: defaultsKey) ?? [:]
        guard var entry = raw[deviceId] as? [String: Any] else { return }
        entry["favorite"] = favorite
        raw[deviceId] = entry
        UserDefaults.standard.set(raw, forKey: defaultsKey)
        if favorite { snoozedUntil.removeValue(forKey: deviceId) }
    }

    // MARK: - Snooze (memory only)

    func snooze(deviceId: String, seconds: TimeInterval) {
        lock.lock()
        defer { lock.unlock() }
        snoozedUntil[deviceId] = Date().addingTimeInterval(seconds)
    }

    func isSnoozed(deviceId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let until = snoozedUntil[deviceId] else { return false }
        if until > Date() { return true }
        snoozedUntil.removeValue(forKey: deviceId)
        return false
    }

    func remove(deviceId: String) {
        lock.lock()
        defer { lock.unlock() }
        var raw = UserDefaults.standard.dictionary(forKey: defaultsKey) ?? [:]
        raw.removeValue(forKey: deviceId)
        UserDefaults.standard.set(raw, forKey: defaultsKey)
        snoozedUntil.removeValue(forKey: deviceId)
    }
}
