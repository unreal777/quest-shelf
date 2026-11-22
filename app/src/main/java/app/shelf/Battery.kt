package app.shelf

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos

/**
 * The headset's charge in the top bar: a battery made of five slanted cells, like an energy bar
 * in a game, and the number next to it. Green above half, yellow below, red at a fifth and less.
 * The topmost lit cell is as bright as it is full; it breathes while charging and when nearly empty.
 */
class BatteryView(c: Context, private val ui: Kit) : View(c) {
    private var level = -1
    private var charging = false
    private var label = ""
    private var phase = 0f
    private var pulse: ValueAnimator? = null

    private val d = c.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ui.medium
        textSize = 15f * c.resources.displayMetrics.scaledDensity
    }
    private val box = RectF()
    private val path = Path()
    private val clip = Path()

    private val padX = 14f * d
    private val bodyW = 31f * d
    private val bodyH = 16f * d
    private val nubW = 2.5f * d
    private val boltW = 8f * d
    private val gap = 7f * d

    init {
        background = ui.round(Pal.GLASS, 24)
        visibility = GONE
        setOnHoverListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    v.background = ui.round(Pal.GLASS_HI, 24)
                    ui.showTip(v, tip())
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    v.background = ui.round(Pal.GLASS, 24)
                    ui.hideTip()
                }
            }
            false
        }
    }

    /** [percent] 0..100; [plugged] when the charger is in, whether or not it is still filling up. */
    fun set(percent: Int, plugged: Boolean) {
        val p = percent.coerceIn(0, 100)
        if (p == level && plugged == charging) return
        level = p
        charging = plugged
        val text = "$p%"
        val resize = text.length != label.length || visibility != VISIBLE
        label = text
        visibility = VISIBLE
        if (resize) requestLayout()
        invalidate()
        animateIfNeeded()
    }

    private fun tip() = context.getString(
        if (charging) R.string.battery_tip_charging else R.string.battery_tip, level
    )

    private fun tint() = when {
        level > 50 -> Pal.GREEN
        level > 20 -> Pal.GOLD
        else -> Pal.RED
    }

    /** Something moves only while charging below full, or when nearly empty; and only on screen. */
    private fun animateIfNeeded() {
        val wanted = isAttachedToWindow && windowVisibility == VISIBLE && level >= 0 &&
            ((charging && level < 100) || (!charging && level <= 15))
        if (wanted && pulse == null) {
            pulse = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1800
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!wanted && pulse != null) {
            pulse?.cancel()
            pulse = null
            phase = 0f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animateIfNeeded()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animateIfNeeded()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        animateIfNeeded()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        var w = padX * 2 + bodyW + nubW + gap + textPaint.measureText(label)
        if (charging) w += boltW + gap * 0.7f
        setMeasuredDimension(resolveSize((w + 0.5f).toInt(), widthSpec), getDefaultSize((48 * d).toInt(), heightSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (level < 0) return
        val color = tint()
        val cy = height / 2f
        var x = padX

        // the body: an outline with a nub on the right
        val stroke = 1.5f * d
        box.set(x + stroke / 2, cy - bodyH / 2 + stroke / 2, x + bodyW - stroke / 2, cy + bodyH / 2 - stroke / 2)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = 0xCCF2F2F2.toInt()
        canvas.drawRoundRect(box, 4.5f * d, 4.5f * d, paint)
        paint.style = Paint.Style.FILL
        box.set(x + bodyW + 0.5f * d, cy - 3f * d, x + bodyW + nubW, cy + 3f * d)
        canvas.drawRoundRect(box, 1.2f * d, 1.2f * d, paint)

        // five slanted cells inside, cut by the body's inner edge
        val inset = 3.2f * d
        box.set(x + inset, cy - bodyH / 2 + inset, x + bodyW - inset, cy + bodyH / 2 - inset)
        clip.reset()
        clip.addRoundRect(box, 1.8f * d, 1.8f * d, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val slant = box.height() * 0.42f
        val pitch = (box.width() + slant) / CELLS
        val cellW = pitch - 1.6f * d
        // 0..1..0 over one round of the animation
        val breath = 0.5f - 0.5f * cos(phase * 2 * PI).toFloat()
        val step = 100 / CELLS
        for (i in 0 until CELLS) {
            // how full this cell is, 0..1
            val share = ((level - i * step) / step.toFloat()).coerceIn(0f, 1f)
            var alpha = if (share > 0f) 0.45f + 0.55f * share else 0f
            if (pulse != null) {
                if (charging) {
                    // the cell being filled swells up to full brightness and back
                    if (i == minOf(level / step, CELLS - 1)) {
                        val base = maxOf(alpha, 0.15f)
                        alpha = base + (1f - base) * breath
                    }
                } else if (share > 0f) {
                    // nearly empty: what is left blinks slowly
                    alpha *= 0.35f + 0.65f * breath
                }
            }
            val left = box.left - slant + i * pitch
            path.reset()
            path.moveTo(left, box.bottom)
            path.lineTo(left + cellW, box.bottom)
            path.lineTo(left + cellW + slant, box.top)
            path.lineTo(left + slant, box.top)
            path.close()
            paint.color = 0x2EFFFFFF
            canvas.drawPath(path, paint)
            if (alpha > 0f) {
                paint.color = (color and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)
                canvas.drawPath(path, paint)
            }
        }
        canvas.restore()
        x += bodyW + nubW + gap

        // a bolt while the charger is in
        if (charging) {
            val h = 14f * d
            val t = cy - h / 2
            path.reset()
            path.moveTo(x + boltW * 0.62f, t)
            path.lineTo(x, t + h * 0.58f)
            path.lineTo(x + boltW * 0.44f, t + h * 0.58f)
            path.lineTo(x + boltW * 0.34f, t + h)
            path.lineTo(x + boltW, t + h * 0.40f)
            path.lineTo(x + boltW * 0.54f, t + h * 0.40f)
            path.close()
            paint.color = color
            canvas.drawPath(path, paint)
            x += boltW + gap * 0.7f
        }

        textPaint.color = if (level <= 20 && !charging) Pal.RED else Pal.TEXT
        val fm = textPaint.fontMetrics
        canvas.drawText(label, x, cy - (fm.ascent + fm.descent) / 2, textPaint)
    }

    private companion object {
        const val CELLS = 5
    }
}
