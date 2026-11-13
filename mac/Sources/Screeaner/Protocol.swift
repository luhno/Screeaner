import Foundation

/// Wire protocol shared with the Android app (see the Protocol section of README.md).
/// Every message is: [type: u8][length: u32 BE][payload].
enum Proto {
    static let port: UInt16 = 27183
    static let serviceType = "_screeaner._tcp"
    static let headerSize = 5
    static let maxPayload = 8 * 1024 * 1024
}

enum MsgType: UInt8 {
    case hello = 1            // tablet -> mac, JSON
    case config = 2           // mac -> tablet, JSON
    case video = 3            // mac -> tablet, [pts u64][flags u8][encodeMicros u32][Annex-B]
    case touch = 4            // tablet -> mac, [action u8][x f32][y f32][pressure f32][button u8]
    case scroll = 5           // tablet -> mac, [dx f32][dy f32][x f32][y f32]
    case keyframeRequest = 6  // tablet -> mac
    case ping = 7             // tablet -> mac, opaque payload
    case pong = 8             // mac -> tablet, same payload echoed
}

struct Hello: Decodable {
    let width: Int
    let height: Int
    let densityDpi: Int
    let refreshRate: Double
    let codecs: [String]
    let name: String
}

struct StreamConfig: Encodable {
    let codec: String
    let width: Int
    let height: Int
    let fps: Int
}

enum Codec: String {
    case h264
    case hevc
}

extension Data {
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
