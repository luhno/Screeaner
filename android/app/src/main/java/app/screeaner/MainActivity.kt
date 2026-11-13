package app.screeaner

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var showHud = false
    @Volatile private var manualHost: String? = null

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

        val prefs = getPreferences(Context.MODE_PRIVATE)
        manualHost = prefs.getString(PREF_HOST, null)
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
        surfaceView.holder.addCallback(this)
    }

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
        if (width < height) return // still rotating into landscape
        if (client?.sameSize?.invoke(width, height) == true) return
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
            discovery = discovery,
            manualHost = { manualHost },
            listener = this,
        ).also { it.start() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        client?.stop()
        client = null
    }

    // endregion

    // region StreamClient.Listener (background threads)

    override fun onState(state: StreamClient.State, detail: String) {
        runOnUiThread {
            when (state) {
                StreamClient.State.STREAMING -> {
                    overlay.visibility = View.GONE
                    hud.visibility = if (showHud) View.VISIBLE else View.GONE
                }

                else -> {
                    overlay.visibility = View.VISIBLE
                    hud.visibility = View.GONE
                    statusText.text = when (state) {
                        StreamClient.State.CONNECTING -> getString(R.string.status_connecting, detail)
                        StreamClient.State.WAITING -> getString(R.string.status_waiting_stream)
                        else -> getString(R.string.status_searching)
                    }
                }
            }
        }
    }

    override fun onStats(text: String) {
        runOnUiThread { hud.text = text }
    }

    // endregion

    // region Touch -> mouse
    //
    // One finger (or the stylus) is the mouse button. Two fingers scroll, a
    // two-finger tap is a right click and a three-finger tap toggles the stats.
    // A finger press is held back for a few milliseconds so that the start of
    // a two-finger gesture is not mistaken for a click; the stylus is never delayed.

    private var touchSlop = 16f
    private var multiTouch = false
    private var pendingDown = false
    private var downSent = false
    private var moved = false
    private var maxPointers = 0
    private var gestureStart = 0L
    private var downX = 0f
    private var downY = 0f
    private var downPressure = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var startCx = 0f
    private var startCy = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private val flushDown = Runnable { flushPendingDown() }

    private fun flushPendingDown() {
        if (!pendingDown) return
        pendingDown = false
        downSent = true
        send(Protocol.ACTION_DOWN, downX, downY, downPressure)
    }

    private fun send(action: Int, x: Float, y: Float, pressure: Float, button: Int = Protocol.BUTTON_LEFT) {
        val w = max(1, surfaceView.width).toFloat()
        val h = max(1, surfaceView.height).toFloat()
        client?.sendTouch(action, x / w, y / h, pressure, button)
    }

    private fun centroidX(event: MotionEvent) = (event.getX(0) + event.getX(1)) / 2
    private fun centroidY(event: MotionEvent) = (event.getY(0) + event.getY(1)) / 2

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
                val precise = event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER
                if (precise) {
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
                    startCx = centroidX(event)
                    startCy = centroidY(event)
                }
                lastCx = centroidX(event)
                lastCy = centroidY(event)
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
                } else if (event.pointerCount >= 2) {
                    val cx = centroidX(event)
                    val cy = centroidY(event)
                    if (!moved && hypot(cx - startCx, cy - startCy) > touchSlop) moved = true
                    if (moved && maxPointers == 2) {
                        val w = max(1, surfaceView.width).toFloat()
                        val h = max(1, surfaceView.height).toFloat()
                        client?.sendScroll((cx - lastCx) / w, (cy - lastCy) / h, cx / w, cy / h)
                    }
                    lastCx = cx
                    lastCy = cy
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(flushDown)
                val isTap = event.actionMasked == MotionEvent.ACTION_UP &&
                    !moved && event.eventTime - gestureStart < TAP_TIMEOUT_MS
                if (!multiTouch) {
                    flushPendingDown() // a quick tap: down and up back to back
                    if (downSent) send(Protocol.ACTION_UP, event.x, event.y, 0f)
                } else if (isTap && maxPointers == 2) {
                    send(Protocol.ACTION_DOWN, startCx, startCy, 1f, Protocol.BUTTON_RIGHT)
                    send(Protocol.ACTION_UP, startCx, startCy, 0f, Protocol.BUTTON_RIGHT)
                } else if (isTap && maxPointers == 3) {
                    showHud = !showHud
                    hud.visibility = if (showHud) View.VISIBLE else View.GONE
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
        const val FINGER_DOWN_DELAY_MS = 45L
        const val TAP_TIMEOUT_MS = 350L
    }
}
