import Foundation

/// Splits an encoded frame into UDP datagrams and adds Reed-Solomon parity
/// so the tablet can rebuild lost packets without a retransmission round trip.
///
/// Datagram: [frameId u32][index u16][k u16][m u16][flags u8][version u8][frameSize u32][shard].
/// The frame becomes k equal data shards (last one zero padded) plus m parity
/// shards, grouped in blocks of up to 32 data shards (see ReedSolomon). Each
/// block survives losing as many of its packets as it has parity shards.
///
/// The Android side (FecAssembler.java) has the matching decoder; both test
/// suites pin the same reference digest.
public enum FecPacketizer {
    public static let headerSize = 16
    public static let version: UInt8 = 2
    /// Keeps every datagram under the usual 1500-byte MTU with room for IP/UDP headers.
    public static let maxShard = 1200

    public static func packets(frameId: UInt32, blob: Data, isKey: Bool, redundancy: Double) -> [Data] {
        let size = blob.count
        guard size > 0 else { return [] }
        let k = max(1, (size + maxShard - 1) / maxShard)
        let shard = (size + k - 1) / k
        let m = ReedSolomon.parityCount(k: k, redundancy: redundancy)
        guard k + m <= Int(UInt16.max) else { return [] }

        var shards = [UInt8](repeating: 0, count: (k + m) * shard)
        shards.withUnsafeMutableBytes { destination in
            blob.copyBytes(to: destination.bindMemory(to: UInt8.self), count: size)
        }
        ReedSolomon.encode(&shards, shard: shard, k: k, m: m)

        var result: [Data] = []
        result.reserveCapacity(k + m)
        shards.withUnsafeBufferPointer { buffer in
            guard let base = buffer.baseAddress else { return }
            for index in ReedSolomon.sendOrder(k: k, m: m) {
                var packet = Data(capacity: headerSize + shard)
                packet.appendBE(frameId)
                packet.appendBE(UInt16(index))
                packet.appendBE(UInt16(k))
                packet.appendBE(UInt16(m))
                packet.append(isKey ? 1 : 0)
                packet.append(version)
                packet.appendBE(UInt32(size))
                packet.append(base + index * shard, count: shard)
                result.append(packet)
            }
        }
        return result
    }
}
