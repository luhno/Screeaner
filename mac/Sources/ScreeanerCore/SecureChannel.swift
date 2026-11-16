import CryptoKit
import Foundation

public enum SecureChannelError: Error {
    case malformed
    case authentication
}

/// Pairing and encryption shared with the tablet (android/.../SecureChannel.java,
/// which documents the handshake). Both test suites check the same
/// known-answer vector, computed independently with Python.
public enum SecureChannel {
    public static let publicKeySize = 65
    public static let nonceSize = 32
    static let transcriptLabel = Data("Screeaner handshake v1".utf8)
    static let keysLabel = Data("Screeaner session v1".utf8)
    static let sasLabel = Data("Screeaner SAS v1".utf8)

    public static func sha256(_ parts: Data...) -> Data {
        var hasher = SHA256()
        for part in parts { hasher.update(data: part) }
        return Data(hasher.finalize())
    }

    public static func randomBytes(_ count: Int) -> Data {
        var generator = SystemRandomNumberGenerator()
        return Data((0..<count).map { _ in UInt8.random(in: 0...255, using: &generator) })
    }

    public static func transcript(hello: Data, server: Data, reveal: Data) -> Data {
        func length(_ data: Data) -> Data {
            var out = Data()
            out.appendBE(UInt32(data.count))
            return out
        }
        return sha256(transcriptLabel, length(hello), hello, length(server), server, length(reveal), reveal)
    }

    /// Six-digit comparison code shown on both screens.
    public static func sas(_ transcript: Data) -> String {
        let hash = sha256(sasLabel, transcript)
        let value = hash.readBE(UInt32.self, at: 0)
        return String(format: "%06u", value % 1_000_000)
    }

    public static func ecdh(_ privateKey: P256.KeyAgreement.PrivateKey, _ publicX963: Data) throws -> Data {
        guard let other = try? P256.KeyAgreement.PublicKey(x963Representation: publicX963) else {
            throw SecureChannelError.malformed
        }
        let secret = try privateKey.sharedSecretFromKeyAgreement(with: other)
        return secret.withUnsafeBytes { Data($0) }
    }

    // MARK: - Handshake messages

    public struct Hello {
        public let staticPublic: Data
        public let commit: Data
        public let deviceId: String
        public let name: String

        public init(payload: Data) throws {
            let bytes = [UInt8](payload)
            var offset = 1 + publicKeySize + 32
            guard bytes.count > offset, bytes[0] == 1 else { throw SecureChannelError.malformed }
            staticPublic = Data(bytes[1..<(1 + publicKeySize)])
            commit = Data(bytes[(1 + publicKeySize)..<offset])
            func string() throws -> String {
                guard offset < bytes.count else { throw SecureChannelError.malformed }
                let length = Int(bytes[offset])
                offset += 1
                guard offset + length <= bytes.count else { throw SecureChannelError.malformed }
                defer { offset += length }
                return String(decoding: bytes[offset..<(offset + length)], as: UTF8.self)
            }
            deviceId = try string()
            name = try string()
        }
    }

    public struct Reveal {
        public let ephemeralPublic: Data
        public let nonce: Data

        public init(payload: Data) throws {
            let bytes = [UInt8](payload)
            guard bytes.count == publicKeySize + nonceSize else { throw SecureChannelError.malformed }
            ephemeralPublic = Data(bytes[0..<publicKeySize])
            nonce = Data(bytes[publicKeySize...])
        }
    }

    public static func buildServer(staticPublic: Data, ephemeralPublic: Data, nonce: Data,
                                   knowsTablet: Bool, name: String) -> Data {
        var out = Data()
        out.append(staticPublic)
        out.append(ephemeralPublic)
        out.append(nonce)
        out.append(knowsTablet ? 1 : 0)
        let label = Data(name.utf8.prefix(255))
        out.append(UInt8(label.count))
        out.append(label)
        return out
    }

    // MARK: - Session

    /// Keys and counters of an established channel. Callers serialize sends and receives.
    public final class Session {
        public let transcript: Data
        public let sas: String
        private let sendKey: SymmetricKey
        private let receiveKey: SymmetricKey
        private let confirmKey: SymmetricKey
        private let videoKey: SymmetricKey
        private var sendCounter: UInt64 = 0
        private var receiveCounter: UInt64 = 0

        public init(ee: Data, ss: Data, transcript: Data, tabletSide: Bool) {
            self.transcript = transcript
            let okm = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: SymmetricKey(data: ee + ss), salt: transcript,
                info: SecureChannel.keysLabel, outputByteCount: 128
            ).withUnsafeBytes { Data($0) }
            let t2m = SymmetricKey(data: okm[okm.startIndex..<(okm.startIndex + 32)])
            let m2t = SymmetricKey(data: okm[(okm.startIndex + 32)..<(okm.startIndex + 64)])
            confirmKey = SymmetricKey(data: okm[(okm.startIndex + 64)..<(okm.startIndex + 96)])
            videoKey = SymmetricKey(data: okm[(okm.startIndex + 96)..<(okm.startIndex + 128)])
            sendKey = tabletSide ? t2m : m2t
            receiveKey = tabletSide ? m2t : t2m
            sas = SecureChannel.sas(transcript)
        }

        public func tabletConfirmation() -> Data {
            Data(HMAC<SHA256>.authenticationCode(for: Data("tablet".utf8) + transcript, using: confirmKey))
        }

        public func macConfirmation() -> Data {
            Data(HMAC<SHA256>.authenticationCode(for: Data("mac".utf8) + transcript, using: confirmKey))
        }

        private static func nonce(_ prefix: UInt32, _ counter: UInt64) -> AES.GCM.Nonce {
            var data = Data()
            data.appendBE(prefix)
            data.appendBE(counter)
            return try! AES.GCM.Nonce(data: data) // 12 bytes, always valid
        }

        /// Payload of an ENCRYPTED message carrying (type, payload).
        public func seal(type: UInt8, payload: Data) throws -> Data {
            var plain = Data(capacity: payload.count + 1)
            plain.append(type)
            plain.append(payload)
            let box = try AES.GCM.seal(plain, using: sendKey, nonce: Self.nonce(0, sendCounter))
            sendCounter += 1
            return box.ciphertext + box.tag
        }

        /// Opens an ENCRYPTED payload. Throws on tampering, reordering or replay.
        public func open(_ data: Data) throws -> (UInt8, Data) {
            guard data.count >= 17 else { throw SecureChannelError.malformed }
            let tagStart = data.endIndex - 16
            let box = try AES.GCM.SealedBox(
                nonce: Self.nonce(0, receiveCounter),
                ciphertext: data[data.startIndex..<tagStart], tag: data[tagStart...]
            )
            guard let plain = try? AES.GCM.open(box, using: receiveKey), !plain.isEmpty else {
                throw SecureChannelError.authentication
            }
            receiveCounter += 1
            return (plain[plain.startIndex], Data(plain.dropFirst()))
        }

        /// Encrypts one video frame before FEC; the frame id is unique per session.
        public func sealVideo(frameId: UInt64, _ plain: Data) throws -> Data {
            let box = try AES.GCM.seal(plain, using: videoKey, nonce: Self.nonce(0xFFFF_FFFF, frameId))
            return box.ciphertext + box.tag
        }
    }
}
