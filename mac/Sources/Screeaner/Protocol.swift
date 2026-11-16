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
    static let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.4.0"
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
    case resize = 12          // tablet -> mac, JSON {width, height}: rotation without reconnecting
    case clipboard = 13       // both ways, UTF-8 text
    case audio = 14           // mac -> tablet, PCM s16le stereo 48 kHz
    case probe = 15           // tablet -> mac, [id u32]: end-to-end latency probe (0 = start)
    case probeInfo = 16       // mac -> tablet, [x f32][y f32][w f32][h f32]: where the probe marker is

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
}

enum Codec: String {
    case h264
    case hevc
}
