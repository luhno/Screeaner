package app.screeaner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.max

/**
 * The last minute as a dot plot: one dot per second, ink on black, red when a
 * value crosses the alert line. Hairlines above and below, scale in mono.
 */
class GraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val values = FloatArray(CAPACITY) { Float.NaN }
    private var unit = ""
    private var minimumScale = 1f
    private var alertAbove = Float.MAX_VALUE
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint().apply {
        color = Dot.ink(0.12f)
        strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Dot.INK_SECONDARY
        textSize = 10f * density
        typeface = try {
            resources.getFont(R.font.jetbrains_mono_regular)
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }

    /** [minimumScale] keeps a quiet graph from zooming into noise; dots above [alertAbove] turn red. */
    fun configure(unit: String, minimumScale: Float, alertAbove: Float = Float.MAX_VALUE) {
        this.unit = unit
        this.minimumScale = minimumScale
        this.alertAbove = alertAbove
        values.fill(Float.NaN)
        invalidate()
    }

    /** Adds one point; NaN leaves a gap. */
    fun push(value: Float) {
        System.arraycopy(values, 1, values, 0, CAPACITY - 1)
        values[CAPACITY - 1] = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 2f * density
        val top = r + density
        val bottom = h - r - density
        if (w <= 0 || bottom <= top) return

        var peak = minimumScale
        for (v in values) if (!v.isNaN()) peak = max(peak, v)
        val scale = peak * 1.15f

        canvas.drawLine(0f, 0.5f, w, 0.5f, linePaint)
        canvas.drawLine(0f, h - 0.5f, w, h - 0.5f, linePaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(Locale.US, "%.0f %s · 60 s", scale, unit), w, textPaint.textSize + 3 * density, textPaint)

        val step = (w - 2 * r) / (CAPACITY - 1)
        for (i in 0 until CAPACITY) {
            val v = values[i]
            if (v.isNaN()) continue
            val x = r + i * step
            val y = bottom - (bottom - top) * (v / scale).coerceIn(0f, 1f)
            dotPaint.color = if (v > alertAbove) Dot.SIGNAL else Dot.INK
            canvas.drawCircle(x, y, r, dotPaint)
        }
    }

    private companion object {
        const val CAPACITY = 60
    }
}
