package app.screeaner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * End-to-end ("toque → imagem") latency test: the tablet asks the Mac to
 * flip a small square between black and white and times how long the flip
 * takes to show up in its own SurfaceView. That covers the whole loop:
 * network both ways, the Mac drawing, capture, encode, decode and compositing
 * (everything but the panel's own scan-out). Each sample is a little high by
 * the time one screen read-back takes (a few ms).
 */
class LatencyProbe(
    private val surfaceView: SurfaceView,
    private val client: () -> StreamClient?,
) {
    class Result(val samples: List<Double>, val attempts: Int) {
        val average get() = samples.average()
        val minimum get() = samples.minOrNull() ?: Double.NaN
        val p95 get() = samples.sorted().let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * 0.95).toInt()] }
    }

    private val copyThread = HandlerThread("screeaner-probe").apply { start() }
    private val copyHandler = Handler(copyThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    @Volatile private var area: RectF? = null
    @Volatile private var areaLatch: CountDownLatch? = null

    /** The Mac told us where its marker is (normalized to our screen). */
    fun onProbeInfo(rect: RectF) {
        area = rect
        areaLatch?.countDown()
    }

    /** Runs [rounds] probes off the UI thread; [done] gets null if the test could not run. */
    fun run(rounds: Int = 20, done: (Result?) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        Thread({
            val result = try {
                measure(rounds)
            } catch (_: Exception) {
                null
            }
            running.set(false)
            main.post { done(result) }
        }, "screeaner-probe-run").start()
    }

    private fun measure(rounds: Int): Result? {
        val stream = client() ?: return null
        val latch = CountDownLatch(1)
        areaLatch = latch
        area = null
        stream.sendProbe(0)
        if (!latch.await(2, TimeUnit.SECONDS)) return null
        val marker = area ?: return null
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0) return null
        // Read only the middle of the marker, away from its edges.
        val source = Rect(
            ((marker.left + marker.width() * 0.3f) * w).toInt(),
            ((marker.top + marker.height() * 0.3f) * h).toInt(),
            ((marker.right - marker.width() * 0.3f) * w).toInt().coerceAtLeast(1),
            ((marker.bottom - marker.height() * 0.3f) * h).toInt().coerceAtLeast(1),
        )
        if (source.width() <= 0 || source.height() <= 0) return null
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        Thread.sleep(400) // let the marker reach the screen

        val samples = ArrayList<Double>()
        for (i in 1..rounds) {
            val lit = i % 2 == 1
            val start = System.nanoTime()
            stream.sendProbe(i)
            while (System.nanoTime() - start < 1_000_000_000L) {
                val luma = luminance(source, bitmap) ?: break
                if ((lit && luma > 170) || (!lit && luma < 80)) {
                    samples += (System.nanoTime() - start) / 1e6
                    break
                }
            }
            Thread.sleep(150)
        }
        bitmap.recycle()
        return Result(samples, rounds)
    }

    /** Average brightness (0-255) of [source] on screen, or null if the read-back failed. */
    private fun luminance(source: Rect, bitmap: Bitmap): Double? {
        val done = CountDownLatch(1)
        var ok = false
        PixelCopy.request(surfaceView, source, bitmap, { result ->
            ok = result == PixelCopy.SUCCESS
            done.countDown()
        }, copyHandler)
        if (!done.await(500, TimeUnit.MILLISECONDS) || !ok) return null
        var sum = 0.0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val c = bitmap.getPixel(x, y)
            sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        }
        return sum / (bitmap.width * bitmap.height)
    }

    fun release() {
        copyThread.quitSafely()
    }
}
