package app.screeaner

/**
 * Wire protocol shared with the Mac app (see the Protocol section of README.md).
 * Control channel (TCP): every message is [type: u8][length: u32 BE][payload].
 * Video may also travel over UDP with FEC (see FecAssembler).
 */
object Protocol {
    const val PORT = 27183
    const val SERVICE_TYPE = "_screeaner._tcp"
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    const val HELLO = 1             // tablet -> mac, JSON
    const val CONFIG = 2            // mac -> tablet, JSON
    const val VIDEO = 3             // mac -> tablet, [pts u64][flags u8][encodeMicros u32][Annex-B]
    const val TOUCH = 4             // tablet -> mac, [action u8][x f32][y f32][pressure f32][button u8]
    const val SCROLL = 5            // tablet -> mac, [dx f32][dy f32][x f32][y f32]
    const val KEYFRAME_REQUEST = 6  // tablet -> mac
    const val PING = 7              // tablet -> mac, opaque payload
    const val PONG = 8              // mac -> tablet, same payload echoed + [macClockMicros u64]
    const val STATS = 9             // tablet -> mac, [rttMicros u32][packetsExpected u32][packetsLost u32][framesLost u16][framesRendered u16]
    const val ZOOM = 10             // tablet -> mac, [phase u8][delta f32][x f32][y f32]
    const val KEY = 11              // tablet -> mac, [action u8][androidKeyCode u16][flags u8]
    const val PROBE = 12            // tablet -> mac, [state u8][seq u32] (latency test square)
    const val PROBE_ACK = 13        // mac -> tablet, [seq u32][macClockMicros u64] once the square was painted

    /** "SCRN": first bytes of the datagram that opens the UDP video path. */
    const val UDP_MAGIC = 0x5343524E

    const val VIDEO_HEADER = 13

    /** IP traffic class for DSCP 46 (Expedited Forwarding), which Wi-Fi maps to the voice queue. */
    const val DSCP_EF = 0xB8

    const val ACTION_DOWN = 0
    const val ACTION_MOVE = 1
    const val ACTION_UP = 2
    const val ACTION_HOVER = 3

    const val ZOOM_BEGIN = 0
    const val ZOOM_CHANGE = 1
    const val ZOOM_END = 2

    const val PROBE_HIDDEN = 0
    const val PROBE_BLACK = 1
    const val PROBE_WHITE = 2
    const val PROBE_MOTION_ON = 3   // benchmark: moving pattern over the whole display
    const val PROBE_MOTION_OFF = 4

    const val BUTTON_LEFT = 0
    const val BUTTON_RIGHT = 1
}
