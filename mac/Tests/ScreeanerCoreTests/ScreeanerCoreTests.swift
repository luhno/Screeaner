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
        for packet in FecPacketizer.packets(frameId: 7, blob: blob(5000, seed: 7), isKey: true, redundancy: 0.2) {
            hasher.update(data: packet)
        }
        let hex = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(hex, "f700487071335eff360ac5a786f298ea16eab22e04f7bff1d57b032f48e901c4")
    }

    func testShape() {
        let packets = FecPacketizer.packets(frameId: 1, blob: blob(30_000, seed: 3), isKey: false, redundancy: 0.2)
        XCTAssertEqual(packets.count, 30) // k = 25, m = 5
        XCTAssertTrue(packets.allSatisfy { $0.count == FecPacketizer.headerSize + 1200 })
        XCTAssertEqual(packets[29].readBE(UInt16.self, at: 4), 29)
        XCTAssertEqual(packets[0].readBE(UInt32.self, at: 12), 30_000)

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
