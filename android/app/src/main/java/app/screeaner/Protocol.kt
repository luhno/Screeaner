package app.screeaner

/**
 * Wire protocol shared with the Mac app (see the Protocol section of README.md).
 * Control channel (TCP): every message is [type: u8][length: u32 BE][payload].
 * After the pairing handshake (see SecureChannel) every message travels as ENCRYPTED.
 * Video may also travel over UDP with FEC (see FecAssembler), encrypted per frame.
 */
object Protocol {
    const val PORT = 27183
    const val SERVICE_TYPE = "_screeaner._tcp"
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    const val HELLO = 1             // tablet -> mac, JSON
    const val CONFIG = 2            // mac -> tablet, JSON
    const val VIDEO = 3             // mac -> tablet, [pts u64][flags u8][encodeMicros u32][Annex-B]
    const val TOUCH = 4             // tablet -> mac, [action u8][x f32][y f32][pressure f32][button u8][tiltX f32][tiltY f32][tool u8]
    const val SCROLL = 5            // tablet -> mac, [dx f32][dy f32][x f32][y f32]
    const val KEYFRAME_REQUEST = 6  // tablet -> mac
    const val PING = 7              // tablet -> mac, opaque payload
    const val PONG = 8              // mac -> tablet, same payload echoed + [macClockMicros u64]
    const val STATS = 9             // tablet -> mac, [rttMicros u32][packetsExpected u32][packetsLost u32][framesLost u16][framesRendered u16]
    const val ZOOM = 10             // tablet -> mac, [phase u8][delta f32][x f32][y f32]
    const val KEY = 11              // tablet -> mac, [action u8][androidKeyCode u16][flags u8][scanCode u16]
    const val RESIZE = 12           // tablet -> mac, JSON {width, height}
    const val CLIPBOARD = 13        // both ways, UTF-8 text
    const val AUDIO = 14            // mac -> tablet, PCM s16le stereo 48 kHz
    const val PROBE = 15            // tablet -> mac, [id u32] (0 = show the marker)
    const val PROBE_INFO = 16       // mac -> tablet, [x f32][y f32][w f32][h f32]

    const val SEC_HELLO = 20
    const val SEC_SERVER = 21
    const val SEC_REVEAL = 22
    const val SEC_CONFIRM = 23
    const val SEC_OK = 24
    const val SEC_DENIED = 25
    const val ENCRYPTED = 26

    /** "SCRN": first bytes of the datagram that opens the UDP video path. */
    const val UDP_MAGIC = 0x5343524E

    const val VIDEO_HEADER = 13

    const val ACTION_DOWN = 0
    const val ACTION_MOVE = 1
    const val ACTION_UP = 2
    const val ACTION_HOVER = 3
    const val ACTION_PROXIMITY_IN = 4
    const val ACTION_PROXIMITY_OUT = 5

    const val TOOL_FINGER = 0
    const val TOOL_PEN = 1
    const val TOOL_ERASER = 2

    const val ZOOM_BEGIN = 0
    const val ZOOM_CHANGE = 1
    const val ZOOM_END = 2

    const val BUTTON_LEFT = 0
    const val BUTTON_RIGHT = 1
}
