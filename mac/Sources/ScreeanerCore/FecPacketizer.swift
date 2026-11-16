import Foundation

/// Splits an encoded frame into UDP datagrams and adds interleaved XOR parity
/// so the tablet can rebuild lost packets without a retransmission round trip.
///
/// Datagram: [frameId u32][index u16][k u16][m u16][flags u8][version u8][frameSize u32][shard].
/// The frame becomes k equal data shards (last one zero padded) plus m parity
/// shards; parity j is the XOR of every data shard i with i % m == j. One lost
/// shard per group is recoverable, i.e. any burst of up to m consecutive losses.
///
/// The Android side (FecAssembler.java) has the matching decoder; both test
/// suites pin the same reference digest.
public enum FecPacketizer {
    public static let headerSize = 16
    public static let version: UInt8 = 1
    /// Keeps every datagram under the usual 1500-byte MTU with room for IP/UDP headers.
    public static let maxShard = 1200

    public static func packets(frameId: UInt32, blob: Data, isKey: Bool, redundancy: Double) -> [Data] {
        let size = blob.count
        guard size > 0 else { return [] }
        let k = max(1, (size + maxShard - 1) / maxShard)
        let shard = (size + k - 1) / k
        let m = min(k, max(1, Int((Double(k) * redundancy).rounded(.up))))
        guard k + m <= Int(UInt16.max) else { return [] }

        func header(_ index: Int) -> Data {
            var packet = Data(capacity: headerSize + shard)
            packet.appendBE(frameId)
            packet.appendBE(UInt16(index))
            packet.appendBE(UInt16(k))
            packet.appendBE(UInt16(m))
            packet.append(isKey ? 1 : 0)
            packet.append(version)
            packet.appendBE(UInt32(size))
            return packet
        }

        var result: [Data] = []
        result.reserveCapacity(k + m)
        var parity = [UInt8](repeating: 0, count: m * shard)

        blob.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let source = raw.bindMemory(to: UInt8.self).baseAddress else { return }
            parity.withUnsafeMutableBufferPointer { parityBuffer in
                guard let parityBase = parityBuffer.baseAddress else { return }
                for i in 0..<k {
                    let offset = i * shard
                    let count = min(shard, size - offset)
                    var packet = header(i)
                    packet.append(source + offset, count: count)
                    if count < shard {
                        packet.append(contentsOf: [UInt8](repeating: 0, count: shard - count))
                    }
                    result.append(packet)

                    let group = parityBase + (i % m) * shard
                    let data = source + offset
                    for b in 0..<count {
                        group[b] ^= data[b]
                    }
                }
            }
        }

        for j in 0..<m {
            var packet = header(k + j)
            packet.append(contentsOf: parity[(j * shard)..<((j + 1) * shard)])
            result.append(packet)
        }
        return result
    }
}
