import CryptoKit
import Foundation

/// A tablet the user approved once; it reconnects without a code.
struct PairedTablet: Identifiable, Equatable {
    let id: String          // the tablet's device id
    let name: String
    let publicKey: Data     // its static P-256 key, X9.63
    let pairedAt: Date
}

/// This Mac's long-term identity key and the list of approved tablets.
/// All access happens on the main queue except `identity`, which is immutable.
final class PairingStore {
    static let shared = PairingStore()

    private let defaultsKey = "pairedTablets"
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
        guard let raw = UserDefaults.standard.dictionary(forKey: defaultsKey) as? [String: [String: Any]] else {
            return []
        }
        return raw.compactMap { id, entry in
            guard let name = entry["name"] as? String,
                  let keyText = entry["key"] as? String,
                  let key = Data(base64Encoded: keyText)
            else { return nil }
            let date = Date(timeIntervalSince1970: entry["date"] as? Double ?? 0)
            return PairedTablet(id: id, name: name, publicKey: key, pairedAt: date)
        }
        .sorted { $0.pairedAt < $1.pairedAt }
    }

    func isPaired(deviceId: String, publicKey: Data) -> Bool {
        tablets.contains { $0.id == deviceId && $0.publicKey == publicKey }
    }

    func add(deviceId: String, name: String, publicKey: Data) {
        var raw = UserDefaults.standard.dictionary(forKey: defaultsKey) ?? [:]
        raw[deviceId] = [
            "name": name,
            "key": publicKey.base64EncodedString(),
            "date": Date().timeIntervalSince1970,
        ]
        UserDefaults.standard.set(raw, forKey: defaultsKey)
    }

    func remove(deviceId: String) {
        var raw = UserDefaults.standard.dictionary(forKey: defaultsKey) ?? [:]
        raw.removeValue(forKey: deviceId)
        UserDefaults.standard.set(raw, forKey: defaultsKey)
    }
}
