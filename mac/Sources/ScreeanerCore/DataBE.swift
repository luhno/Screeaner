import Foundation

public extension Data {
    mutating func appendBE<T: FixedWidthInteger>(_ value: T) {
        var be = value.bigEndian
        Swift.withUnsafeBytes(of: &be) { append(contentsOf: $0) }
    }

    func readBE<T: FixedWidthInteger>(_ type: T.Type, at offset: Int) -> T {
        var value: T = 0
        for i in 0..<MemoryLayout<T>.size {
            value = (value << 8) | T(truncatingIfNeeded: self[startIndex + offset + i])
        }
        return value
    }

    func readFloat(at offset: Int) -> Float {
        Float(bitPattern: readBE(UInt32.self, at: offset))
    }
}
