package app.screeaner

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The "Screeaner Dot" visual language: dots as the unit of measure.
 * Ink on black, one signal red for what is live, recording or wrong.
 */
object Dot {
    const val INK = 0xFFF2F2EF.toInt()
    const val INK_SECONDARY = 0xFFA6A6A2.toInt()
    const val SIGNAL = 0xFFE8352B.toInt()
    const val CANVAS = 0xFF0A0A0A.toInt()

    /** Ink at a given opacity (0..1). */
    fun ink(alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (INK and 0xFFFFFF)
}

/** A faint grid of dots behind full-screen pages. */
class DotGridDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Dot.ink(0.06f) }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(Dot.CANVAS)
        val step = 16 * density
        val r = 1f * density
        var y = step / 2
        while (y < bounds.height()) {
            var x = step / 2
            while (x < bounds.width()) {
                canvas.drawCircle(bounds.left + x, bounds.top + y, r, paint)
                x += step
            }
            y += step
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/**
 * Concentric rings of dots with one red dot travelling around: the "looking
 * for the Mac" state. Animates only while it is visible and [searching].
 */
class RadarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val start = System.nanoTime()

    var searching = true
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val unit = min(width, height) / 420f
        val elapsed = (System.nanoTime() - start) / 1e9f
        for ((ring, spec) in RINGS.withIndex()) {
            val (radius, count) = spec
            val hot = if (searching && ring == 2) ((elapsed * 6).toInt() % count) else -1
            for (i in 0 until count) {
                val a = (i.toFloat() / count) * 2 * Math.PI - Math.PI / 2
                val x = cx + (cos(a) * radius * unit).toFloat()
                val y = cy + (sin(a) * radius * unit).toFloat()
                val size = if (ring == 3) 3f else 4f
                if (i == hot) {
                    paint.color = Dot.SIGNAL
                    canvas.drawCircle(x, y, 5f * unit, paint)
                } else {
                    paint.color = Dot.ink(ALPHAS[ring])
                    canvas.drawCircle(x, y, size * unit, paint)
                }
            }
        }
        paint.color = if (searching) Dot.INK else Dot.SIGNAL
        canvas.drawCircle(cx, cy, 8f * unit, paint)
        if (searching && isShown) postInvalidateDelayed(FRAME_MS)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = (280 * density).toInt()
        setMeasuredDimension(resolveSize(side, widthMeasureSpec), resolveSize(side, heightMeasureSpec))
    }

    private companion object {
        val RINGS = listOf(48f to 8, 96f to 16, 144f to 24, 192f to 32)
        val ALPHAS = floatArrayOf(0.9f, 0.55f, 0.32f, 0.16f)
        const val FRAME_MS = 80L
    }
}

/** A row of segments proportional to values: the latency breakdown (encode in red). */
class SegmentBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var values = FloatArray(0)
    private var colors = IntArray(0)

    fun set(values: FloatArray, colors: IntArray) {
        this.values = values
        this.colors = colors
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val total = values.filter { !it.isNaN() && it > 0 }.sum()
        if (total <= 0) {
            paint.color = Dot.ink(0.16f)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 2 * density, 2 * density, paint)
            return
        }
        val gap = 3 * density
        val usable = width - gap * (values.size - 1)
        var x = 0f
        for ((i, v) in values.withIndex()) {
            if (v.isNaN() || v <= 0) continue
            val w = usable * v / total
            paint.color = colors.getOrElse(i) { Dot.INK }
            canvas.drawRoundRect(x, 0f, x + w, height.toFloat(), 2 * density, 2 * density, paint)
            x += w + gap
        }
    }
}

/**
 * Progress as dots in groups: each dot a real step, the current one red.
 * Used for the benchmark (configurations × phases) and the touch test.
 */
class DotProgressView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var groups = 1
    private var perGroup = 20
    private var done = 0

    /** [done] steps finished out of groups × perGroup; the next one shows as current. */
    fun set(groups: Int, perGroup: Int, done: Int) {
        this.groups = groups.coerceAtLeast(1)
        this.perGroup = perGroup.coerceAtLeast(1)
        this.done = done
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val total = groups * perGroup
        val groupGap = 10 * density
        val available = width - groupGap * (groups - 1)
        val pitch = min(available / total, 14 * density)
        val r = min(pitch * 0.32f, 4.5f * density)
        val cy = height / 2f
        var x = pitch / 2
        for (i in 0 until total) {
            if (i > 0 && i % perGroup == 0) x += groupGap
            paint.color = when {
                i < done -> Dot.INK
                i == done -> Dot.SIGNAL
                else -> Dot.ink(0.16f)
            }
            canvas.drawCircle(x, cy, r, paint)
            x += pitch
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((14 * density).toInt(), heightMeasureSpec),
        )
    }
}
