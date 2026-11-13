package app.screeaner

/**
 * Wire protocol shared with the Mac app (see the Protocol section of README.md).
 * Every message is: [type: u8][length: u32 BE][payload].
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
    const val PONG = 8              // mac -> tablet, same payload echoed

    const val VIDEO_HEADER = 13

    const val ACTION_DOWN = 0
    const val ACTION_MOVE = 1
    const val ACTION_UP = 2
    const val ACTION_HOVER = 3

    const val BUTTON_LEFT = 0
    const val BUTTON_RIGHT = 1
}
