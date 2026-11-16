package app.screeaner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.max

/** A small rolling line chart: one point per second, last minute visible. */
class GraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private class Series(val name: String, val color: Int) {
        val values = FloatArray(CAPACITY) { Float.NaN }
    }

    private val density = resources.displayMetrics.density
    private val series = ArrayList<Series>()
    private var count = 0
    private var unit = ""
    private var minimumScale = 1f
    private val path = Path()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint().apply {
        color = 0x33FFFFFF
        strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textSize = 10f * density
    }

    /** [minimumScale] keeps a quiet graph from zooming into noise. */
    fun configure(unit: String, minimumScale: Float, vararg lines: Pair<String, Int>) {
        this.unit = unit
        this.minimumScale = minimumScale
        series.clear()
        lines.forEach { series += Series(it.first, it.second) }
        count = 0
        invalidate()
    }

    /** Adds one point per configured line; NaN leaves a gap. */
    fun push(vararg values: Float) {
        for ((index, line) in series.withIndex()) {
            System.arraycopy(line.values, 1, line.values, 0, CAPACITY - 1)
            line.values[CAPACITY - 1] = values.getOrElse(index) { Float.NaN }
        }
        count = minOf(count + 1, CAPACITY)
        invalidate()
    }

    fun clear() {
        series.forEach { it.values.fill(Float.NaN) }
        count = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = textPaint.textSize + 2 * density
        val plot = h - top
        if (w <= 0 || plot <= 0) return

        var peak = minimumScale
        for (line in series) for (v in line.values) if (!v.isNaN()) peak = max(peak, v)
        val scale = peak * 1.1f

        canvas.drawLine(0f, h - 1, w, h - 1, gridPaint)
        canvas.drawLine(0f, top, w, top, gridPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(String.format(Locale.US, "%.0f %s", scale, unit), 0f, textPaint.textSize, textPaint)

        // Legend, right aligned, in each line's color.
        var legendX = w
        textPaint.textAlign = Paint.Align.RIGHT
        val labelColor = textPaint.color
        for (line in series.asReversed()) {
            textPaint.color = line.color
            canvas.drawText(line.name, legendX, textPaint.textSize, textPaint)
            legendX -= textPaint.measureText(line.name) + 8 * density
        }
        textPaint.color = labelColor

        val step = w / (CAPACITY - 1)
        for (line in series) {
            path.reset()
            var drawing = false
            for (i in 0 until CAPACITY) {
                val v = line.values[i]
                if (v.isNaN()) {
                    drawing = false
                    continue
                }
                val x = i * step
                val y = top + plot * (1f - (v / scale).coerceIn(0f, 1f))
                if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                drawing = true
            }
            linePaint.color = line.color
            canvas.drawPath(path, linePaint)
        }
    }

    private companion object {
        const val CAPACITY = 60
    }
}
