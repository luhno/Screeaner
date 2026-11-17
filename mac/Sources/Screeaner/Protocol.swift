import CoreMedia
import Foundation
import ScreeanerCore

/// Wire protocol shared with the Android app (see the Protocol section of README.md).
/// Control channel (TCP): every message is [type: u8][length: u32 BE][payload].
/// After the pairing handshake every message travels as `.encrypted`.
/// Video may also travel over UDP with FEC (see FecPacketizer).
enum Proto {
    static let port: UInt16 = 27183
    static let serviceType = "_screeaner._tcp"
    static let headerSize = 5
    static let maxPayload = 8 * 1024 * 1024
    /// First four bytes ("SCRN") of the datagram the tablet sends to open the UDP path.
    static let udpMagic: UInt32 = 0x5343_524E
    /// Shown in the menu and sent to the tablet (which logs it in test sessions).
    static let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.9.1"

    /// The host clock in microseconds: frame timestamps, pongs and probe acks all use it.
    static func hostClockMicros() -> UInt64 {
        UInt64(max(0, CMTimeGetSeconds(CMClockGetTime(CMClockGetHostTimeClock()))) * 1_000_000)
    }
}

enum MsgType: UInt8 {
    case hello = 1            // tablet -> mac, JSON
    case config = 2           // mac -> tablet, JSON
    case video = 3            // mac -> tablet, [pts u64][flags u8][encodeMicros u32][Annex-B]
    case touch = 4            // tablet -> mac, [action u8][x f32][y f32][pressure f32][button u8][tiltX f32][tiltY f32][tool u8]
    case scroll = 5           // tablet -> mac, [dx f32][dy f32][x f32][y f32]
    case keyframeRequest = 6  // tablet -> mac
    case ping = 7             // tablet -> mac, opaque payload
    case pong = 8             // mac -> tablet, same payload echoed + [macClockMicros u64]
    case stats = 9            // tablet -> mac, [rttMicros u32][packetsExpected u32][packetsLost u32][framesLost u16][framesRendered u16]
    case zoom = 10            // tablet -> mac, [phase u8][delta f32][x f32][y f32]
    case key = 11             // tablet -> mac, [action u8][androidKeyCode u16][flags u8][scanCode u16]
    case probe = 12           // tablet -> mac, [state u8][seq u32] (latency test square)
    case probeAck = 13        // mac -> tablet, [seq u32][macClockMicros u64] once the square was painted
    case updateOffer = 14     // mac -> tablet, JSON UpdateOffer: a newer tablet app is available
    case updateRequest = 15   // tablet -> mac, empty: send it
    case updateApk = 16       // mac -> tablet, the APK bytes
    case resize = 17          // tablet -> mac, JSON {width, height}: rotation without reconnecting
    case clipboard = 18       // both ways, UTF-8 text
    case audio = 19           // mac -> tablet, PCM s16le stereo 48 kHz
    case mouse = 27           // tablet -> mac, [kind u8][button u8][a f32][b f32][x f32][y f32] (see MouseKind)
    case text = 28            // tablet -> mac, [action u8][androidKeyCode u16][scanCode u16][flags u8][UTF-8 text]
    case endpoints = 29       // mac -> tablet, JSON {hosts: [{host, port, udp}]}: where to find this Mac from the internet

    case secHello = 20        // tablet -> mac, pairing handshake (see SecureChannel)
    case secServer = 21       // mac -> tablet
    case secReveal = 22       // tablet -> mac
    case secConfirm = 23      // tablet -> mac, [hmac 32]
    case secOk = 24           // mac -> tablet, [hmac 32]
    case secDenied = 25       // mac -> tablet, UTF-8 reason
    case encrypted = 26       // both ways after the handshake: AES-GCM of [type][payload]

    /// Messages that may travel in the clear (before the channel is established).
    var isHandshake: Bool { rawValue >= 20 && rawValue <= 25 }
}

/// What a `.mouse` message carries. x/y are normalized tablet positions, or NaN for
/// "at the Mac's cursor" (a trackpad captured by the tablet drives the whole Mac).
enum MouseKind: UInt8 {
    case move = 0       // a, b: relative movement in points
    case down = 1       // button: 0 left, 1 right, 2 middle
    case up = 2
    case wheel = 3      // a, b: lines (fractional for high-resolution wheels), positive = up / left
    case scroll = 4     // a, b: points, content follows the fingers
    case zoom = 5       // button: phase (0 begin, 1 change, 2 end), a: relative change
    case cursor = 6     // button: 1 draw the Mac's cursor in the video, 0 hide it (the tablet draws its own)
}

struct Hello: Decodable {
    let width: Int
    let height: Int
    let densityDpi: Int
    let refreshRate: Double
    let codecs: [String]
    let name: String
    /// True when the tablet wants video over UDP (it is not connected through USB).
    let udp: Bool?
    /// Tablet app version, for display.
    let version: String?
    /// "extend", "main", "mirror" or "only" (see DisplayMode). Missing means extend.
    let mode: String?
    /// "auto", "left", "right", "above" or "below" (see DisplayPosition).
    let position: String?
    /// Share the clipboard with this tablet.
    let clipboard: Bool?
    /// Send the Mac's audio to this tablet.
    let audio: Bool?
    /// Settings the tablet wants for this stream only (the benchmark uses it); nil = menu settings.
    let streamOverride: StreamOverride?

    struct StreamOverride: Decodable {
        let codec: String?
        let fps: Int?
        let frameCap: Bool?
    }
}

struct Resize: Decodable {
    let width: Int
    let height: Int
}

struct StreamConfig: Encodable {
    let codec: String
    let width: Int
    let height: Int
    let fps: Int
    /// Non-zero when the Mac accepts UDP video: the tablet echoes it in its UDP hello.
    let token: UInt32
    let udpPort: Int
    /// The display mode actually in use.
    let mode: String
    /// This Mac's name and app version.
    let server: String
    let version: String
    /// Whether audio packets will follow.
    let audio: Bool
    /// Whether VideoToolbox accepted low-latency rate control for this stream.
    let lowLatency: Bool
    /// Whether each frame is capped at a multiple of the average frame size.
    let frameCap: Bool
    /// This tablet is a favorite here: it may remember this Mac's address and try it first.
    let favorite: Bool
}

enum Codec: String {
    case h264
    case hevc
}
