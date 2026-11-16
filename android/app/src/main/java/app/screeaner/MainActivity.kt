package app.screeaner

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.RectF
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
    private lateinit var probe: LatencyProbe
    private lateinit var measureButton: Button
    private lateinit var pairingBox: View
    private lateinit var pairingCode: TextView
    private lateinit var pairingHint: TextView
    private lateinit var pairingConfirm: Button
    private lateinit var pairingCancel: Button
    private var pairingReply: ((Boolean) -> Unit)? = null
    private var pairingMac = "Mac"
    private var deniedMessage: String? = null
    private var deniedAt = 0L
    private var optAudio = false
    private var optClipboard = true
    private var optPalm = true
    private var optSidebar = false
    private var optCtrlIsCmd = false
    private var shortcuts: Set<String> = ShortcutBar.DEFAULT

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

        prefs = getPreferences(Context.MODE_PRIVATE)
        trust = TrustStore(applicationContext)
        manualHost = prefs.getString(PREF_HOST, null)
        // Stable identity: the Mac uses it to remember where this tablet's display sits.
        deviceId = prefs.getString(PREF_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(PREF_DEVICE_ID, it).apply()
        }
        hostInput.setText(manualHost ?: "")
        setUpDisplayOptions(prefs)
        loadOptions()
        setUpPairing()
        setUpExtras()
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

    // region Options, pairing, shortcuts, clipboard and end-to-end probe

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
        probe = LatencyProbe(surfaceView) { client }
        measureButton = findViewById(R.id.measure)
        measureButton.setOnClickListener { runProbe() }
        findViewById<Button>(R.id.options).setOnClickListener { showOptions() }
        findViewById<Button>(R.id.monitorOptions).setOnClickListener { showOptions() }
    }

    private fun applySidebar() {
        sidebar.visibility = if (streaming && optSidebar) View.VISIBLE else View.GONE
        if (!streaming) shortcutBar.releaseAll()
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
        if (client == null) return
        handler.removeCallbacks(restartClient)
        client?.stop()
        client = null
        restartClient.run()
    }

    private fun runProbe() {
        measureButton.isEnabled = false
        recordInfo.text = getString(R.string.measuring)
        probe.run { result ->
            measureButton.isEnabled = true
            if (result == null || result.samples.isEmpty()) {
                recordInfo.text = getString(R.string.measure_failed)
                return@run
            }
            val text = getString(
                R.string.measure_result, format("%.1f", result.average), format("%.1f", result.minimum),
                format("%.1f", result.p95), result.samples.size, result.attempts,
            )
            recordInfo.text = text
            recorder.note(text)
        }
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
            "exibição: ${info?.mode ?: displayMode} · posição $displayPosition",
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
        probe.release()
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

    private val restartClient = Runnable {
        val holder = surfaceView.holder
        val frame = holder.surfaceFrame
        val width = frame.width()
        val height = frame.height()
        if (!holder.surface.isValid || width <= 0 || height <= 0) return@Runnable
        if (client?.hasSize(width, height) == true) return@Runnable
        client?.stop()
        pairingReply = null
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
            listener = this,
        ).also { it.start() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        handler.removeCallbacks(restartClient)
        handler.removeCallbacks(resizeClient)
        streaming = false
        client?.stop()
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
            when (state) {
                StreamClient.State.STREAMING -> {
                    overlay.visibility = View.GONE
                    deniedMessage = null
                }

                else -> {
                    overlay.visibility = View.VISIBLE
                    var text = when (state) {
                        StreamClient.State.CONNECTING -> getString(R.string.status_connecting, detail)
                        StreamClient.State.PAIRING -> getString(R.string.status_pairing, detail)
                        StreamClient.State.WAITING -> getString(R.string.status_waiting_stream)
                        else -> getString(R.string.status_searching)
                    }
                    // Keep a refusal visible for a while instead of overwriting it at once.
                    val denied = deniedMessage
                    if (denied != null && SystemClock.uptimeMillis() - deniedAt < 15_000) text = "$denied\n$text"
                    statusText.text = text
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
        }
    }

    override fun onDenied(reason: String) {
        runOnUiThread {
            pairingBox.visibility = View.GONE
            pairingReply = null
            deniedMessage = getString(R.string.pairing_denied, reason)
            deniedAt = SystemClock.uptimeMillis()
            statusText.text = deniedMessage
        }
    }

    override fun onClipboard(text: String) {
        runOnUiThread { if (optClipboard) clipboardBridge.apply(text) }
    }

    override fun onProbeInfo(area: RectF) = probe.onProbeInfo(area)

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

    // Pen state: tool, tilt and when it was last seen (palm rejection).
    private var currentTool = Protocol.TOOL_FINGER
    private var tiltX = 0f
    private var tiltY = 0f
    private var penHovering = false
    private var lastPenAt = 0L
    private var ignoringPalm = false

    /** Reads the tool and tilt of [event]'s first pointer. */
    private fun updatePen(event: MotionEvent) {
        currentTool = when (event.getToolType(0)) {
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
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
        val orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
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
            val end = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
            if (end) ignoringPalm = false
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
        const val MAX_SHARED_CHARS = 200_000
        const val PREF_DEVICE_ID = "deviceId"
        const val ROTATION_SETTLE_MS = 350L
        /** Fraction of the screen scrolled per mouse wheel notch. */
        const val WHEEL_STEP = 0.06f
        const val FINGER_DOWN_DELAY_MS = 45L
        const val TAP_TIMEOUT_MS = 350L
    }
}
