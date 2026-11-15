import CoreMedia
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
    /// Shown in the menu and sent to the tablet (which logs it in test sessions).
    static let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.5.0"

    /// The host clock in microseconds: frame timestamps, pongs and probe acks all use it.
    static func hostClockMicros() -> UInt64 {
        UInt64(max(0, CMTimeGetSeconds(CMClockGetTime(CMClockGetHostTimeClock()))) * 1_000_000)
    }
}

enum MsgType: UInt8 {
    case hello = 1            // tablet -> mac, JSON
    case config = 2           // mac -> tablet, JSON
    case video = 3            // mac -> tablet, [pts u64][flags u8][encodeMicros u32][Annex-B]
    case touch = 4            // tablet -> mac, [action u8][x f32][y f32][pressure f32][button u8]
    case scroll = 5           // tablet -> mac, [dx f32][dy f32][x f32][y f32]
    case keyframeRequest = 6  // tablet -> mac
    case ping = 7             // tablet -> mac, opaque payload
    case pong = 8             // mac -> tablet, same payload echoed + [macClockMicros u64]
    case stats = 9            // tablet -> mac, [rttMicros u32][packetsExpected u32][packetsLost u32][framesLost u16][framesRendered u16]
    case zoom = 10            // tablet -> mac, [phase u8][delta f32][x f32][y f32]
    case key = 11             // tablet -> mac, [action u8][androidKeyCode u16][flags u8]
    case probe = 12           // tablet -> mac, [state u8][seq u32] (latency test square)
    case probeAck = 13        // mac -> tablet, [seq u32][macClockMicros u64] once the square was painted
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
    /// Tablet app version, for display.
    let version: String?
    /// Settings the tablet wants for this stream only (the benchmark uses it); nil = menu settings.
    let streamOverride: StreamOverride?

    struct StreamOverride: Decodable {
        let codec: String?
        let fps: Int?
        let frameCap: Bool?
    }
}

struct StreamConfig: Encodable {
    let codec: String
    let width: Int
    let height: Int
    let fps: Int
    /// Non-zero when the Mac accepts UDP video: the tablet echoes it in its UDP hello.
    let token: UInt32
    let udpPort: Int
    /// This Mac's name and app version.
    let server: String
    let version: String
    /// Whether VideoToolbox accepted low-latency rate control for this stream.
    let lowLatency: Bool
    /// Whether each frame is capped at a multiple of the average frame size.
    let frameCap: Bool
}

enum Codec: String {
    case h264
    case hevc
}
