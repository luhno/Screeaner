import Foundation
import ScreeanerCore

/// Wire protocol shared with the Android app (see the Protocol section of README.md).
/// Control channel (TCP): every message is [type: u8][length: u32 BE][payload].
/// Video may also travel over UDP with FEC (see FecPacketizer).
enum Proto {
    static let port: UInt16 = 27183
    static let serviceType = "_screeaner._tcp"
    static let headerSize = 5
    static let maxPayload = 8 * 1024 * 1024
    /// First four bytes ("SCRN") of the datagram the tablet sends to open the UDP path.
    static let udpMagic: UInt32 = 0x5343_524E
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
    case stats = 9            // tablet -> mac, [rttMicros u32][packetsExpected u32][packetsLost u32][framesLost u16][framesRendered u16]
    case zoom = 10            // tablet -> mac, [phase u8][delta f32][x f32][y f32]
    case key = 11             // tablet -> mac, [action u8][androidKeyCode u16][flags u8]
}

struct Hello: Decodable {
    let width: Int
    let height: Int
    let densityDpi: Int
    let refreshRate: Double
    let codecs: [String]
    let name: String
    /// Stable per-device identifier; lets macOS remember each tablet's arrangement.
    let id: String?
    /// True when the tablet wants video over UDP (it is not connected through USB).
    let udp: Bool?
}

struct StreamConfig: Encodable {
    let codec: String
    let width: Int
    let height: Int
    let fps: Int
    /// Non-zero when the Mac accepts UDP video: the tablet echoes it in its UDP hello.
    let token: UInt32
    let udpPort: Int
}

enum Codec: String {
    case h264
    case hevc
}
