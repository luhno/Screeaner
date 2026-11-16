import CryptoKit
import XCTest
@testable import ScreeanerCore

final class FecPacketizerTests: XCTestCase {
    private func blob(_ size: Int, seed: Int) -> Data {
        Data((0..<size).map { UInt8(truncatingIfNeeded: $0 * 31 + seed) })
    }

    /// Same vector as FecAssemblerTest.referenceVector on Android: both ends agree on the wire format.
    func testReferenceVector() {
        var hasher = SHA256()
        for packet in FecPacketizer.packets(frameId: 7, blob: blob(50_000, seed: 7), isKey: true, redundancy: 0.2) {
            hasher.update(data: packet)
        }
        let hex = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(hex, "a3b687fbaffbc7cd73fcbc8f53b4f60858173267adef2d7cb6e01f7183944a69")
    }

    func testFieldArithmetic() {
        for a in 1..<256 {
            XCTAssertEqual(ReedSolomon.mulTable[(a << 8) | ReedSolomon.inv(a)], 1)
        }
    }

    func testShape() {
        let packets = FecPacketizer.packets(frameId: 1, blob: blob(30_000, seed: 3), isKey: false, redundancy: 0.2)
        XCTAssertEqual(packets.count, 30) // k = 25, m = 5
        XCTAssertTrue(packets.allSatisfy { $0.count == FecPacketizer.headerSize + 1200 })
        XCTAssertEqual(Set(packets.map { $0.readBE(UInt16.self, at: 4) }).count, 30)
        XCTAssertEqual(packets[0].readBE(UInt32.self, at: 12), 30_000)
        XCTAssertEqual(packets[0][packets[0].startIndex + 11], 2)

        let tiny = FecPacketizer.packets(frameId: 2, blob: blob(40, seed: 9), isKey: true, redundancy: 0.2)
        XCTAssertEqual(tiny.count, 2) // a single shard is simply duplicated
        XCTAssertEqual(tiny[0].dropFirst(FecPacketizer.headerSize), tiny[1].dropFirst(FecPacketizer.headerSize))
    }
}

final class BitrateControllerTests: XCTestCase {
    func testBacksOffAndRecovers() {
        var controller = BitrateController(maximum: 20_000_000)
        XCTAssertNil(controller.update(.init()))
        XCTAssertEqual(controller.update(.init(lostFrames: 1)), 14_000_000)
        XCTAssertNil(controller.update(.init()))
        XCTAssertNil(controller.update(.init()))
        XCTAssertEqual(controller.update(.init()), 15_900_000)
        for _ in 0..<20 { _ = controller.update(.init()) }
        XCTAssertEqual(controller.current, 20_000_000)
    }

    func testFloorAndRttSignal() {
        var controller = BitrateController(maximum: 4_000_000)
        for _ in 0..<10 { _ = controller.update(.init(skippedFrames: 5)) }
        XCTAssertEqual(controller.current, 2_000_000)

        var rtt = BitrateController(maximum: 20_000_000)
        XCTAssertNil(rtt.update(.init(rttMs: 4)))
        XCTAssertNotNil(rtt.update(.init(rttMs: 80)))
    }

    func testRedundancyBounds() {
        XCTAssertEqual(BitrateController.redundancy(forLoss: 0), 0.1)
        XCTAssertEqual(BitrateController.redundancy(forLoss: 1), 0.5)
    }

    func testTabletVersionCode() {
        XCTAssertEqual(TabletVersion.code(from: "0.5.0 (5)"), 5)
        XCTAssertEqual(TabletVersion.code(from: "1.2.3 ( 42 )"), 42)
        XCTAssertNil(TabletVersion.code(from: "0.5.0"))
        XCTAssertNil(TabletVersion.code(from: nil))
        XCTAssertTrue(TabletVersion.isOutdated(tabletVersion: "0.5.0 (5)", bundledCode: 6))
        XCTAssertFalse(TabletVersion.isOutdated(tabletVersion: "0.6.0 (6)", bundledCode: 6))
        // Unknown versions are left alone: they cannot install over the air anyway.
        XCTAssertFalse(TabletVersion.isOutdated(tabletVersion: "0.1.0", bundledCode: 6))
    }
}

/// Same known-answer vector as SecureChannelTest on Android (computed with Python `cryptography`).
final class SecureChannelTests: XCTestCase {
    private func hex(_ string: String) -> Data {
        var out = Data()
        var index = string.startIndex
        while index < string.endIndex {
            let next = string.index(index, offsetBy: 2)
            out.append(UInt8(string[index..<next], radix: 16)!)
            index = next
        }
        return out
    }

    private func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    private func key(_ scalar: String) throws -> P256.KeyAgreement.PrivateKey {
        try P256.KeyAgreement.PrivateKey(rawRepresentation: hex(scalar))
    }

    func testKnownAnswer() throws {
        let sT = try key("ab2e00154017c257fd8761a83f64aa750819db6a8e083104e8605785928fb24d")
        let eT = try key("8c933dcfc1daee1a0c7a465bbe3ed3d559ad3b8bea7be809557855355b42ee80")
        let sM = try key("c34730b39aeb04c5bee2ff40a18b43004e731927a18c366a94df82cf90871dd1")
        let eM = try key("c51349aa7d9eface324a875250d53ce05ba301692442be56f8b6727ea775fa3d")
        let nT = Data(repeating: 0x11, count: 32)
        let nM = Data(repeating: 0x22, count: 32)
        let ephT = eT.publicKey.x963Representation
        let commit = SecureChannel.sha256(ephT, nT)

        var hello = Data([1])
        hello.append(sT.publicKey.x963Representation)
        hello.append(commit)
        hello.append(5)
        hello.append(Data("dev-1".utf8))
        hello.append(3)
        hello.append(Data("Tab".utf8))
        let parsed = try SecureChannel.Hello(payload: hello)
        XCTAssertEqual(parsed.deviceId, "dev-1")
        XCTAssertEqual(parsed.name, "Tab")
        XCTAssertEqual(parsed.commit, commit)

        let server = SecureChannel.buildServer(
            staticPublic: sM.publicKey.x963Representation, ephemeralPublic: eM.publicKey.x963Representation,
            nonce: nM, knowsTablet: false, name: "Mac"
        )
        let reveal = try SecureChannel.Reveal(payload: ephT + nT)
        XCTAssertEqual(SecureChannel.sha256(reveal.ephemeralPublic, reveal.nonce), parsed.commit)
        let t = SecureChannel.transcript(hello: hello, server: server, reveal: ephT + nT)
        XCTAssertEqual(hex(t), "41e1daa08ea71a0fed09b893aaac71028c4d9d98f67aa48d2ba14745b1dedce9")

        let mac = SecureChannel.Session(
            ee: try SecureChannel.ecdh(eM, ephT),
            ss: try SecureChannel.ecdh(sM, parsed.staticPublic),
            transcript: t, tabletSide: false
        )
        XCTAssertEqual(mac.sas, "953187")
        XCTAssertEqual(hex(mac.tabletConfirmation()), "c113a3d1eb54ae1d1d9a1d7146104fa7acb6dd7fbda1825e1c3aa95efe8b1faa")
        XCTAssertEqual(hex(mac.macConfirmation()), "da83f0f334c53fac2e66801821ab7214a34e99bf02c71122145bf45932d9548e")
        XCTAssertEqual(hex(try mac.sealVideo(frameId: 5, Data("frame".utf8))), "6055aeefc0c479b35339cf1cfd64e25597a2eb134e")

        // Tablet's first message opens; the Mac's own second message matches the vector.
        let (type, payload) = try mac.open(hex("baf9e6cfa474b08d7e6fb8dae81ac5dd7a84dfd0ef6b"))
        XCTAssertEqual(type, 1)
        XCTAssertEqual(payload, Data("hello".utf8))
        _ = try mac.seal(type: 2, payload: Data())
        XCTAssertEqual(hex(try mac.seal(type: 3, payload: Data("video".utf8))), "2f94b7a3100a949c90823c20ac20eefbbd368bfa44d2")

        // Replaying the same message fails (the counter moved on).
        XCTAssertThrowsError(try mac.open(hex("baf9e6cfa474b08d7e6fb8dae81ac5dd7a84dfd0ef6b")))
    }
}
