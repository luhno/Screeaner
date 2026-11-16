package app.screeaner

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity(), SurfaceHolder.Callback, StreamClient.Listener {
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: View
    private lateinit var statusText: TextView
    private lateinit var statusLabel: TextView
    private lateinit var radar: RadarView
    private lateinit var latencyValue: TextView
    private lateinit var latencyPeak: TextView
    private lateinit var breakdownBar: SegmentBar
    private lateinit var encoderText: TextView
    private lateinit var testProgress: DotProgressView
    private lateinit var hud: TextView
    private lateinit var hostInput: EditText
    private lateinit var discovery: Discovery

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var client: StreamClient? = null
    /** Settings the benchmark asks of the Mac for the current stream; null = the Mac's own. */
    private var streamOverride: StreamOverride? = null
    @Volatile private var benchmark: Benchmark? = null
    private lateinit var benchmarkButton: Button
    /** Update versions already offered in this run: asked once, not on every reconnect. */
    private val updatesAsked = HashSet<Int>()
    /** The user accepted but first had to allow installs from this app in Settings. */
    private var updateAfterPermission = false
    private var updateDialog: AlertDialog? = null
    private var refreshRate = 60f
    private var hudMode = HUD_OFF
    private lateinit var monitor: View
    private lateinit var latencyText: TextView
    private lateinit var performanceText: TextView
    private lateinit var latencyGraph: GraphView
    private lateinit var recordButton: Button
    private lateinit var recordInfo: TextView
    private lateinit var probeButton: Button
    private lateinit var probeInfo: TextView
    private var probing = false
    private lateinit var deviceMonitor: DeviceMonitor
    private lateinit var recorder: SessionRecorder
    private var appVersion = "?"
    private var displayMode = MODE_VALUES[0]
    private var displayPosition = POSITION_VALUES[0]
    private val modeSpinners = ArrayList<Spinner>()
    private val positionSpinners = ArrayList<Spinner>()
    @Volatile private var manualHost: String? = null
    private var deviceId = ""
    private var streaming = false
    private lateinit var prefs: SharedPreferences
    private lateinit var trust: TrustStore
    private lateinit var shortcutBar: ShortcutBar
    private lateinit var sidebar: View
    private lateinit var clipboardBridge: ClipboardBridge
    private lateinit var pairingBox: View
    private lateinit var pairingCode: TextView
    private lateinit var pairingHint: TextView
    private lateinit var pairingConfirm: Button
    private lateinit var pairingCancel: Button
    private var pairingReply: ((Boolean) -> Unit)? = null
    private var pairingMac = "Mac"
    private var optAudio = false
    private var optClipboard = true
    private var optPalm = true
    private var optSidebar = false
    private var optCtrlIsCmd = false
    private var shortcuts: Set<String> = ShortcutBar.DEFAULT
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var overlayScroll: ScrollView
    private lateinit var noticeText: TextView
    private lateinit var updateButton: Button
    /** An update offered while streaming: shown as a button in the monitor instead of a dialog. */
    private var pendingOffer: StreamClient.UpdateOffer? = null
    /** Session files are written off the UI thread, in order. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    /** A stopped client still releasing the Surface; its successor waits for it. */
    private var retiring: StreamClient? = null
    private var clientGeneration = 0
    private var lastBackAt = 0L
    private var lastLayoutWidth = 0
    private var lastLayoutHeight = 0
    private val hideNotice = Runnable { noticeText.visibility = View.GONE }

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
        statusLabel = findViewById(R.id.statusLabel)
        radar = findViewById(R.id.radar)
        overlay.background = DotGridDrawable(this)
        hud = findViewById(R.id.hud)
        hostInput = findViewById(R.id.hostInput)
        discovery = Discovery(applicationContext)
        setUpMonitor()

        prefs = getPreferences(Context.MODE_PRIVATE)
        trust = TrustStore(applicationContext)
        manualHost = prefs.getString(PREF_HOST, null)
        // Stable identity: the Mac uses it to remember where this tablet's display sits.
        deviceId = prefs.getString(PREF_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(PREF_DEVICE_ID, it).apply()
        }
        hostInput.setText(manualHost ?: "")
        hudMode = prefs.getInt(PREF_HUD, HUD_OFF)
        savedInstanceState?.getIntArray(STATE_UPDATES_ASKED)?.let { asked -> updatesAsked += asked.toList() }
        setUpDisplayOptions(prefs)
        loadOptions()
        setUpPairing()
        setUpExtras()
        setUpResponsiveLayout()
        findViewById<Button>(R.id.connect).setOnClickListener { connectNow() }
        hostInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                connectNow()
                true
            } else {
                false
            }
        }

        selectFastestDisplayMode()
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        surfaceView.setOnTouchListener { _, event -> onSurfaceTouch(event) }
        surfaceView.setOnHoverListener { view, event ->
            updatePen(event)
            val x = event.x / max(1, view.width)
            val y = event.y / max(1, view.height)
            val isPen = currentTool != Protocol.TOOL_FINGER
            val action = when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> if (isPen) Protocol.ACTION_PROXIMITY_IN else Protocol.ACTION_HOVER
                MotionEvent.ACTION_HOVER_EXIT -> if (isPen) Protocol.ACTION_PROXIMITY_OUT else -1
                MotionEvent.ACTION_HOVER_MOVE -> Protocol.ACTION_HOVER
                else -> -1
            }
            if (isPen) penHovering = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
            if (action >= 0) {
                client?.sendTouch(action, x, y, 0f, Protocol.BUTTON_LEFT, tiltX, tiltY, currentTool)
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
        // Android batches input to the next vsync by default; deliver every
        // touch, stylus, hover and mouse sample the moment it arrives instead.
        if (Build.VERSION.SDK_INT >= 30) {
            surfaceView.requestUnbufferedDispatch(
                InputDevice.SOURCE_CLASS_POINTER or InputDevice.SOURCE_CLASS_POSITION,
            )
        }
    }

    /**
     * Keeps the Wi-Fi radio out of power save while streaming: power save
     * parks the radio between beacons and adds 50–100 ms spikes. Low-latency
     * mode (Android 10+) also tells the driver to favour latency over battery.
     */
    private fun holdWifi(hold: Boolean) {
        if (!hold) {
            wifiLock?.takeIf { it.isHeld }?.release()
            return
        }
        val lock = wifiLock ?: run {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifi.createWifiLock(mode, "screeaner:stream").apply { setReferenceCounted(false) }
                .also { wifiLock = it }
        }
        if (!lock.isHeld) lock.acquire()
    }

    /** A keyboard attached to the tablet types on the Mac while streaming. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val current = client
        if (current != null && streaming && !event.isSystem) {
            var code = event.keyCode
            if (optCtrlIsCmd) {
                // PC keyboards: Ctrl+C / Ctrl+V and friends behave like on the Mac.
                code = when (code) {
                    KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.KEYCODE_META_LEFT
                    KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.KEYCODE_META_RIGHT
                    KeyEvent.KEYCODE_META_LEFT -> KeyEvent.KEYCODE_CTRL_LEFT
                    KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.KEYCODE_CTRL_RIGHT
                    else -> code
                }
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> current.sendKey(0, code, event.repeatCount > 0, event.scanCode)
                KeyEvent.ACTION_UP -> current.sendKey(1, code, false, event.scanCode)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** "Conectar" (or Done on the keyboard): use the typed IP and try every Mac again right away. */
    private fun connectNow() {
        manualHost = hostInput.text.toString().trim()
        prefs.edit().putString(PREF_HOST, manualHost).apply()
        hideKeyboard()
        enterImmersive()
        client?.forgetFailures()
        statusLabel.text = getString(R.string.connecting_to, manualHost?.ifEmpty { null } ?: "USB · Wi‑Fi")
        if (client != null || retiring != null) reconnect() else handler.post(restartClient)
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(hostInput.windowToken, 0)
        hostInput.clearFocus()
    }

    /** Runs file work off the UI thread, in order; ignored once the activity is gone. */
    private fun background(work: () -> Unit) {
        if (io.isShutdown) return
        try {
            io.execute(work)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** Shows a message under the status (refusals, errors) for a while, in a readable size. */
    private fun showNotice(text: String) {
        handler.removeCallbacks(hideNotice)
        noticeText.text = text
        noticeText.visibility = View.VISIBLE
        handler.postDelayed(hideNotice, NOTICE_MS)
    }

    // region Responsive layout

    private fun setUpResponsiveLayout() {
        overlayScroll = findViewById(R.id.overlayScroll)
        noticeText = findViewById(R.id.notice)
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w != lastLayoutWidth || h != lastLayoutHeight) {
                lastLayoutWidth = w
                lastLayoutHeight = h
                // Not during layout: changing sizes here would request another one.
                handler.post { adaptLayout(w, h) }
            }
        }
        // Keyboard and camera cutout: pad what they would cover.
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
    }

    /** Fits the pages to the window: phones, small tablets, portrait and split screen. */
    private fun adaptLayout(widthPx: Int, heightPx: Int) {
        val density = resources.displayMetrics.density
        val widthDp = widthPx / density
        val heightDp = heightPx / density
        // The radar is decoration: it only stays when the text column keeps ~400dp.
        radar.visibility = if (widthDp >= 760) View.VISIBLE else View.GONE
        val side = ((if (widthDp >= 720) 56 else if (widthDp >= 480) 32 else 18) * density).toInt()
        val vertical = ((if (heightDp >= 640) 40 else 16) * density).toInt()
        overlay.setPadding(side, vertical, side, vertical)
        // The monitor is at most 408dp, and never wider than the window minus its margins.
        (monitor.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            val width = min((408 * density).toInt(), widthPx - params.leftMargin - params.rightMargin)
            if (params.width != width) {
                params.width = width
                monitor.layoutParams = params
            }
        }
    }

    private fun applyInsets(insets: WindowInsets) {
        val density = resources.displayMetrics.density
        var imeBottom = 0
        var cutLeft = 0
        var cutTop = 0
        var cutRight = 0
        if (Build.VERSION.SDK_INT >= 30) {
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            val cut = insets.getInsets(WindowInsets.Type.displayCutout())
            cutLeft = cut.left
            cutTop = cut.top
            cutRight = cut.right
        } else if (Build.VERSION.SDK_INT >= 28) {
            insets.displayCutout?.let {
                cutLeft = it.safeInsetLeft
                cutTop = it.safeInsetTop
                cutRight = it.safeInsetRight
            }
        }
        // Room to scroll the IP field above the keyboard.
        overlayScroll.setPadding(0, 0, 0, imeBottom)
        if (imeBottom > 0 && hostInput.hasFocus()) {
            overlayScroll.post { overlayScroll.smoothScrollTo(0, max(0, hostInput.bottom + (24 * density).toInt())) }
        }
        fun margins(view: View, base: Int, gravityEnd: Boolean) {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val px = (base * density).toInt()
            params.topMargin = px + cutTop
            if (gravityEnd) params.rightMargin = px + cutRight else params.leftMargin = px + cutLeft
            view.layoutParams = params
        }
        margins(hud, 12, gravityEnd = false)
        margins(monitor, 16, gravityEnd = true)
        (sidebar.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.leftMargin = (8 * density).toInt() + cutLeft
            sidebar.layoutParams = it
        }
    }

    // endregion

    // region Options, pairing, shortcuts and clipboard

    private fun loadOptions() {
        optAudio = prefs.getBoolean(PREF_AUDIO, false)
        optClipboard = prefs.getBoolean(PREF_CLIPBOARD, true)
        optPalm = prefs.getBoolean(PREF_PALM, true)
        optSidebar = prefs.getBoolean(PREF_SIDEBAR, false)
        optCtrlIsCmd = prefs.getBoolean(PREF_CTRL_CMD, false)
        shortcuts = prefs.getStringSet(PREF_SHORTCUTS, null)?.toSet() ?: ShortcutBar.DEFAULT
    }

    private fun setUpPairing() {
        pairingBox = findViewById(R.id.pairingBox)
        pairingCode = findViewById(R.id.pairingCode)
        pairingHint = findViewById(R.id.pairingHint)
        pairingConfirm = findViewById(R.id.pairingConfirm)
        pairingCancel = findViewById(R.id.pairingCancel)
        pairingConfirm.setOnClickListener { answerPairing(true) }
        pairingCancel.setOnClickListener { answerPairing(false) }
    }

    private fun answerPairing(accepted: Boolean) {
        val reply = pairingReply ?: return
        pairingReply = null
        pairingConfirm.visibility = View.GONE
        pairingCancel.visibility = View.GONE
        if (accepted) pairingHint.text = getString(R.string.pairing_waiting, pairingMac)
        reply(accepted)
    }

    private fun setUpExtras() {
        sidebar = findViewById(R.id.sidebarScroll)
        shortcutBar = ShortcutBar(findViewById<LinearLayout>(R.id.sidebar)) { action, code ->
            client?.sendKey(action, code, false)
        }
        shortcutBar.build(shortcuts)
        clipboardBridge = ClipboardBridge(this) { text -> if (optClipboard) client?.sendClipboard(text) }
        findViewById<Button>(R.id.options).setOnClickListener { showOptions() }
        findViewById<Button>(R.id.monitorOptions).setOnClickListener { showOptions() }
        findViewById<Button>(R.id.monitorClose).setOnClickListener { setHudMode(HUD_OFF) }
        updateButton = findViewById(R.id.updateButton)
        updateButton.setOnClickListener { pendingOffer?.let { showUpdateDialog(it) } }
    }

    private fun setHudMode(mode: Int) {
        hudMode = mode
        prefs.edit().putInt(PREF_HUD, mode).apply()
        applyHudMode()
    }

    private fun applySidebar() {
        val visible = streaming && optSidebar
        sidebar.visibility = if (visible) View.VISIBLE else View.GONE
        // A latched ⌘ must never stay down on the Mac with no button left to release it.
        if (!visible) shortcutBar.releaseAll()
    }

    private fun showOptions() {
        val values = booleanArrayOf(optAudio, optClipboard, optPalm, optSidebar, optCtrlIsCmd)
        AlertDialog.Builder(this)
            .setTitle(R.string.options_title)
            .setMultiChoiceItems(R.array.option_labels, values) { _, index, checked -> values[index] = checked }
            .setPositiveButton(R.string.ok) { _, _ ->
                val reconnect = values[0] != optAudio || values[1] != optClipboard
                optAudio = values[0]
                optClipboard = values[1]
                optPalm = values[2]
                optSidebar = values[3]
                optCtrlIsCmd = values[4]
                prefs.edit()
                    .putBoolean(PREF_AUDIO, optAudio).putBoolean(PREF_CLIPBOARD, optClipboard)
                    .putBoolean(PREF_PALM, optPalm).putBoolean(PREF_SIDEBAR, optSidebar)
                    .putBoolean(PREF_CTRL_CMD, optCtrlIsCmd)
                    .apply()
                applySidebar()
                // Audio and clipboard are agreed when connecting.
                if (reconnect) reconnect()
            }
            .setNeutralButton(R.string.options_shortcuts) { _, _ -> showShortcuts() }
            .setNegativeButton(R.string.options_forget) { _, _ -> confirmForget() }
            .show()
    }

    private fun showShortcuts() {
        val all = ShortcutBar.ALL
        val values = BooleanArray(all.size) { all[it].id in shortcuts }
        AlertDialog.Builder(this)
            .setTitle(R.string.shortcuts_title)
            .setMultiChoiceItems(R.array.shortcut_labels, values) { _, index, checked -> values[index] = checked }
            .setPositiveButton(R.string.ok) { _, _ ->
                shortcuts = all.filterIndexed { index, _ -> values[index] }.map { it.id }.toSet()
                prefs.edit().putStringSet(PREF_SHORTCUTS, shortcuts).apply()
                shortcutBar.build(shortcuts)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmForget() {
        val known = trust.trustedMacs().joinToString(", ").ifEmpty { getString(R.string.forget_none) }
        AlertDialog.Builder(this)
            .setTitle(R.string.forget_title)
            .setMessage(getString(R.string.forget_message, known))
            .setPositiveButton(R.string.options_forget) { _, _ ->
                trust.forgetMacs()
                reconnect()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Drops the connection and starts over with the current settings. */
    private fun reconnect() {
        if (client == null && retiring == null) return
        handler.removeCallbacks(restartClient)
        startClient(force = true)
    }

    // endregion

    // region Display mode (extension / main display / mirror)

    private fun setUpDisplayOptions(prefs: SharedPreferences) {
        displayMode = prefs.getString(PREF_MODE, null)?.takeIf { it in MODE_VALUES } ?: MODE_VALUES[0]
        displayPosition = prefs.getString(PREF_POSITION, null)?.takeIf { it in POSITION_VALUES } ?: POSITION_VALUES[0]
        modeSpinners += listOf(findViewById<Spinner>(R.id.modeSpinner), findViewById(R.id.monitorModeSpinner))
        positionSpinners += listOf(findViewById<Spinner>(R.id.positionSpinner), findViewById(R.id.monitorPositionSpinner))

        fun attach(spinner: Spinner, items: Int, onPick: (Int) -> Unit) {
            spinner.adapter = ArrayAdapter.createFromResource(this, items, android.R.layout.simple_spinner_item).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, index: Int, id: Long) = onPick(index)
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
        }
        modeSpinners.forEach { spinner ->
            attach(spinner, R.array.display_modes) { changeDisplay(MODE_VALUES[it], displayPosition, prefs) }
        }
        positionSpinners.forEach { spinner ->
            attach(spinner, R.array.display_positions) { changeDisplay(displayMode, POSITION_VALUES[it], prefs) }
        }
        syncDisplaySpinners()
    }

    private fun syncDisplaySpinners() {
        modeSpinners.forEach { it.setSelection(MODE_VALUES.indexOf(displayMode)) }
        positionSpinners.forEach {
            it.setSelection(POSITION_VALUES.indexOf(displayPosition))
            it.isEnabled = displayMode != "mirror" // a mirror has no position
            it.alpha = if (it.isEnabled) 1f else 0.4f
        }
    }

    /** Saves the choice and, if connected, reconnects so the Mac applies it. */
    private fun changeDisplay(mode: String, position: String, prefs: SharedPreferences) {
        // Spinners also report the selection we set ourselves: ignore no-ops.
        if (mode == displayMode && position == displayPosition) return
        displayMode = mode
        displayPosition = position
        prefs.edit().putString(PREF_MODE, mode).putString(PREF_POSITION, position).apply()
        syncDisplaySpinners()
        reconnect()
    }

    // endregion

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
        findViewById<TextView>(R.id.version).text = "$about   ·   ${getString(R.string.footer_gestures)}"

        monitor = findViewById(R.id.monitor)
        latencyText = findViewById(R.id.latencyText)
        performanceText = findViewById(R.id.performanceText)
        latencyGraph = findViewById(R.id.latencyGraph)
        latencyValue = findViewById(R.id.latencyValue)
        latencyPeak = findViewById(R.id.latencyPeak)
        breakdownBar = findViewById(R.id.breakdownBar)
        encoderText = findViewById(R.id.encoderText)
        testProgress = findViewById(R.id.testProgress)
        recordButton = findViewById(R.id.record)
        recordInfo = findViewById(R.id.recordInfo)
        findViewById<TextView>(R.id.monitorTitle).text = getString(R.string.monitor_title, appVersion, Build.MODEL ?: "")
        // Red dots mark seconds clearly worse than a good stream (over ~2 frames at 60 Hz).
        latencyGraph.configure("ms", 20f, alertAbove = 36f)

        deviceMonitor = DeviceMonitor(applicationContext)
        recorder = SessionRecorder(applicationContext)
        recordButton.setOnClickListener { toggleRecording() }
        probeButton = findViewById(R.id.probe)
        probeInfo = findViewById(R.id.probeInfo)
        probeButton.setOnClickListener { toggleLatencyTest() }
        benchmarkButton = findViewById(R.id.benchmark)
        benchmarkButton.setOnClickListener { toggleBenchmark() }
        findViewById<View>(R.id.share).setOnClickListener { shareLastSession() }
    }

    private fun applyHudMode() {
        hud.visibility = if (streaming && hudMode == HUD_COMPACT) View.VISIBLE else View.GONE
        monitor.visibility = if (streaming && hudMode == HUD_FULL) View.VISIBLE else View.GONE
    }

    private fun toggleLatencyTest() {
        if (benchmark != null) {
            probeInfo.text = getString(R.string.benchmark_busy)
            return
        }
        val current = client
        if (probing) {
            current?.cancelLatencyTest()
            return
        }
        val started = current?.startLatencyTest(object : LatencyTest.Listener {
            override fun onProgress(done: Int, total: Int, lastMs: Double) = runOnUiThread {
                probeButton.text = getString(R.string.probe_running, done, total)
                testProgress.set(1, total, done)
                probeInfo.text = getString(R.string.probe_last, format("%.1f", lastMs))
            }

            override fun onFinished(result: LatencyTest.Result) = runOnUiThread { showLatencyResult(result) }
        }) ?: false
        if (started) {
            probing = true
            probeButton.text = getString(R.string.probe_running, 0, LatencyTest.SAMPLES)
            probeInfo.text = getString(R.string.probe_started)
            testProgress.set(1, LatencyTest.SAMPLES, 0)
            testProgress.visibility = View.VISIBLE
        } else {
            probeInfo.text = getString(R.string.probe_unavailable)
        }
    }

    private fun showLatencyResult(result: LatencyTest.Result) {
        probing = false
        probeButton.text = getString(R.string.probe_start)
        testProgress.visibility = View.GONE
        val lines = result.lines()
        val problem = when (result.outcome) {
            LatencyTest.Outcome.NO_MAC -> getString(R.string.probe_no_mac)
            LatencyTest.Outcome.NO_SQUARE -> getString(R.string.probe_no_square)
            LatencyTest.Outcome.CANCELLED -> getString(R.string.probe_cancelled)
            LatencyTest.Outcome.DONE -> null
        }
        probeInfo.text = (lines + listOfNotNull(problem)).joinToString("\n")
        if (lines.isNotEmpty()) background { recorder.note(lines) }
    }

    private fun toggleBenchmark() {
        benchmark?.let {
            it.cancel()
            return
        }
        val info = client?.streamInfo
        if (!streaming || info == null || probing) {
            probeInfo.text = getString(R.string.probe_unavailable)
            return
        }
        val configs = Benchmark.configsFor(VideoDecoder.supportedCodecs(info.width, info.height), refreshRate.roundToInt())
        val setup = listOf(
            "tablet: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · app $appVersion",
            "mac: ${info.macName} · app ${info.macVersion}",
            "stream: ${info.width}x${info.height} · painel ${refreshRate.roundToInt()} Hz",
        )
        val run = Benchmark(
            host = object : Benchmark.Host {
                override fun restartStream(override: StreamOverride?) = runOnUiThread {
                    streamOverride = override
                    startClient(force = true)
                }

                override fun currentClient(): StreamClient? = client
            },
            configs = configs,
            listener = object : Benchmark.Listener {
                override fun onStep(text: String) = runOnUiThread { probeInfo.text = text }
                override fun onProgress(done: Int, total: Int) = runOnUiThread {
                    testProgress.set(configs.size, Benchmark.PHASES, done)
                }
                override fun onFinished(report: Benchmark.Report) = runOnUiThread { showBenchmark(report, setup) }
            },
        )
        benchmark = run
        benchmarkButton.text = getString(R.string.benchmark_stop)
        probeInfo.text = getString(R.string.benchmark_started)
        testProgress.set(configs.size, Benchmark.PHASES, 0)
        testProgress.visibility = View.VISIBLE
        run.start()
    }

    private fun showBenchmark(report: Benchmark.Report, setup: List<String>) {
        benchmark = null
        benchmarkButton.text = getString(R.string.benchmark_start)
        testProgress.visibility = View.GONE
        val lines = report.lines()
        probeInfo.text = lines.joinToString("\n")
        background {
            val saved = if (report.results.isNotEmpty()) recorder.saveReport(setup, report.csv(), lines) else null
            if (recorder.isRecording) recorder.note(lines)
            if (saved != null) {
                runOnUiThread {
                    probeInfo.text = (lines + getString(R.string.benchmark_saved, saved)).joinToString("\n")
                }
            }
        }
    }

    private fun toggleRecording() {
        if (recorder.isRecording) {
            stopRecording()
            return
        }
        val info = client?.streamInfo
        val stream = if (info == null) {
            getString(R.string.record_no_stream)
        } else {
            "${info.width}x${info.height} · ${info.describe()}"
        }
        val setup = listOf(
            "tablet: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · app $appVersion",
            "mac: ${info?.macName ?: "?"} · app ${info?.macVersion ?: "?"}",
            "exibição: ${info?.mode ?: displayMode} · posição $displayPosition",
            "stream: $stream · painel ${refreshRate.roundToInt()} Hz",
        )
        recordButton.isEnabled = false
        background {
            val started = recorder.start(setup)
            runOnUiThread {
                recordButton.isEnabled = true
                if (started) {
                    recordButton.text = getString(R.string.record_stop, "0:00")
                    recordInfo.text = getString(R.string.record_hint)
                } else {
                    recordInfo.text = getString(R.string.record_failed)
                }
            }
        }
    }

    private fun stopRecording(then: (() -> Unit)? = null) {
        recordButton.text = getString(R.string.record_start)
        background {
            val summary = recorder.stop()
            runOnUiThread {
                if (summary != null) {
                    val where = summary.publicLocation ?: summary.file.absolutePath
                    recordInfo.text = getString(R.string.record_saved, where) + "\n" + summary.text
                }
                then?.invoke()
            }
        }
    }

    /**
     * Sends the most recent recording (or benchmark) as text, so no file
     * permission is needed. Long sessions keep the header and the summary.
     */
    private fun shareLastSession() {
        if (recorder.isRecording) {
            stopRecording { shareLastSession() }
            return
        }
        background {
            val file = recorder.lastFile()
            val text = try {
                file?.let { recorder.shareText(it) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (file == null || text == null) {
                    recordInfo.text = getString(if (file == null) R.string.record_none else R.string.record_failed)
                    return@runOnUiThread
                }
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, file.name)
                    .putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, file.name))
            }
        }
    }

    private fun format(pattern: String, vararg args: Any) = String.format(Locale.US, pattern, *args)
    private fun ms(value: Double) = if (value.isNaN()) "–" else format("%.1f", value)

    private fun showSample(stream: StreamSample) {
        val recording = recorder.isRecording
        // Battery and thermal readings are binder calls: only when someone looks or records.
        val device = if (recording || hudMode == HUD_FULL) deviceMonitor.sample() else null
        if (recording && device != null) {
            background { recorder.append(stream, device) }
            val seconds = recorder.elapsedSeconds
            recordButton.text = getString(R.string.record_stop, format("%d:%02d", seconds / 60, seconds % 60))
        }
        // Keep the history rolling even while the panel is hidden.
        latencyGraph.push(stream.latencyAvgMs.toFloat())

        if (hudMode == HUD_COMPACT) {
            var compact = format(
                "%s   %d fps   %.1f Mbps   %s ms",
                stream.transport, stream.fps, stream.mbps, ms(stream.latencyAvgMs),
            )
            if (stream.packetLossPct >= 0.1) compact += "   " + getString(R.string.hud_loss, format("%.1f", stream.packetLossPct))
            if (hud.text.toString() != compact) hud.text = compact
            // The dot turns red while a session is being recorded.
            val dot = if (recording) R.drawable.dot_signal else R.drawable.dot_ink
            if (hud.tag != dot) {
                hud.tag = dot
                hud.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
            }
        }
        if (hudMode != HUD_FULL || device == null) return

        latencyValue.text = ms(stream.latencyAvgMs)
        latencyPeak.text = getString(R.string.monitor_peak, ms(stream.latencyMaxMs))
        breakdownBar.set(
            floatArrayOf(
                stream.encodeMs.toFloat(), stream.networkMs.toFloat(),
                stream.transferAvgMs.toFloat(), stream.decodeAvgMs.toFloat(),
            ),
            intArrayOf(Dot.SIGNAL, Dot.ink(0.45f), Dot.ink(0.7f), Dot.INK),
        )
        // Large font settings: columns would wrap, so use a flowing line instead.
        val largeText = resources.configuration.fontScale > 1.15f
        val parts = arrayOf(
            format("%.1f", stream.encodeMs), format("%.1f", stream.networkMs),
            format("%.1f", stream.transferAvgMs), format("%.1f", stream.decodeAvgMs),
        )
        latencyText.text = getString(if (largeText) R.string.monitor_breakdown else R.string.monitor_breakdown_wide, *parts)
        val info = client?.streamInfo
        if (info != null && info.lowLatencyEncoder == false) {
            encoderText.text = getString(R.string.encoder_normal, info.describe().substringBefore(" · encoder"))
            encoderText.visibility = View.VISIBLE
        } else {
            encoderText.visibility = View.GONE
        }
        val headers = getString(R.string.monitor_perf_headers).split('|')
        val values = listOf(
            stream.fps.toString(), format("%.1f", stream.mbps), format("%.2f%%", stream.packetLossPct),
            format("%.0f%%", device.cpuPct), format("%.0f MB", device.memoryMb),
            format("%d%% %.1f°", device.batteryPct, device.batteryTempC),
        )
        val table = if (largeText) {
            headers.zip(values).joinToString("\n") { (h, v) -> "$h  $v" }
        } else {
            format("%-9s %-9s %s\n%-9s %-9s %s\n%-9s %-9s %s\n%-9s %-9s %s",
                headers[0], headers[1], headers[2], values[0], values[1], values[2],
                headers[3], headers[4], headers[5], values[3], values[4], values[5])
        }
        performanceText.text = table + "\n" + getString(
            R.string.monitor_perf_footer, stream.transport, info?.describe() ?: "", stream.framesLost, stream.keyframeRequests,
        ) + " · " + device.thermal
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(STATE_UPDATES_ASKED, updatesAsked.toIntArray())
    }

    /** Back while streaming would end it: ask for a second press first. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (streaming) {
            val now = SystemClock.uptimeMillis()
            if (now - lastBackAt > BACK_CONFIRM_MS) {
                lastBackAt = now
                Toast.makeText(this, R.string.back_again, Toast.LENGTH_SHORT).show()
                return
            }
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        holdWifi(false)
        handler.removeCallbacksAndMessages(null)
        updateDialog?.dismiss()
        updateDialog = null
        background { if (recorder.isRecording) recorder.stop() }
        io.shutdown() // lets the queued work, including that final stop, finish
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
        if (updateAfterPermission && UpdateInstaller.canInstall(this)) {
            updateAfterPermission = false
            client?.requestUpdate()
        }
        enterImmersive()
        discovery.start()
        clipboardBridge.start()
    }

    override fun onPause() {
        super.onPause()
        discovery.stop()
        clipboardBridge.stop()
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
        handler.removeCallbacks(resizeClient)
        val current = client ?: run {
            handler.post(restartClient)
            return
        }
        if (current.hasSize(width, height)) return
        // Already connected (or connecting): the Mac resizes the display in
        // place, keeping the pairing, the connection and the windows.
        handler.postDelayed(resizeClient, ROTATION_SETTLE_MS)
    }

    private val resizeClient = Runnable {
        val frame = surfaceView.holder.surfaceFrame
        if (frame.width() > 0 && frame.height() > 0) client?.resize(frame.width(), frame.height())
    }

    private val restartClient = Runnable { startClient(force = false) }

    /**
     * (Re)connects with the surface's current size; [force] restarts even if
     * the size did not change. The old client is stopped without blocking the
     * UI; the new one starts once the old decoder has let go of the Surface.
     */
    private fun startClient(force: Boolean) {
        val holder = surfaceView.holder
        val frame = holder.surfaceFrame
        if (!holder.surface.isValid || frame.width() <= 0 || frame.height() <= 0) return
        if (!force && client?.hasSize(frame.width(), frame.height()) == true) return
        val old = client
        if (old != null) {
            shortcutBar.releaseAll() // while the old client can still send the key-ups
            old.stop()
            retiring = old
        }
        client = null
        pairingReply = null
        streaming = false
        val generation = ++clientGeneration
        val waitFor = retiring
        if (waitFor == null) {
            createClient()
            return
        }
        Thread({
            waitFor.awaitStopped()
            handler.post {
                if (retiring === waitFor) retiring = null
                if (generation == clientGeneration && client == null && !isFinishing) createClient()
            }
        }, "screeaner-restart").start()
    }

    private fun createClient() {
        val holder = surfaceView.holder
        val frame = holder.surfaceFrame
        val width = frame.width()
        val height = frame.height()
        if (!holder.surface.isValid || width <= 0 || height <= 0) return
        val metrics = resources.displayMetrics
        val physicalDpi = metrics.xdpi.roundToInt()
        val dpi = if (physicalDpi in 80..800) physicalDpi else metrics.densityDpi
        client = StreamClient(
            surface = holder.surface,
            width = width,
            height = height,
            densityDpi = dpi,
            refreshRate = refreshRate,
            options = ClientOptions(
                deviceName = Build.MODEL ?: "Android",
                deviceId = deviceId,
                appVersion = appVersion,
                displayMode = displayMode,
                displayPosition = displayPosition,
                audio = optAudio,
                clipboard = optClipboard,
            ),
            trust = trust,
            discovery = discovery,
            manualHost = { manualHost },
            rawListener = this,
            override = streamOverride,
            updateDir = File(cacheDir, "updates"),
        ).also { it.start() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        handler.removeCallbacks(restartClient)
        handler.removeCallbacks(resizeClient)
        benchmark?.cancel()
        shortcutBar.releaseAll()
        streaming = false
        holdWifi(false)
        clientGeneration++
        // The decoder must stop drawing on the Surface before this returns.
        client?.stop(blocking = true)
        retiring?.awaitStopped()
        retiring = null
        client = null
    }

    // endregion

    // region StreamClient.Listener (background threads)

    override fun onState(state: StreamClient.State, detail: String) {
        runOnUiThread {
            streaming = state == StreamClient.State.STREAMING
            if (state != StreamClient.State.PAIRING) {
                pairingBox.visibility = View.GONE
                pairingReply = null
            }
            // A late callback from a client stopped in surfaceDestroyed must not re-take the lock.
            holdWifi(streaming && client != null)
            when (state) {
                StreamClient.State.STREAMING -> {
                    overlay.visibility = View.GONE
                    // Otherwise the keyboard covers the stream and swallows what is typed.
                    hideKeyboard()
                    handler.removeCallbacks(hideNotice)
                    noticeText.visibility = View.GONE
                }

                else -> {
                    overlay.visibility = View.VISIBLE
                    statusText.text = when (state) {
                        StreamClient.State.CONNECTING -> getString(R.string.status_connecting)
                        StreamClient.State.PAIRING -> getString(R.string.status_pairing, detail)
                        StreamClient.State.WAITING -> getString(R.string.status_waiting_stream)
                        else -> getString(R.string.status_searching)
                    }
                    statusLabel.text = when (state) {
                        StreamClient.State.CONNECTING -> getString(R.string.label_connecting, detail)
                        StreamClient.State.PAIRING -> getString(R.string.label_pairing)
                        StreamClient.State.WAITING -> getString(R.string.label_waiting)
                        else -> getString(R.string.label_searching)
                    }
                    radar.searching = state == StreamClient.State.SEARCHING
                }
            }
            applyHudMode()
            applySidebar()
        }
    }

    override fun onPairingCode(code: String, macName: String, reply: ((Boolean) -> Unit)?) {
        runOnUiThread {
            pairingMac = macName
            pairingReply = reply
            pairingBox.visibility = View.VISIBLE
            pairingCode.text = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code
            val ask = reply != null
            pairingHint.text = getString(if (ask) R.string.pairing_compare else R.string.pairing_waiting, macName)
            pairingConfirm.visibility = if (ask) View.VISIBLE else View.GONE
            pairingCancel.visibility = if (ask) View.VISIBLE else View.GONE
            // On short screens the code and its buttons may be below the fold.
            overlayScroll.post { overlayScroll.smoothScrollTo(0, max(0, pairingBox.top - (16 * resources.displayMetrics.density).toInt())) }
        }
    }

    override fun onDenied(reason: StreamClient.DenyReason, detail: String) {
        runOnUiThread {
            pairingBox.visibility = View.GONE
            pairingReply = null
            showNotice(
                when (reason) {
                    StreamClient.DenyReason.MAC_REFUSED ->
                        if (detail.isBlank()) getString(R.string.denied_by_mac_generic) else getString(R.string.denied_by_mac, detail)
                    StreamClient.DenyReason.MAC_TOO_OLD -> getString(R.string.denied_mac_too_old, appVersion.substringBefore(' '))
                    StreamClient.DenyReason.CANCELLED_HERE -> getString(R.string.denied_cancelled)
                }
            )
        }
    }

    override fun onClipboard(text: String) {
        runOnUiThread { if (optClipboard) clipboardBridge.apply(text) }
    }

    override fun onUpdateOffer(offer: StreamClient.UpdateOffer) {
        runOnUiThread {
            // Back from allowing installs: the stream reconnected, so the offer is new; just take it.
            if (updateAfterPermission && UpdateInstaller.canInstall(this)) {
                updateAfterPermission = false
                client?.requestUpdate()
                return@runOnUiThread
            }
            if (!updatesAsked.add(offer.versionCode) || benchmark != null || isFinishing) return@runOnUiThread
            if (streaming) {
                // No modal dialog over a live stream: a button in the monitor and a short note.
                pendingOffer = offer
                updateButton.text = getString(R.string.update_button, offer.versionName)
                updateButton.visibility = View.VISIBLE
                Toast.makeText(this, getString(R.string.update_available_toast, offer.versionName), Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            showUpdateDialog(offer)
        }
    }

    private fun showUpdateDialog(offer: StreamClient.UpdateOffer) {
        run {
            updateDialog?.dismiss()
            updateDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_title, offer.versionName))
                .setMessage(getString(R.string.update_message, appVersion, offer.size / 1024))
                .setPositiveButton(R.string.update_accept) { _, _ ->
                    pendingOffer = null
                    updateButton.visibility = View.GONE
                    if (UpdateInstaller.canInstall(this)) {
                        client?.requestUpdate()
                    } else {
                        updateAfterPermission = true
                        Toast.makeText(this, R.string.update_permission, Toast.LENGTH_LONG).show()
                        UpdateInstaller.openPermissionSettings(this)
                    }
                }
                .setNegativeButton(R.string.update_later, null)
                .show()
        }
    }

    override fun onUpdateDownloaded(apk: File, offer: StreamClient.UpdateOffer) {
        // Copying several MB into the installer session must not block the UI.
        background {
            try {
                UpdateInstaller.install(this, apk)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.update_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UpdateInstaller.ACTION_RESULT) {
            UpdateInstaller.handleResult(this, intent)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onSample(sample: StreamSample) {
        benchmark?.onSample(sample)
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

    // Pen state: tool, tilt and when it was last seen (palm rejection).
    private var currentTool = Protocol.TOOL_FINGER
    private var tiltX = 0f
    private var tiltY = 0f
    private var penHovering = false
    private var lastPenAt = 0L
    private var ignoringPalm = false
    /** The pen pointer followed while a palm is being ignored, or -1. */
    private var penPointerId = -1

    private fun penUnderPalm(event: MotionEvent) {
        val action = event.actionMasked
        val index = event.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val tool = event.getToolType(index)
                if (penPointerId == -1 && (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER)) {
                    penPointerId = event.getPointerId(index)
                    updatePenAt(event, index)
                    button = Protocol.BUTTON_LEFT
                    send(Protocol.ACTION_DOWN, event.getX(index), event.getY(index), event.getPressure(index))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(penPointerId)
                if (penPointerId != -1 && i >= 0) {
                    updatePenAt(event, i)
                    send(Protocol.ACTION_MOVE, event.getX(i), event.getY(i), event.getPressure(i))
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (penPointerId == -1) return
                if (action == MotionEvent.ACTION_CANCEL || event.getPointerId(index) == penPointerId) {
                    val i = event.findPointerIndex(penPointerId).coerceAtLeast(0)
                    send(Protocol.ACTION_UP, event.getX(i), event.getY(i), 0f)
                    penPointerId = -1
                }
            }
        }
    }

    /** Reads the tool and tilt of [event]'s first pointer. */
    private fun updatePen(event: MotionEvent) = updatePenAt(event, 0)

    /** Reads the tool and tilt of pointer [index]. */
    private fun updatePenAt(event: MotionEvent, index: Int) {
        currentTool = when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS -> Protocol.TOOL_PEN
            MotionEvent.TOOL_TYPE_ERASER -> Protocol.TOOL_ERASER
            else -> Protocol.TOOL_FINGER
        }
        if (currentTool == Protocol.TOOL_FINGER) {
            tiltX = 0f
            tiltY = 0f
            return
        }
        lastPenAt = SystemClock.uptimeMillis()
        // Android: tilt = angle from perpendicular, orientation = direction it leans.
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT, index)
        val orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index)
        tiltX = (sin(tilt) * sin(orientation)).coerceIn(-1f, 1f)
        tiltY = (-sin(tilt) * cos(orientation)).coerceIn(-1f, 1f)
    }

    /** A finger right after (or while) the pen is around is the hand resting on the screen. */
    private fun isPalm(event: MotionEvent): Boolean =
        optPalm && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
            (penHovering || SystemClock.uptimeMillis() - lastPenAt < PALM_WINDOW_MS)

    private fun flushPendingDown() {
        if (!pendingDown) return
        pendingDown = false
        downSent = true
        send(Protocol.ACTION_DOWN, downX, downY, downPressure)
    }

    private fun surfaceWidth() = max(1, surfaceView.width).toFloat()
    private fun surfaceHeight() = max(1, surfaceView.height).toFloat()

    private fun send(action: Int, x: Float, y: Float, pressure: Float, button: Int = this.button) {
        client?.sendTouch(action, x / surfaceWidth(), y / surfaceHeight(), pressure, button, tiltX, tiltY, currentTool)
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isPalm(event)) ignoringPalm = true
        if (ignoringPalm) {
            // The resting hand is ignored, but a pen that lands next to it still draws.
            penUnderPalm(event)
            val end = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
            if (end) {
                ignoringPalm = false
                penPointerId = -1
            }
            return true
        }
        updatePen(event)
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
                if (Build.VERSION.SDK_INT < 30) surfaceView.requestUnbufferedDispatch(event)
                if (tool != MotionEvent.TOOL_TYPE_FINGER) {
                    pendingDown = false
                    downSent = true
                    send(Protocol.ACTION_DOWN, downX, downY, downPressure)
                } else {
                    pendingDown = true
                    downSent = false
                    // The click waits a moment in case a second finger lands,
                    // but the cursor can jump under the finger right away.
                    send(Protocol.ACTION_HOVER, downX, downY, 0f)
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
                    setHudMode((hudMode + 1) % 3)
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
        const val PREF_AUDIO = "optAudio"
        const val PREF_CLIPBOARD = "optClipboard"
        const val PREF_PALM = "optPalm"
        const val PREF_SIDEBAR = "optSidebar"
        const val PREF_CTRL_CMD = "optCtrlIsCmd"
        const val PREF_SHORTCUTS = "shortcuts"
        const val PALM_WINDOW_MS = 600L
        const val PREF_MODE = "displayMode"
        const val PREF_POSITION = "displayPosition"
        /** Wire values, in the same order as R.array.display_modes / display_positions. */
        val MODE_VALUES = listOf("extend", "main", "mirror", "only")
        val POSITION_VALUES = listOf("auto", "left", "right", "above", "below")
        const val HUD_OFF = 0
        const val HUD_COMPACT = 1
        const val HUD_FULL = 2
        const val PREF_HUD = "hudMode"
        const val STATE_UPDATES_ASKED = "updatesAsked"
        const val NOTICE_MS = 15_000L
        const val BACK_CONFIRM_MS = 2_000L
        const val PREF_DEVICE_ID = "deviceId"
        const val ROTATION_SETTLE_MS = 350L
        /** Fraction of the screen scrolled per mouse wheel notch. */
        const val WHEEL_STEP = 0.06f
        const val FINGER_DOWN_DELAY_MS = 45L
        const val TAP_TIMEOUT_MS = 350L
    }
}
