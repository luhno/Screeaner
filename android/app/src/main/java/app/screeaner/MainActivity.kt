package app.screeaner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity(), SurfaceHolder.Callback, StreamClient.Listener {
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: View
    private lateinit var statusText: TextView
    private lateinit var hud: TextView
    private lateinit var hostInput: EditText
    private lateinit var discovery: Discovery

    private val handler = Handler(Looper.getMainLooper())
    private var client: StreamClient? = null
    private var refreshRate = 60f
    private var hudMode = HUD_OFF
    private lateinit var monitor: View
    private lateinit var latencyText: TextView
    private lateinit var performanceText: TextView
    private lateinit var latencyGraph: GraphView
    private lateinit var fpsGraph: GraphView
    private lateinit var recordButton: Button
    private lateinit var recordInfo: TextView
    private lateinit var deviceMonitor: DeviceMonitor
    private lateinit var recorder: SessionRecorder
    private var appVersion = "?"
    @Volatile private var manualHost: String? = null
    private var deviceId = ""
    private var streaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(R.layout.activity_main)
        surfaceView = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.status)
        hud = findViewById(R.id.hud)
        hostInput = findViewById(R.id.hostInput)
        discovery = Discovery(applicationContext)
        setUpMonitor()

        val prefs = getPreferences(Context.MODE_PRIVATE)
        manualHost = prefs.getString(PREF_HOST, null)
        // Stable identity: the Mac uses it to remember where this tablet's display sits.
        deviceId = prefs.getString(PREF_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(PREF_DEVICE_ID, it).apply()
        }
        hostInput.setText(manualHost ?: "")
        findViewById<Button>(R.id.connect).setOnClickListener {
            manualHost = hostInput.text.toString().trim()
            prefs.edit().putString(PREF_HOST, manualHost).apply()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(hostInput.windowToken, 0)
            hostInput.clearFocus()
            enterImmersive()
        }

        selectFastestDisplayMode()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        surfaceView.setOnTouchListener { _, event -> onSurfaceTouch(event) }
        surfaceView.setOnHoverListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                client?.sendTouch(
                    Protocol.ACTION_HOVER, event.x / view.width, event.y / view.height, 0f, Protocol.BUTTON_LEFT,
                )
            }
            true
        }
        // A mouse or trackpad attached to the tablet: wheel / two-finger scroll.
        surfaceView.setOnGenericMotionListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
                client?.sendScroll(
                    -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * WHEEL_STEP,
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL) * WHEEL_STEP,
                    event.x / view.width, event.y / view.height,
                )
                true
            } else {
                false
            }
        }
        surfaceView.holder.addCallback(this)
    }

    /** A keyboard attached to the tablet types on the Mac while streaming. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val current = client
        if (current != null && streaming && !event.isSystem) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> current.sendKey(0, event.keyCode, event.repeatCount > 0)
                KeyEvent.ACTION_UP -> current.sendKey(1, event.keyCode, false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // region Monitor, test sessions and version

    private fun setUpMonitor() {
        appVersion = try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            "${info.versionName} ($code)"
        } catch (e: Exception) {
            "?"
        }
        val about = "Screeaner v$appVersion · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}"
        findViewById<TextView>(R.id.version).text = about

        monitor = findViewById(R.id.monitor)
        latencyText = findViewById(R.id.latencyText)
        performanceText = findViewById(R.id.performanceText)
        latencyGraph = findViewById(R.id.latencyGraph)
        fpsGraph = findViewById(R.id.fpsGraph)
        recordButton = findViewById(R.id.record)
        recordInfo = findViewById(R.id.recordInfo)
        findViewById<TextView>(R.id.monitorTitle).text = about
        latencyGraph.configure(
            "ms", 20f,
            "total" to 0xFFFFFFFF.toInt(), "dec" to 0xFFFFB454.toInt(),
            "rede" to 0xFF7CFFB2.toInt(), "enc" to 0xFF4F8CFF.toInt(),
        )
        fpsGraph.configure("fps", 30f, "fps" to 0xFF7CFFB2.toInt(), "Mbps" to 0xFF4F8CFF.toInt())

        deviceMonitor = DeviceMonitor(applicationContext)
        recorder = SessionRecorder(applicationContext)
        recordButton.setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.share).setOnClickListener { shareLastSession() }
    }

    private fun applyHudMode() {
        hud.visibility = if (streaming && hudMode == HUD_COMPACT) View.VISIBLE else View.GONE
        monitor.visibility = if (streaming && hudMode == HUD_FULL) View.VISIBLE else View.GONE
    }

    private fun toggleRecording() {
        if (recorder.isRecording) {
            stopRecording()
            return
        }
        val info = client?.streamInfo
        val setup = listOf(
            "tablet: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · app $appVersion",
            "mac: ${info?.macName ?: "?"} · app ${info?.macVersion ?: "?"}",
            "stream: ${info?.width}x${info?.height} · ${info?.codec} · ${info?.fps} fps · painel ${refreshRate.roundToInt()} Hz",
        )
        if (recorder.start(setup)) {
            recordButton.text = getString(R.string.record_stop, "0:00")
            recordInfo.text = getString(R.string.record_hint)
        } else {
            recordInfo.text = getString(R.string.record_failed)
        }
    }

    private fun stopRecording() {
        val summary = recorder.stop() ?: return
        recordButton.text = getString(R.string.record_start)
        val where = summary.publicLocation ?: summary.file.absolutePath
        recordInfo.text = getString(R.string.record_saved, where) + "\n" + summary.text
    }

    /** Sends the most recent recording as text, so no file permission is needed. */
    private fun shareLastSession() {
        if (recorder.isRecording) stopRecording()
        val file = recorder.lastFile()
        if (file == null) {
            recordInfo.text = getString(R.string.record_none)
            return
        }
        val text = try {
            file.readText().take(MAX_SHARED_CHARS)
        } catch (e: Exception) {
            recordInfo.text = getString(R.string.record_failed)
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, file.name))
    }

    private fun format(pattern: String, vararg args: Any) = String.format(Locale.US, pattern, *args)
    private fun ms(value: Double) = if (value.isNaN()) "–" else format("%.1f", value)

    private fun showSample(stream: StreamSample) {
        val device = deviceMonitor.sample()
        if (recorder.isRecording) {
            recorder.append(stream, device)
            val seconds = recorder.elapsedSeconds
            recordButton.text = getString(R.string.record_stop, format("%d:%02d", seconds / 60, seconds % 60))
        }

        var compact = format(
            "%s · %d fps · %.1f Mbps · latência %s ms (enc %.1f · rede %.1f · dec %.1f)",
            stream.transport, stream.fps, stream.mbps, ms(stream.latencyAvgMs),
            stream.encodeMs, stream.networkMs, stream.decodeAvgMs,
        )
        if (stream.packetLossPct >= 0.1) compact += format(" · perda %.1f%%", stream.packetLossPct)
        if (recorder.isRecording) compact = "● $compact"
        hud.text = compact
        // Keep the history rolling even while the panel is hidden.
        latencyGraph.push(
            stream.latencyAvgMs.toFloat(), stream.decodeAvgMs.toFloat(),
            stream.networkMs.toFloat(), stream.encodeMs.toFloat(),
        )
        fpsGraph.push(stream.fps.toFloat(), stream.mbps.toFloat())
        if (hudMode != HUD_FULL) return

        latencyText.text = format(
            "Mac→tela  %s ms   (pico %s ms)\nencode %.1f · rede %.1f · decode %.1f (pico %.1f)",
            ms(stream.latencyAvgMs), ms(stream.latencyMaxMs),
            stream.encodeMs, stream.networkMs, stream.decodeAvgMs, stream.decodeMaxMs,
        )
        performanceText.text = format(
            "%s · %d fps · %.1f Mbps · perda %.1f%%\nquadros perdidos %d · keyframes %d\n" +
                "CPU %.0f%% · mem %.0f MB · bat %d%% %.1f°C · térmico %s",
            stream.transport, stream.fps, stream.mbps, stream.packetLossPct,
            stream.framesLost, stream.keyframeRequests,
            device.cpuPct, device.memoryMb, device.batteryPct, device.batteryTempC, device.thermal,
        )
    }

    override fun onDestroy() {
        if (recorder.isRecording) recorder.stop()
        super.onDestroy()
    }

    // endregion

    /** Runs the panel at its highest refresh rate: shorter scan-out, less latency. */
    private fun selectFastestDisplayMode() {
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: current
        refreshRate = best.refreshRate
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        discovery.start()
    }

    override fun onPause() {
        super.onPause()
        discovery.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    // region Surface lifecycle -> stream lifecycle

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Rotating between landscape and portrait resizes the surface a few
        // times; wait for it to settle, then (re)connect with the final size.
        handler.removeCallbacks(restartClient)
        if (client?.hasSize(width, height) == true) return
        handler.postDelayed(restartClient, if (client == null) 0L else ROTATION_SETTLE_MS)
    }

    private val restartClient = Runnable {
        val holder = surfaceView.holder
        val frame = holder.surfaceFrame
        val width = frame.width()
        val height = frame.height()
        if (!holder.surface.isValid || width <= 0 || height <= 0) return@Runnable
        if (client?.hasSize(width, height) == true) return@Runnable
        client?.stop()
        val metrics = resources.displayMetrics
        val physicalDpi = metrics.xdpi.roundToInt()
        val dpi = if (physicalDpi in 80..800) physicalDpi else metrics.densityDpi
        client = StreamClient(
            surface = holder.surface,
            width = width,
            height = height,
            densityDpi = dpi,
            refreshRate = refreshRate,
            deviceName = Build.MODEL ?: "Android",
            deviceId = deviceId,
            appVersion = appVersion,
            discovery = discovery,
            manualHost = { manualHost },
            listener = this,
        ).also { it.start() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        handler.removeCallbacks(restartClient)
        streaming = false
        client?.stop()
        client = null
    }

    // endregion

    // region StreamClient.Listener (background threads)

    override fun onState(state: StreamClient.State, detail: String) {
        runOnUiThread {
            streaming = state == StreamClient.State.STREAMING
            when (state) {
                StreamClient.State.STREAMING -> {
                    overlay.visibility = View.GONE
                    applyHudMode()
                }

                else -> {
                    overlay.visibility = View.VISIBLE
                    applyHudMode()
                    statusText.text = when (state) {
                        StreamClient.State.CONNECTING -> getString(R.string.status_connecting, detail)
                        StreamClient.State.WAITING -> getString(R.string.status_waiting_stream)
                        else -> getString(R.string.status_searching)
                    }
                }
            }
        }
    }

    override fun onSample(sample: StreamSample) {
        runOnUiThread { showSample(sample) }
    }

    // endregion

    // region Touch -> mouse
    //
    // One finger (or the stylus, or a mouse) is the mouse button. Two fingers
    // scroll or pinch to zoom, a two-finger tap is a right click and a
    // three-finger tap cycles the monitor. A finger press is held back for a
    // few milliseconds so that the start of a two-finger gesture is not
    // mistaken for a click; stylus and mouse are never delayed.

    private enum class TwoFinger { UNDECIDED, SCROLL, PINCH, DONE }

    private var touchSlop = 16f
    private var multiTouch = false
    private var twoFinger = TwoFinger.DONE
    private var pendingDown = false
    private var downSent = false
    private var moved = false
    private var maxPointers = 0
    private var gestureStart = 0L
    private var button = Protocol.BUTTON_LEFT
    private var downX = 0f
    private var downY = 0f
    private var downPressure = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var startCx = 0f
    private var startCy = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var startSpan = 0f
    private var lastSpan = 0f
    private val flushDown = Runnable { flushPendingDown() }

    private fun flushPendingDown() {
        if (!pendingDown) return
        pendingDown = false
        downSent = true
        send(Protocol.ACTION_DOWN, downX, downY, downPressure)
    }

    private fun surfaceWidth() = max(1, surfaceView.width).toFloat()
    private fun surfaceHeight() = max(1, surfaceView.height).toFloat()

    private fun send(action: Int, x: Float, y: Float, pressure: Float, button: Int = this.button) {
        client?.sendTouch(action, x / surfaceWidth(), y / surfaceHeight(), pressure, button)
    }

    private fun centroidX(event: MotionEvent) = (event.getX(0) + event.getX(1)) / 2
    private fun centroidY(event: MotionEvent) = (event.getY(0) + event.getY(1)) / 2
    private fun span(event: MotionEvent) =
        max(1f, hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)))

    private fun endPinch() {
        if (twoFinger == TwoFinger.PINCH) {
            client?.sendZoom(Protocol.ZOOM_END, 0f, lastCx / surfaceWidth(), lastCy / surfaceHeight())
        }
        twoFinger = TwoFinger.DONE
    }

    private fun onSurfaceTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                moved = false
                maxPointers = 1
                gestureStart = event.eventTime
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downPressure = event.pressure
                val tool = event.getToolType(0)
                val secondary = event.buttonState and
                    (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                button = if (secondary) Protocol.BUTTON_RIGHT else Protocol.BUTTON_LEFT
                if (tool != MotionEvent.TOOL_TYPE_FINGER) {
                    pendingDown = false
                    downSent = true
                    send(Protocol.ACTION_DOWN, downX, downY, downPressure)
                } else {
                    pendingDown = true
                    downSent = false
                    handler.postDelayed(flushDown, FINGER_DOWN_DELAY_MS)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, event.pointerCount)
                handler.removeCallbacks(flushDown)
                pendingDown = false
                if (downSent) {
                    downSent = false
                    send(Protocol.ACTION_UP, lastX, lastY, 0f)
                }
                if (!multiTouch) {
                    multiTouch = true
                    twoFinger = TwoFinger.UNDECIDED
                    startCx = centroidX(event)
                    startCy = centroidY(event)
                    startSpan = span(event)
                } else {
                    endPinch() // a third finger cancels scroll / pinch
                }
                lastCx = centroidX(event)
                lastCy = centroidY(event)
                lastSpan = span(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!multiTouch) {
                    if (pendingDown && hypot(event.x - downX, event.y - downY) > touchSlop) {
                        handler.removeCallbacks(flushDown)
                        flushPendingDown()
                    }
                    lastX = event.x
                    lastY = event.y
                    if (downSent) send(Protocol.ACTION_MOVE, event.x, event.y, event.pressure)
                } else if (event.pointerCount >= 2 && twoFinger != TwoFinger.DONE) {
                    val cx = centroidX(event)
                    val cy = centroidY(event)
                    val currentSpan = span(event)
                    val w = surfaceWidth()
                    val h = surfaceHeight()
                    if (twoFinger == TwoFinger.UNDECIDED) {
                        // Whichever happens first decides the gesture: fingers
                        // moving apart/together (pinch) or travelling together (scroll).
                        if (abs(currentSpan - startSpan) > touchSlop * 2) {
                            twoFinger = TwoFinger.PINCH
                            moved = true
                            lastSpan = currentSpan
                            client?.sendZoom(Protocol.ZOOM_BEGIN, 0f, cx / w, cy / h)
                        } else if (hypot(cx - startCx, cy - startCy) > touchSlop) {
                            twoFinger = TwoFinger.SCROLL
                            moved = true
                        }
                    }
                    when (twoFinger) {
                        TwoFinger.SCROLL -> client?.sendScroll((cx - lastCx) / w, (cy - lastCy) / h, cx / w, cy / h)
                        TwoFinger.PINCH -> {
                            client?.sendZoom(Protocol.ZOOM_CHANGE, currentSpan / lastSpan - 1f, cx / w, cy / h)
                            lastSpan = currentSpan
                        }

                        else -> {}
                    }
                    lastCx = cx
                    lastCy = cy
                }
            }

            MotionEvent.ACTION_POINTER_UP -> endPinch()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(flushDown)
                endPinch()
                val isTap = event.actionMasked == MotionEvent.ACTION_UP &&
                    !moved && event.eventTime - gestureStart < TAP_TIMEOUT_MS
                if (!multiTouch) {
                    flushPendingDown() // a quick tap: down and up back to back
                    if (downSent) send(Protocol.ACTION_UP, event.x, event.y, 0f)
                } else if (isTap && maxPointers == 2) {
                    send(Protocol.ACTION_DOWN, startCx, startCy, 1f, Protocol.BUTTON_RIGHT)
                    send(Protocol.ACTION_UP, startCx, startCy, 0f, Protocol.BUTTON_RIGHT)
                } else if (isTap && maxPointers == 3) {
                    // off -> compact line -> full monitor -> off
                    hudMode = (hudMode + 1) % 3
                    applyHudMode()
                }
                pendingDown = false
                downSent = false
                multiTouch = false
            }
        }
        return true
    }

    // endregion

    private companion object {
        const val PREF_HOST = "host"
        const val HUD_OFF = 0
        const val HUD_COMPACT = 1
        const val HUD_FULL = 2
        const val MAX_SHARED_CHARS = 200_000
        const val PREF_DEVICE_ID = "deviceId"
        const val ROTATION_SETTLE_MS = 350L
        /** Fraction of the screen scrolled per mouse wheel notch. */
        const val WHEEL_STEP = 0.06f
        const val FINGER_DOWN_DELAY_MS = 45L
        const val TAP_TIMEOUT_MS = 350L
    }
}
