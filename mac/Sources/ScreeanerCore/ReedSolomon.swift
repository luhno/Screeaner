import Foundation

/// Systematic Reed-Solomon erasure code over GF(256) with a Cauchy matrix.
/// Bit-identical to android/.../ReedSolomon.java (both test suites pin the
/// same digest). Frames are split into blocks of at most 32 data shards, each
/// with its own parity; shard i lives at [i * shard, (i + 1) * shard).
public enum ReedSolomon {
    public static let maxBlockData = 32

    private static let tables: (exp: [UInt8], log: [Int]) = {
        var exp = [UInt8](repeating: 0, count: 512)
        var log = [Int](repeating: 0, count: 256)
        var x = 1
        for i in 0..<255 {
            exp[i] = UInt8(x)
            log[x] = i
            x <<= 1
            if x & 0x100 != 0 { x ^= 0x11D }
        }
        for i in 255..<512 { exp[i] = exp[i - 255] }
        return (exp, log)
    }()

    /// mulTable[(a << 8) | b] = a * b in GF(256).
    public static let mulTable: [UInt8] = {
        var table = [UInt8](repeating: 0, count: 65536)
        let (exp, log) = tables
        for a in 1..<256 {
            for b in 1..<256 { table[(a << 8) | b] = exp[log[a] + log[b]] }
        }
        return table
    }()

    public static func inv(_ a: Int) -> Int {
        Int(tables.exp[255 - tables.log[a]])
    }

    public static func coefficient(_ r: Int, _ c: Int, _ m: Int) -> Int {
        inv(r ^ (m + c))
    }

    public static func blocks(_ k: Int) -> Int { (k + maxBlockData - 1) / maxBlockData }

    public static func blockData(_ k: Int, _ j: Int) -> Int {
        let b = blocks(k)
        return k / b + (j < k % b ? 1 : 0)
    }

    public static func blockDataStart(_ k: Int, _ j: Int) -> Int {
        let b = blocks(k)
        return j * (k / b) + min(j, k % b)
    }

    public static func blockParity(_ k: Int, _ m: Int, _ j: Int) -> Int {
        let b = blocks(k)
        return m / b + (j < m % b ? 1 : 0)
    }

    public static func blockParityStart(_ k: Int, _ m: Int, _ j: Int) -> Int {
        let b = blocks(k)
        return k + j * (m / b) + min(j, m % b)
    }

    /// At least one parity shard per block, at most k.
    public static func parityCount(k: Int, redundancy: Double) -> Int {
        min(k, max(blocks(k), Int((Double(k) * redundancy).rounded(.up))))
    }

    /// Shard indexes in send order: blocks interleaved so a burst of losses is spread out.
    public static func sendOrder(k: Int, m: Int) -> [Int] {
        let b = blocks(k)
        var longest = 0
        for j in 0..<b { longest = max(longest, blockData(k, j) + blockParity(k, m, j)) }
        var order: [Int] = []
        order.reserveCapacity(k + m)
        for t in 0..<longest {
            for j in 0..<b {
                let kj = blockData(k, j)
                let mj = blockParity(k, m, j)
                if t < kj {
                    order.append(blockDataStart(k, j) + t)
                } else if t < kj + mj {
                    order.append(blockParityStart(k, m, j) + (t - kj))
                }
            }
        }
        return order
    }

    /// Computes every parity shard from the data shards already in place.
    public static func encode(_ shards: inout [UInt8], shard: Int, k: Int, m: Int) {
        mulTable.withUnsafeBufferPointer { table in
            shards.withUnsafeMutableBufferPointer { buffer in
                guard let base = buffer.baseAddress, let mul = table.baseAddress else { return }
                for j in 0..<blocks(k) {
                    let kj = blockData(k, j)
                    let ds = blockDataStart(k, j)
                    let mj = blockParity(k, m, j)
                    let ps = blockParityStart(k, m, j)
                    for r in 0..<mj {
                        let out = base + (ps + r) * shard
                        out.update(repeating: 0, count: shard)
                        for c in 0..<kj {
                            let row = mul + (coefficient(r, c, mj) << 8)
                            let input = base + (ds + c) * shard
                            for i in 0..<shard {
                                out[i] ^= row[Int(input[i])]
                            }
                        }
                    }
                }
            }
        }
    }
}
