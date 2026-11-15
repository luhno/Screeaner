package app.screeaner

import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-click benchmark: streams each configuration in turn (codec, frame rate,
 * per-frame size cap), and for each one measures
 *
 * - **motion**: per-frame latency and its breakdown while the Mac shows the
 *   same moving pattern every time, so runs are comparable;
 * - **touch**: the [LatencyTest] round trip on a still screen.
 *
 * Every configuration is a fresh stream, restarted with a [StreamOverride],
 * so the Mac rebuilds its encoder exactly as it would from the menu. The
 * normal stream comes back at the end.
 */
class Benchmark(
    private val host: Host,
    private val configs: List<StreamOverride>,
    private val listener: Listener,
) {
    interface Host {
        /** Restarts the stream with these settings (null = the Mac's own). Any thread. */
        fun restartStream(override: StreamOverride?)
        fun currentClient(): StreamClient?
    }

    /** Called on the benchmark thread. */
    interface Listener {
        fun onStep(text: String)
        fun onFinished(report: Report)
    }

    @Volatile private var running = true
    @Volatile private var collecting = false
    private val samples = ArrayList<StreamSample>() // guarded by itself
    @Volatile private var activeTest: StreamClient? = null
    private val thread = Thread(::run, "screeaner-benchmark")

    fun start() = thread.start()

    fun cancel() {
        running = false
        activeTest?.cancelLatencyTest()
        thread.interrupt()
    }

    /** Fed with every per-second sample of the current stream. */
    fun onSample(sample: StreamSample) {
        if (!collecting) return
        synchronized(samples) { samples += sample }
    }

    private fun run() {
        val results = ArrayList<ConfigResult>()
        try {
            for ((index, config) in configs.withIndex()) {
                if (!running) break
                val step = "${index + 1}/${configs.size} · ${config.label}"
                results += measure(config, step) ?: break
            }
        } finally {
            collecting = false
            host.currentClient()?.setTestMotion(false)
            host.restartStream(null)
            listener.onFinished(Report(results, cancelled = !running))
        }
    }

    /** Null when cancelled. */
    private fun measure(config: StreamOverride, step: String): ConfigResult? {
        listener.onStep("$step · conectando…")
        val previous = host.currentClient()
        host.restartStream(config)
        val client = waitForStream(previous) ?: return if (running) ConfigResult.failed(config) else null
        val info = client.streamInfo ?: return ConfigResult.failed(config)

        listener.onStep("$step · aquecendo…")
        if (!pause(WARMUP_MS)) return null

        listener.onStep("$step · movimento…")
        client.setTestMotion(true)
        if (!pause(MOTION_SETTLE_MS)) return null
        synchronized(samples) { samples.clear() }
        collecting = true
        if (!pause(MOTION_MS)) return null
        collecting = false
        client.setTestMotion(false)
        val motion = synchronized(samples) { samples.toList() }
        if (!pause(STILL_SETTLE_MS)) return null

        listener.onStep("$step · toque→tela…")
        val done = CountDownLatch(1)
        var touch: LatencyTest.Result? = null
        activeTest = client
        val started = client.startLatencyTest(object : LatencyTest.Listener {
            override fun onProgress(done: Int, total: Int, lastMs: Double) =
                listener.onStep(String.format(Locale.US, "%s · toque→tela %d/%d", step, done, total))

            override fun onFinished(result: LatencyTest.Result) {
                touch = result
                done.countDown()
            }
        }, TOUCH_SAMPLES)
        if (started) {
            try {
                done.await(TOUCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        activeTest = null
        if (!running) return null
        return ConfigResult(config, info, motion, touch)
    }

    /** Waits for a new stream (not [previous]) to be up, with its clock synced. */
    private fun waitForStream(previous: StreamClient?): StreamClient? {
        val deadline = System.nanoTime() + CONNECT_TIMEOUT_MS * 1_000_000
        while (running && System.nanoTime() < deadline) {
            val client = host.currentClient()
            if (client != null && client !== previous && client.isStreaming && client.streamInfo != null) return client
            if (!pause(100)) return null
        }
        return null
    }

    /** False when cancelled. */
    private fun pause(millis: Long): Boolean {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
        return running
    }

    class ConfigResult(
        val config: StreamOverride,
        val info: StreamInfo?,
        val motion: List<StreamSample>,
        val touch: LatencyTest.Result?,
    ) {
        val ok get() = info != null

        private fun mean(pick: (StreamSample) -> Double): Double {
            val values = motion.map(pick).filter { !it.isNaN() }
            return if (values.isEmpty()) Double.NaN else values.average()
        }

        val motionLatencyMs get() = mean { it.latencyAvgMs }
        val motionPeakMs get() = mean { it.latencyMaxMs }
        val encodeMs get() = mean { it.encodeMs }
        val networkMs get() = mean { it.networkMs }
        val transferMs get() = mean { it.transferAvgMs }
        val decodeMs get() = mean { it.decodeAvgMs }
        val fps get() = mean { it.fps.toDouble() }
        val mbps get() = mean { it.mbps }
        val touchP50Ms get() = LatencyTest.percentile(touch?.roundTripMs.orEmpty(), 0.5)
        val touchP95Ms get() = LatencyTest.percentile(touch?.roundTripMs.orEmpty(), 0.95)
        val touchToMacMs get() = touch?.toMacMs?.takeIf { it.isNotEmpty() }?.average() ?: Double.NaN
        val touchToScreenMs get() = touch?.toScreenMs?.takeIf { it.isNotEmpty() }?.average() ?: Double.NaN

        companion object {
            fun failed(config: StreamOverride) = ConfigResult(config, null, emptyList(), null)
        }
    }

    class Report(val results: List<ConfigResult>, val cancelled: Boolean) {
        /** The configuration with the lowest touch p50, or else the lowest motion latency. */
        val best: ConfigResult?
            get() {
                val measured = results.filter { it.ok }
                return measured.filter { !it.touchP50Ms.isNaN() }.minByOrNull { it.touchP50Ms }
                    ?: measured.filter { !it.motionLatencyMs.isNaN() }.minByOrNull { it.motionLatencyMs }
            }

        /** One line per configuration, for the screen and the file summary. */
        fun lines(): List<String> {
            val lines = results.map { r ->
                if (!r.ok) return@map "${r.config.label}: não conectou"
                val mode = when (r.info?.lowLatencyEncoder) {
                    true -> "LL"
                    false -> "normal"
                    null -> "?"
                }
                String.format(
                    Locale.US,
                    "%s [%s]: toque p50 %s / p95 %s · mov %s ms (enc %s · transf %s · dec %s) · %s Mbps",
                    r.config.label, mode, num(r.touchP50Ms), num(r.touchP95Ms), num(r.motionLatencyMs),
                    num(r.encodeMs), num(r.transferMs), num(r.decodeMs), num(r.mbps),
                )
            }.toMutableList()
            best?.let { lines += "melhor: ${it.config.label} (toque p50 ${num(it.touchP50Ms)} ms)" }
            if (cancelled) lines += "benchmark interrompido"
            return lines
        }

        fun csv(): List<String> = listOf(CSV_HEADER) + results.map { r ->
            listOf(
                r.config.label, r.info?.codec ?: r.config.codec, (r.info?.fps ?: r.config.fps).toString(),
                r.info?.lowLatencyEncoder?.toString() ?: "", r.info?.frameCap?.toString() ?: r.config.frameCap.toString(),
                r.ok.toString(), csv(r.motionLatencyMs), csv(r.motionPeakMs), csv(r.encodeMs), csv(r.networkMs),
                csv(r.transferMs), csv(r.decodeMs), csv(r.fps), csv(r.mbps), csv(r.touchP50Ms), csv(r.touchP95Ms),
                csv(r.touchToMacMs), csv(r.touchToScreenMs), (r.touch?.roundTripMs?.size ?: 0).toString(),
            ).joinToString(",")
        }

        private fun num(value: Double) = if (value.isNaN()) "–" else String.format(Locale.US, "%.1f", value)
        private fun csv(value: Double) = if (value.isNaN()) "" else String.format(Locale.US, "%.2f", value)

        companion object {
            const val CSV_HEADER = "config,codec,fps,low_latency_encoder,frame_cap,connected," +
                "motion_latency_ms,motion_peak_ms,encode_ms,network_ms,transfer_ms,decode_ms,fps_measured,mbps," +
                "touch_p50_ms,touch_p95_ms,touch_to_mac_ms,touch_to_screen_ms,touch_samples"
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L
        /** Clock sync (first pongs) and the rate controller settle. */
        private const val WARMUP_MS = 3_000L
        private const val MOTION_SETTLE_MS = 1_500L
        private const val MOTION_MS = 8_000L
        private const val STILL_SETTLE_MS = 1_000L
        const val TOUCH_SAMPLES = 20
        private const val TOUCH_TIMEOUT_MS = 60_000L

        /**
         * The matrix: both codecs at 60 fps with and without the per-frame cap,
         * then the panel's high refresh rate when it has one.
         */
        fun configsFor(codecs: List<String>, panelHz: Int): List<StreamOverride> {
            val list = ArrayList<StreamOverride>()
            val hevc = "hevc" in codecs
            if (hevc) {
                list += StreamOverride("HEVC 60", "hevc", 60, frameCap = true)
                list += StreamOverride("HEVC 60 sem limite", "hevc", 60, frameCap = false)
            }
            list += StreamOverride("H.264 60", "h264", 60, frameCap = true)
            list += StreamOverride("H.264 60 sem limite", "h264", 60, frameCap = false)
            if (panelHz >= 90) {
                val high = minOf(panelHz, 120)
                list += StreamOverride("H.264 $high", "h264", high, frameCap = true)
                if (hevc) list += StreamOverride("HEVC $high", "hevc", high, frameCap = true)
            }
            return list
        }
    }
}
