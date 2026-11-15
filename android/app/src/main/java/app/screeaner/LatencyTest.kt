package app.screeaner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.Surface
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Measures the real touch-to-screen round trip, without a camera.
 *
 * The tablet asks the Mac to paint a square in the middle of its virtual
 * display black or white and starts a stopwatch. That request travels like a
 * touch; the change then goes through the whole video path (WindowServer,
 * ScreenCaptureKit, encoder, network, decoder). Every decoded frame is
 * sampled with [PixelCopy] until the new colour shows up. The round trip uses
 * only the tablet's clock; with the clocks synced through the ping, the Mac's
 * acknowledgement also splits it into "tablet → Mac" and "Mac → screen".
 *
 * Four small regions inside the square are sampled. A first calibration round
 * finds the ones that really follow the square (the Mac cursor may sit on one
 * of them), and only those are used afterwards.
 */
class LatencyTest(
    private val surface: Surface,
    frameWidth: Int,
    frameHeight: Int,
    private val send: (state: Int, seq: Int) -> Unit,
    private val clockOffsetMicros: () -> Long,
    private val listener: Listener,
    private val samples: Int = SAMPLES,
) {
    /** Both called on the test's own thread. */
    interface Listener {
        fun onProgress(done: Int, total: Int, lastMs: Double)
        fun onFinished(result: Result)
    }

    enum class Outcome { DONE, NO_MAC, NO_SQUARE, CANCELLED }

    class Result(
        val outcome: Outcome,
        val roundTripMs: List<Double>,
        val toMacMs: List<Double>,
        val toScreenMs: List<Double>,
        val timeouts: Int,
    ) {
        /** Human-readable lines, also written to the test session file. */
        fun lines(): List<String> {
            if (roundTripMs.isEmpty()) return emptyList()
            val lines = ArrayList<String>()
            lines += "toque→tela (ida e volta): " + describe(roundTripMs) + " · ${roundTripMs.size} amostras" +
                (if (timeouts > 0) " · $timeouts sem resposta" else "")
            if (toMacMs.isNotEmpty() && toScreenMs.isNotEmpty()) {
                lines += String.format(
                    Locale.US, "ida tablet→Mac %.1f ms · volta Mac→tela %.1f ms (médias)",
                    toMacMs.average(), toScreenMs.average(),
                )
            }
            return lines
        }
    }

    private data class Sample(val startNanos: Long, val detectedNanos: Long, val ackMacMicros: Long, val mask: Int)

    @Volatile private var running = true
    private val thread = Thread(::run, "screeaner-latency-test")
    private val copyThread = HandlerThread("screeaner-probe-copy")
    private lateinit var copyHandler: Handler

    // Only touched on the copy thread.
    private val bitmap = Bitmap.createBitmap(PATCH, PATCH, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(PATCH * PATCH)
    private val source = Rect(
        frameWidth / 2 - PATCH / 2, frameHeight / 2 - PATCH / 2,
        frameWidth / 2 + PATCH / 2, frameHeight / 2 + PATCH / 2,
    )

    private val lock = Any()
    // Guarded by lock
    private var waiting = false
    private var probeStartNanos = 0L
    private var seq = 0
    private var expectWhite = false
    private var regionMask = ALL_REGIONS
    private var matchAny = false
    private var latch: CountDownLatch? = null
    private var detectedNanos = 0L
    private var detectedMask = 0
    private var ackMacMicros = NO_ACK
    private var anyAck = false
    private var copyInFlight = false
    private var copyFrameNanos = 0L
    private var pendingFrameNanos = 0L

    fun start() {
        copyThread.start()
        copyHandler = Handler(copyThread.looper)
        thread.start()
    }

    fun cancel() {
        running = false
        thread.interrupt()
    }

    /** Decoder output thread: a frame was just handed to the display. */
    fun onFrameRendered(nanos: Long) {
        synchronized(lock) {
            if (!waiting) return
            if (copyInFlight) {
                pendingFrameNanos = nanos
                return
            }
            copyInFlight = true
            copyFrameNanos = nanos
        }
        requestCopy()
    }

    /** Reader thread: the Mac painted the square and tells when, on its clock. */
    fun onAck(ackSeq: Int, macMicros: Long) {
        synchronized(lock) {
            anyAck = true
            if (ackSeq == seq) ackMacMicros = macMicros
        }
    }

    private fun requestCopy() {
        try {
            PixelCopy.request(surface, source, bitmap, ::onCopied, copyHandler)
        } catch (e: IllegalArgumentException) {
            // Surface gone (rotation or teardown): the sample will time out.
            synchronized(lock) { copyInFlight = false }
        }
    }

    private fun onCopied(result: Int) {
        var again = false
        synchronized(lock) {
            // A copy asked for before this probe started cannot count for it.
            if (result == PixelCopy.SUCCESS && waiting && copyFrameNanos >= probeStartNanos) {
                val matched = matchingRegions(expectWhite) and regionMask
                val hit = if (matchAny) matched != 0 else matched == regionMask
                if (hit) {
                    waiting = false
                    detectedNanos = copyFrameNanos
                    detectedMask = matched
                    latch?.countDown()
                }
            }
            copyInFlight = false
            if (waiting && pendingFrameNanos != 0L) {
                copyFrameNanos = pendingFrameNanos
                copyInFlight = true
                again = true
            }
            pendingFrameNanos = 0L
        }
        if (again) requestCopy()
    }

    /** Bit i set when region i of the patch shows the expected colour. */
    private fun matchingRegions(white: Boolean): Int {
        bitmap.getPixels(pixels, 0, PATCH, 0, 0, PATCH, PATCH)
        var mask = 0
        for ((i, corner) in REGIONS.withIndex()) {
            val luma = regionLuma(corner.first, corner.second)
            if (if (white) luma > BRIGHT else luma < DARK) mask = mask or (1 shl i)
        }
        return mask
    }

    private fun regionLuma(x0: Int, y0: Int): Int {
        var sum = 0
        for (y in y0 until y0 + REGION) for (x in x0 until x0 + REGION) {
            val c = pixels[y * PATCH + x]
            sum += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        return sum / (REGION * REGION)
    }

    /** Asks for one colour and waits until it is on screen. Null on timeout or cancel. */
    private fun probe(white: Boolean, mask: Int, any: Boolean): Sample? {
        val done = CountDownLatch(1)
        val id: Int
        val start: Long
        synchronized(lock) {
            id = ++seq
            start = System.nanoTime()
            probeStartNanos = start
            expectWhite = white
            regionMask = mask
            matchAny = any
            latch = done
            ackMacMicros = NO_ACK
            detectedNanos = 0L
            pendingFrameNanos = 0L
            waiting = true
        }
        send(if (white) Protocol.PROBE_WHITE else Protocol.PROBE_BLACK, id)
        val seen = try {
            done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        synchronized(lock) { waiting = false }
        if (!seen) return null
        // The acknowledgement leaves the Mac before the frame does, so it is
        // normally here already; give it a moment if not.
        var ack = synchronized(lock) { ackMacMicros }
        val deadline = System.nanoTime() + ACK_GRACE_NANOS
        while (ack == NO_ACK && System.nanoTime() < deadline && running) {
            pause(5)
            ack = synchronized(lock) { ackMacMicros }
        }
        return synchronized(lock) { Sample(start, detectedNanos, ack, detectedMask) }
    }

    private fun run() {
        val roundTrips = ArrayList<Double>()
        val toMac = ArrayList<Double>()
        val toScreen = ArrayList<Double>()
        var timeouts = 0
        var outcome = Outcome.DONE
        try {
            // Calibration: regions that turn white and then black follow the
            // square. The first black may not change anything on a dark
            // background, so it is allowed to time out.
            probe(white = false, mask = ALL_REGIONS, any = true)
            val bright = if (running) probe(white = true, mask = ALL_REGIONS, any = true) else null
            val dark = bright?.let { probe(white = false, mask = it.mask, any = true) }
            if (!running) {
                outcome = Outcome.CANCELLED
                return
            }
            if (dark == null) {
                outcome = if (synchronized(lock) { anyAck }) Outcome.NO_SQUARE else Outcome.NO_MAC
                return
            }
            val usable = dark.mask

            var white = true
            var misses = 0
            for (i in 1..samples) {
                if (!running) {
                    outcome = Outcome.CANCELLED
                    break
                }
                // A random gap so samples land at every phase of the refresh cycle.
                pause(Random.nextLong(GAP_MIN_MS, GAP_MAX_MS))
                if (!running) {
                    outcome = Outcome.CANCELLED
                    break
                }
                val sample = probe(white, usable, any = false)
                if (sample == null) {
                    if (!running) {
                        outcome = Outcome.CANCELLED
                        break
                    }
                    timeouts++
                    if (++misses >= MAX_MISSES) break
                    // The square's colour is unknown now: resync to black before going on.
                    if (probe(white = false, mask = usable, any = false) == null) break
                    white = true
                    continue
                }
                misses = 0
                white = !white
                val total = (sample.detectedNanos - sample.startNanos) / 1e6
                roundTrips += total
                val offset = clockOffsetMicros()
                if (offset != Long.MIN_VALUE && sample.ackMacMicros != NO_ACK) {
                    val startMac = sample.startNanos / 1000 + offset
                    val shownMac = sample.detectedNanos / 1000 + offset
                    val up = (sample.ackMacMicros - startMac) / 1e3
                    val down = (shownMac - sample.ackMacMicros) / 1e3
                    // Clock sync is only good to about half a ping; skip nonsense.
                    if (up >= 0 && down >= 0) {
                        toMac += up
                        toScreen += down
                    }
                }
                listener.onProgress(i, samples, total)
            }
        } finally {
            send(Protocol.PROBE_HIDDEN, synchronized(lock) { ++seq })
            copyThread.quitSafely()
            listener.onFinished(Result(outcome, roundTrips, toMac, toScreen, timeouts))
        }
    }

    private fun pause(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        const val SAMPLES = 30
        private const val TIMEOUT_MS = 1500L
        private const val ACK_GRACE_NANOS = 200_000_000L
        private const val GAP_MIN_MS = 60L
        private const val GAP_MAX_MS = 160L
        private const val MAX_MISSES = 4
        private const val NO_ACK = Long.MIN_VALUE

        /**
         * Sampled patch around the centre of the frame, in video pixels. The
         * Mac square is 160 points (160 or 320 pixels), so the patch always
         * sits inside it.
         */
        private const val PATCH = 96
        private const val REGION = 8
        /** Four regions about 40 px from the centre: the cursor can hide one or two, not all. */
        private val REGIONS = listOf(4 to 4, PATCH - 4 - REGION to 4, 4 to PATCH - 4 - REGION,
            PATCH - 4 - REGION to PATCH - 4 - REGION)
        private const val ALL_REGIONS = 0b1111
        private const val BRIGHT = 170
        private const val DARK = 70

        /** Nearest-rank percentile (p in 0..1); NaN for an empty list. */
        fun percentile(values: List<Double>, p: Double): Double {
            if (values.isEmpty()) return Double.NaN
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * p).toInt()]
        }

        /** "média · p50 · p95 · mín · máx" for a list of milliseconds. */
        fun describe(values: List<Double>): String {
            if (values.isEmpty()) return "–"
            return String.format(
                Locale.US, "média %.1f ms · p50 %.1f · p95 %.1f · mín %.1f · máx %.1f",
                values.average(), percentile(values, 0.5), percentile(values, 0.95),
                values.minOrNull()!!, values.maxOrNull()!!,
            )
        }
    }
}
