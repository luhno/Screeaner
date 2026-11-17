package app.screeaner

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Keyboards, mice and trackpads attached to the tablet (USB, Bluetooth, a cover).
 * The app adapts to what is there: ⌘ mapping, cursor, trackpad gestures.
 * Main thread only.
 */
class InputDeviceWatcher(context: Context, private val onChange: (Devices) -> Unit) :
    InputManager.InputDeviceListener {

    data class Devices(
        val keyboard: InputDevice? = null,
        val mouse: InputDevice? = null,
        val touchpad: InputDevice? = null,
    ) {
        val hasPointer: Boolean get() = mouse != null || touchpad != null

        /** Apple keyboards have a real ⌘ key; PC keyboards get Ctrl as ⌘. */
        val keyboardIsApple: Boolean
            get() = keyboard?.let { it.vendorId == APPLE_VENDOR || it.name.contains("Apple", true) || it.name.contains("Magic", true) } == true

        fun sameAs(other: Devices) =
            keyboard?.descriptor == other.keyboard?.descriptor &&
                mouse?.descriptor == other.mouse?.descriptor &&
                touchpad?.descriptor == other.touchpad?.descriptor
    }

    private val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    var devices = scan()
        private set

    fun start() {
        manager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun stop() = manager.unregisterInputDeviceListener(this)

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    private fun refresh() {
        val now = scan()
        if (now.sameAs(devices)) return
        devices = now
        onChange(now)
    }

    private fun scan(): Devices {
        var keyboard: InputDevice? = null
        var mouse: InputDevice? = null
        var touchpad: InputDevice? = null
        for (id in manager.inputDeviceIds) {
            val device = manager.getInputDevice(id) ?: continue
            if (device.isVirtual) continue
            if (keyboard == null && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                device.supportsSource(InputDevice.SOURCE_KEYBOARD)
            ) {
                keyboard = device
            }
            val name = device.name
            val padByName = name.contains("touchpad", true) || name.contains("trackpad", true)
            if (touchpad == null && (device.supportsSource(InputDevice.SOURCE_TOUCHPAD) || padByName) &&
                !device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
            ) {
                touchpad = device
            } else if (mouse == null && device.supportsSource(InputDevice.SOURCE_MOUSE) &&
                !device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
            ) {
                mouse = device
            }
        }
        return Devices(keyboard, mouse, touchpad)
    }

    private companion object {
        const val APPLE_VENDOR = 0x05AC
    }
}

/**
 * The tablet's hardware keyboard on the Mac.
 *
 * Shortcuts (Ctrl, ⌘, Option held) and special keys go as key codes, so the
 * Mac sees real key presses. With [tabletLayout], everything else goes as the
 * characters the tablet's own layout produces (ABNT2, AZERTY…), accents and
 * AltGr included: what is printed on the keys is what appears on the Mac,
 * whatever input source the Mac uses. Main thread only.
 */
class KeyboardBridge(private val out: Output) {
    interface Output {
        val supportsText: Boolean
        /** A latched modifier on the shortcut bar: the next key is a shortcut. */
        val modifierLatched: Boolean
        fun key(action: Int, keyCode: Int, isRepeat: Boolean, scanCode: Int)
        fun text(action: Int, keyCode: Int, scanCode: Int, isRepeat: Boolean, text: String)
    }

    /** Ctrl acts as ⌘ (PC keyboards, whose Windows key Android keeps for itself). */
    var swapCtrlCmd = false
    var tabletLayout = true

    /** A dead key (´ ~ ^ ` ¨) waiting for the next letter, as a combining accent. */
    private var pendingAccent = 0
    /** Text sent for each key held down, so its key-up matches. */
    private val textDown = HashMap<Int, String>()
    /** Keys handled here only (dead keys, AltGr, Caps Lock): their key-up is dropped too. */
    private val swallowed = HashSet<Int>()

    fun reset() {
        pendingAccent = 0
        textDown.clear()
        swallowed.clear()
    }

    fun handle(event: KeyEvent) {
        val keyCode = event.keyCode
        val code = mapped(keyCode)
        if (event.action == KeyEvent.ACTION_UP) {
            val sent = textDown.remove(keyCode)
            when {
                sent != null -> out.text(UP, code, event.scanCode, false, sent)
                swallowed.remove(keyCode) -> {}
                else -> out.key(UP, code, false, event.scanCode)
            }
            return
        }
        if (event.action != KeyEvent.ACTION_DOWN) return
        val repeat = event.repeatCount > 0
        if (tabletLayout && out.supportsText) {
            // AltGr only picks characters on the tablet's layout; Android applies Caps Lock itself.
            if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT || keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
                swallowed += keyCode
                return
            }
            if (repeat && swallowed.contains(keyCode)) return
            val text = if (repeat) textDown[keyCode] else characters(event)
            if (text != null) {
                if (text.isEmpty()) {
                    swallowed += keyCode // a dead key: wait for the letter
                } else {
                    textDown[keyCode] = text
                    out.text(DOWN, code, event.scanCode, repeat, text)
                }
                return
            }
        }
        out.key(DOWN, code, repeat, event.scanCode)
    }

    private fun mapped(code: Int): Int = if (!swapCtrlCmd) code else when (code) {
        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.KEYCODE_META_LEFT
        KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.KEYCODE_META_RIGHT
        KeyEvent.KEYCODE_META_LEFT -> KeyEvent.KEYCODE_CTRL_LEFT
        KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.KEYCODE_CTRL_RIGHT
        else -> code
    }

    /** The text this key types; "" for a dead key; null when it must go as a key code. */
    private fun characters(event: KeyEvent): String? {
        val meta = event.metaState
        if (out.modifierLatched ||
            meta and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK or KeyEvent.META_ALT_LEFT_ON) != 0
        ) {
            pendingAccent = 0
            return null
        }
        val unicode = event.getUnicodeChar(meta)
        if (unicode == 0) return null
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            val previous = pendingAccent
            pendingAccent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            return if (previous != 0) spacing(previous) else "" // a second accent types the first
        }
        val accent = pendingAccent
        // Enter, Tab, Backspace and the space bar stay real keys.
        if (unicode < 0x20 || unicode == 0x7F) {
            pendingAccent = 0
            return null
        }
        if (accent != 0) {
            pendingAccent = 0
            if (unicode == ' '.code) return spacing(accent)
            val composed = KeyCharacterMap.getDeadChar(accent, unicode)
            return if (composed != 0) chars(composed) else spacing(accent) + chars(unicode)
        }
        if (unicode == ' '.code) return null
        return chars(unicode)
    }

    private fun chars(codePoint: Int) = String(Character.toChars(codePoint))

    /** The accent on its own, as typed by accent + space. */
    private fun spacing(accent: Int): String = when (accent) {
        0x0300 -> "`"
        0x0301 -> "´"
        0x0302 -> "^"
        0x0303 -> "~"
        0x0308 -> "¨"
        0x0327 -> "¸"
        0x030A -> "˚"
        else -> chars(accent)
    }

    private companion object {
        const val DOWN = 0
        const val UP = 1
    }
}

/**
 * Mice and trackpads attached to the tablet.
 *
 * On the tablet's screen (default), the pointer works like on a computer:
 * hover moves the Mac's cursor, buttons click at once (no finger delay),
 * right/middle buttons, wheels, and Android 14+ trackpad gestures (two-finger
 * scroll, pinch, three-finger swipes) instead of being read as finger drags.
 *
 * Captured ([onCaptured], "Mac inteiro"): the tablet keeps the trackpad to
 * itself and moves the Mac's cursor relatively, across every Mac screen, with
 * Mac-style gestures. Main thread only.
 */
class PointerBridge(private val out: Output) {
    interface Output {
        /** The Mac understands middle buttons, wheels in lines and relative movement (0.8+). */
        val extended: Boolean
        fun touch(action: Int, x: Float, y: Float, button: Int)
        /** Fractions of the display, content follows the fingers (works with any Mac version). */
        fun scroll(dx: Float, dy: Float, x: Float, y: Float)
        fun zoom(phase: Int, delta: Float, x: Float, y: Float)
        /** A `Protocol.MOUSE` message; x/y NaN = at the Mac's cursor. */
        fun mouse(kind: Int, button: Int, a: Float, b: Float, x: Float = Float.NaN, y: Float = Float.NaN)
        /** Presses the keys in order, then releases them (Mission Control and friends). */
        fun shortcut(vararg keyCodes: Int)
        /** Some mouse or trackpad input arrived. */
        fun sawPointer()
    }

    /** Scrolling opposite to the fingers (classic, not "natural"). */
    var reverseScroll = false
    /** Cursor speed multiplier while captured. */
    var speed = 1f

    private var held = 0
    private var implicitPrimary = false

    fun isPointer(event: MotionEvent): Boolean =
        (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) &&
            !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)

    // region On the tablet's screen

    /** Touch-stream events (down/move/up) of a mouse or trackpad; false if [event] is not one. */
    fun onTouch(event: MotionEvent, width: Float, height: Float): Boolean {
        if (!isPointer(event)) return false
        out.sawPointer()
        val x = event.x / width
        val y = event.y / height
        if (Build.VERSION.SDK_INT >= 34) {
            when (event.classification) {
                MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE -> {
                    twoFingerSwipe(event, width, height)
                    return true
                }
                MotionEvent.CLASSIFICATION_PINCH -> {
                    pinch(event, x, y)
                    return true
                }
                CLASSIFICATION_MULTI_FINGER_SWIPE -> {
                    multiFingerSwipe(event)
                    return true
                }
            }
        }
        // A trackpad touched without a click (older Android): its fingers come as a real
        // multi-touch stream; the touch-screen gestures read it best (tap, scroll, pinch).
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            fingerStream = mask(event.buttonState) == 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        }
        if (fingerStream) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) fingerStream = false
            return false
        }
        buttons(event, x, y)
        return true
    }

    private var fingerStream = false

    /** Hover, wheel and button events of a mouse or trackpad; false if [event] is not one. */
    fun onGeneric(event: MotionEvent, width: Float, height: Float): Boolean {
        if (!isPointer(event)) return false
        out.sawPointer()
        val x = event.x / width
        val y = event.y / height
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                out.touch(Protocol.ACTION_HOVER, x, y, Protocol.BUTTON_LEFT)
            MotionEvent.ACTION_SCROLL -> wheel(event, x, y)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> buttons(event, x, y)
            else -> {}
        }
        return true
    }

    private fun wheel(event: MotionEvent, x: Float, y: Float) {
        val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (out.extended) {
            out.mouse(Protocol.MOUSE_WHEEL, 0, -h, v, x, y)
        } else {
            out.scroll(-h * WHEEL_STEP, v * WHEEL_STEP, x, y)
        }
    }

    /** Sends a press or release for every button whose state changed, then the drag. */
    private fun buttons(event: MotionEvent, x: Float, y: Float) {
        var state = mask(event.buttonState)
        when (event.actionMasked) {
            // A trackpad tap on older Android versions comes without a button.
            MotionEvent.ACTION_DOWN -> implicitPrimary = state == 0
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                implicitPrimary = false
                state = 0
            }
        }
        if (implicitPrimary) state = state or 1
        if (!out.extended) state = (state and 3) or (if (state and 4 != 0) 1 else 0)
        for (b in 0..2) {
            val bit = 1 shl b
            if (held and bit != 0 && state and bit == 0) out.touch(Protocol.ACTION_UP, x, y, b)
        }
        for (b in 0..2) {
            val bit = 1 shl b
            if (held and bit == 0 && state and bit != 0) out.touch(Protocol.ACTION_DOWN, x, y, b)
        }
        held = state
        if (event.actionMasked == MotionEvent.ACTION_MOVE && state != 0) {
            out.touch(Protocol.ACTION_MOVE, x, y, lowest(state))
        }
    }

    private var swipeX = 0f
    private var swipeY = 0f

    private fun twoFingerSwipe(event: MotionEvent, width: Float, height: Float) {
        // Android moves one "fake finger" along with the two real ones.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeX = event.x
                swipeY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val sign = if (reverseScroll) -1f else 1f
                out.scroll(
                    sign * (event.x - swipeX) / width, sign * (event.y - swipeY) / height,
                    event.x / width, event.y / height,
                )
                swipeX = event.x
                swipeY = event.y
            }
        }
    }

    private var pinching = false

    private fun pinch(event: MotionEvent, x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < 34) return
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pinching) out.zoom(Protocol.ZOOM_END, 0f, x, y)
                pinching = false
            }
            else -> {
                if (!pinching) {
                    pinching = true
                    out.zoom(Protocol.ZOOM_BEGIN, 0f, x, y)
                }
                val factor = event.getAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR)
                if (factor > 0f && factor != 1f) out.zoom(Protocol.ZOOM_CHANGE, factor - 1f, x, y)
            }
        }
    }

    private fun multiFingerSwipe(event: MotionEvent) {
        if (Build.VERSION.SDK_INT < 34) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeX = 0f
                swipeY = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                swipeX += event.getAxisValue(MotionEvent.AXIS_GESTURE_X_OFFSET)
                swipeY += event.getAxisValue(MotionEvent.AXIS_GESTURE_Y_OFFSET)
            }
            MotionEvent.ACTION_UP -> swipeShortcut(swipeX, swipeY, 0.15f)
        }
    }

    /** Three fingers like on a Mac trackpad: up Mission Control, down App Exposé, sideways switches Spaces. */
    private fun swipeShortcut(dx: Float, dy: Float, threshold: Float) {
        if (max(abs(dx), abs(dy)) < threshold) return
        val arrow = if (abs(dy) > abs(dx)) {
            if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        } else {
            // Fingers to the left bring the Space on the right.
            if (dx < 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        }
        out.shortcut(KeyEvent.KEYCODE_CTRL_LEFT, arrow)
    }

    // endregion

    // region Captured: the whole Mac

    private var capturedHeld = 0

    /** Every event while the pointer is captured. */
    fun onCaptured(event: MotionEvent): Boolean {
        out.sawPointer()
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            touchpad.onEvent(event)
            return true
        }
        capturedButtons(mask(event.buttonState))
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var dx = event.x
                var dy = event.y
                for (h in 0 until event.historySize) {
                    dx += event.getHistoricalX(h)
                    dy += event.getHistoricalY(h)
                }
                val interval = max(1L, event.eventTime - lastMoveAt).toFloat()
                lastMoveAt = event.eventTime
                // Gentle acceleration: precise when slow, quick across big screens.
                val velocity = hypot(dx, dy) / interval
                val gain = speed * (0.9f + 0.6f * min(velocity / 2f, 2f))
                out.mouse(Protocol.MOUSE_MOVE, 0, dx * gain, dy * gain)
            }
            MotionEvent.ACTION_SCROLL -> out.mouse(
                Protocol.MOUSE_WHEEL, 0,
                -event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL),
            )
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> capturedButtons(0)
        }
        return true
    }

    private var lastMoveAt = 0L

    private fun capturedButtons(state: Int) {
        for (b in 0..2) {
            val bit = 1 shl b
            if (capturedHeld and bit != 0 && state and bit == 0) out.mouse(Protocol.MOUSE_UP, b, 0f, 0f)
        }
        for (b in 0..2) {
            val bit = 1 shl b
            if (capturedHeld and bit == 0 && state and bit != 0) out.mouse(Protocol.MOUSE_DOWN, b, 0f, 0f)
        }
        capturedHeld = state
    }

    /** Lets go of everything (capture lost, stream ended). */
    fun releaseAll() {
        if (capturedHeld != 0) capturedButtons(0)
        touchpad.cancel()
        held = 0
        implicitPrimary = false
        pinching = false
        fingerStream = false
    }

    private val touchpad = TouchpadGestures()

    /**
     * Raw fingers of a captured trackpad, turned into Mac gestures: one finger
     * moves, tap clicks, two fingers scroll / pinch / tap for the right button,
     * three fingers tap for the middle button or swipe for Mission Control and Spaces.
     */
    private inner class TouchpadGestures {
        private val fingers = HashMap<Int, FloatArray>() // pointer id -> x, y in mm
        private var mode = NONE
        private var maxFingers = 0
        private var downAt = 0L
        private var lastAt = 0L
        private var travel = 0f
        private var clicked = false
        private var button = -1
        private var lastCx = 0f
        private var lastCy = 0f
        private var startCx = 0f
        private var startCy = 0f
        private var startSpan = 0f
        private var lastSpan = 0f
        private var swipeX = 0f
        private var swipeY = 0f
        private var mmX = 0.05f
        private var mmY = 0.05f
        private var scaledDevice = Int.MIN_VALUE

        fun cancel() {
            if (button >= 0) out.mouse(Protocol.MOUSE_UP, button, 0f, 0f)
            if (mode == PINCH) out.mouse(Protocol.MOUSE_ZOOM, Protocol.ZOOM_END, 0f, 0f)
            button = -1
            mode = NONE
            fingers.clear()
        }

        fun onEvent(event: MotionEvent) {
            scale(event)
            physicalButton(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fingers.clear()
                    mode = if (button >= 0) MOVE else NONE
                    maxFingers = 1
                    downAt = event.eventTime
                    lastAt = event.eventTime
                    travel = 0f
                    clicked = button >= 0
                    swipeX = 0f
                    swipeY = 0f
                    remember(event)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    remember(event)
                    val count = event.pointerCount
                    maxFingers = max(maxFingers, count)
                    if (button >= 0) return // clicking with one finger and dragging with another
                    if (mode == PINCH) out.mouse(Protocol.MOUSE_ZOOM, Protocol.ZOOM_END, 0f, 0f)
                    mode = if (count >= 3) SWIPE else TWO
                    centroid(event)
                    startCx = lastCx
                    startCy = lastCy
                    startSpan = lastSpan
                }
                MotionEvent.ACTION_MOVE -> move(event)
                MotionEvent.ACTION_POINTER_UP -> {
                    fingers.remove(event.getPointerId(event.actionIndex))
                    if (mode == PINCH) out.mouse(Protocol.MOUSE_ZOOM, Protocol.ZOOM_END, 0f, 0f)
                    // Lifting one finger after scrolling must not start moving the cursor.
                    if (mode == TWO || mode == SCROLL || mode == PINCH) mode = DONE
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish(event)
            }
        }

        private fun scale(event: MotionEvent) {
            if (event.deviceId == scaledDevice) return
            scaledDevice = event.deviceId
            fun mmPerUnit(axis: Int): Float {
                val range = event.device?.getMotionRange(axis, event.source) ?: return 0.05f
                return when {
                    range.resolution > 0f -> 1f / range.resolution
                    range.range > 0f -> 100f / range.range // assume a 10 cm trackpad
                    else -> 0.05f
                }
            }
            mmX = mmPerUnit(MotionEvent.AXIS_X)
            mmY = mmPerUnit(MotionEvent.AXIS_Y)
        }

        private fun remember(event: MotionEvent) {
            for (i in 0 until event.pointerCount) {
                fingers[event.getPointerId(i)] = floatArrayOf(event.getX(i) * mmX, event.getY(i) * mmY)
            }
        }

        /** A press of the trackpad itself: left, or right with two fingers down. */
        private fun physicalButton(event: MotionEvent) {
            val pressed = mask(event.buttonState) != 0
            if (pressed && button < 0) {
                button = if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 || event.pointerCount >= 2) 1 else 0
                clicked = true
                if (mode == TWO || mode == NONE) mode = MOVE
                out.mouse(Protocol.MOUSE_DOWN, button, 0f, 0f)
            } else if (!pressed && button >= 0) {
                out.mouse(Protocol.MOUSE_UP, button, 0f, 0f)
                button = -1
            }
        }

        private fun centroid(event: MotionEvent) {
            val n = min(event.pointerCount, 2)
            var x = 0f
            var y = 0f
            for (i in 0 until n) {
                x += event.getX(i) * mmX
                y += event.getY(i) * mmY
            }
            lastCx = x / n
            lastCy = y / n
            lastSpan = if (event.pointerCount >= 2) {
                max(1f, hypot((event.getX(0) - event.getX(1)) * mmX, (event.getY(0) - event.getY(1)) * mmY))
            } else {
                1f
            }
        }

        private fun move(event: MotionEvent) {
            val count = event.pointerCount
            var bestDx = 0f
            var bestDy = 0f
            var best = 0f
            var sumDx = 0f
            var sumDy = 0f
            for (i in 0 until count) {
                val id = event.getPointerId(i)
                val x = event.getX(i) * mmX
                val y = event.getY(i) * mmY
                val previous = fingers[id]
                if (previous != null) {
                    val dx = x - previous[0]
                    val dy = y - previous[1]
                    sumDx += dx
                    sumDy += dy
                    val distance = hypot(dx, dy)
                    if (distance > best) {
                        best = distance
                        bestDx = dx
                        bestDy = dy
                    }
                }
                fingers[id] = floatArrayOf(x, y)
            }
            val interval = max(1L, event.eventTime - lastAt).toFloat()
            lastAt = event.eventTime
            travel += best
            when {
                mode == MOVE || (mode == NONE && count == 1) -> {
                    mode = MOVE
                    // The finger that moves the most (another may be resting on the button).
                    val velocity = best / interval // mm per ms
                    val gain = speed * (2.2f + 16f * min(velocity, 1.5f)) // points per mm
                    out.mouse(Protocol.MOUSE_MOVE, 0, bestDx * gain, bestDy * gain)
                }
                mode == SWIPE -> {
                    swipeX += sumDx / count
                    swipeY += sumDy / count
                }
                count >= 2 && (mode == TWO || mode == SCROLL || mode == PINCH) -> twoFingers(event)
            }
        }

        private fun twoFingers(event: MotionEvent) {
            val previousCx = lastCx
            val previousCy = lastCy
            val previousSpan = lastSpan
            centroid(event)
            if (mode == TWO) {
                if (abs(lastSpan - startSpan) > PINCH_MM) {
                    mode = PINCH
                    out.mouse(Protocol.MOUSE_ZOOM, Protocol.ZOOM_BEGIN, 0f, 0f)
                    return
                }
                if (hypot(lastCx - startCx, lastCy - startCy) > SCROLL_MM) mode = SCROLL else return
            }
            when (mode) {
                SCROLL -> {
                    val sign = if (reverseScroll) -speed else speed
                    out.mouse(
                        Protocol.MOUSE_SCROLL, 0,
                        (lastCx - previousCx) * SCROLL_GAIN * sign, (lastCy - previousCy) * SCROLL_GAIN * sign,
                    )
                }
                PINCH -> out.mouse(Protocol.MOUSE_ZOOM, Protocol.ZOOM_CHANGE, lastSpan / previousSpan - 1f, 0f)
            }
        }

        private fun finish(event: MotionEvent) {
            if (mode == PINCH) out.mouse(Protocol.MOUSE_ZOOM, Protocol.ZOOM_END, 0f, 0f)
            val quick = event.actionMasked == MotionEvent.ACTION_UP &&
                event.eventTime - downAt < TAP_MS && travel < TAP_MM && !clicked
            if (quick) {
                // Tap to click: one finger left, two right, three middle.
                val tapped = when (maxFingers) {
                    1 -> 0
                    2 -> 1
                    else -> 2
                }
                out.mouse(Protocol.MOUSE_DOWN, tapped, 0f, 0f)
                out.mouse(Protocol.MOUSE_UP, tapped, 0f, 0f)
            } else if (mode == SWIPE) {
                swipeShortcut(swipeX, swipeY, SWIPE_MM)
            }
            mode = NONE
            fingers.clear()
        }
    }

    // endregion

    private fun mask(state: Int): Int {
        var bits = 0
        if (state and MotionEvent.BUTTON_PRIMARY != 0) bits = bits or 1
        if (state and MotionEvent.BUTTON_SECONDARY != 0) bits = bits or 2
        if (state and MotionEvent.BUTTON_TERTIARY != 0) bits = bits or 4
        return bits
    }

    private fun lowest(state: Int) = when {
        state and 1 != 0 -> 0
        state and 2 != 0 -> 1
        else -> 2
    }

    private companion object {
        const val WHEEL_STEP = 0.06f
        /** MotionEvent's three-or-more-finger trackpad swipe (Android 14), not in the public SDK constants. */
        const val CLASSIFICATION_MULTI_FINGER_SWIPE = 4
        const val NONE = 0
        const val MOVE = 1
        const val TWO = 2
        const val SCROLL = 3
        const val PINCH = 4
        const val SWIPE = 5
        const val DONE = 6
        const val TAP_MS = 200L
        const val TAP_MM = 2f
        const val SCROLL_MM = 1.5f
        const val PINCH_MM = 5f
        const val SWIPE_MM = 12f
        /** Points of scrolling per mm of finger travel. */
        const val SCROLL_GAIN = 9f
    }
}
