package app.shelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The game cover behind the whole launcher.
 *
 * Along the edges of the panel the picture is left as it is, so it can be told which game
 * this is. Towards the middle, where the tiles and the texts are, it turns blurred and then
 * sinks under a grey veil. Blur and veil each fade in on their own: the veil has to be there
 * by the time the content starts, the blur can take longer.
 */
class Backdrop(c: Context) : View(c) {
    private var sharp: Bitmap? = null
    private var soft: Bitmap? = null
    private val density = resources.displayMetrics.density
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG)
    private val veil = Paint()
    private val keep = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    private val blurX = Paint().apply { xfermode = keep }
    private val blurY = Paint().apply { xfermode = keep }
    private val veilX = Paint().apply { xfermode = keep }
    private val veilY = Paint().apply { xfermode = keep }
    private val dst = RectF()

    /** How far from the left and right edges the content of the screen begins, px. */
    var inset = 0f
        set(v) {
            field = v
            shaders(width, height)
            invalidate()
        }

    val hasCover: Boolean get() = sharp != null

    fun setCover(b: Bitmap?) {
        sharp = b
        soft = b?.let { blurred(it) }
        visibility = if (b == null) GONE else VISIBLE
        invalidate()
    }

    /**
     * A mask along one axis, the same from both ends: pairs of (distance from the edge in px,
     * opacity 0..1). Whatever a layer holds stays where the mask is opaque.
     */
    private fun ramp(length: Float, horizontal: Boolean, vararg stops: Float): Shader {
        val n = stops.size / 2
        val pos = FloatArray(n * 2)
        val col = IntArray(n * 2)
        var last = 0f
        for (i in 0 until n) {
            // positions must not go back, and both halves must fit
            val p = max(last, min(stops[i * 2] / length, 0.49f))
            last = p
            val a = (stops[i * 2 + 1] * 255).toInt().coerceIn(0, 255) shl 24
            pos[i] = p
            col[i] = a
            pos[2 * n - 1 - i] = 1f - p
            col[2 * n - 1 - i] = a
        }
        return if (horizontal) {
            LinearGradient(0f, 0f, length, 0f, col, pos, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, 0f, length, col, pos, Shader.TileMode.CLAMP)
        }
    }

    private fun shaders(wi: Int, hi: Int) {
        if (wi <= 0 || hi <= 0) return
        val w = wi.toFloat()
        val h = hi.toFloat()
        val d = density
        // sharp in the frame, fully blurred a quarter of the way in
        blurX.shader = ramp(w, true, 20 * d, 0f, w * 0.26f, 1f)
        blurY.shader = ramp(h, false, 0f, 0.1f, h * 0.12f, 1f)
        // half the veil where the content starts, all of it a third of the way in
        veilX.shader = ramp(w, true, 6 * d, 0f, inset + 12 * d, 0.5f, w * 0.17f, 0.82f, w * 0.31f, 1f)
        veilY.shader = ramp(h, false, 0f, 0.3f, h * 0.07f, 1f)
        // lighter at the top, denser at the bottom where the dock is
        veil.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x992C2C2E.toInt(), 0xBF2A2A2C.toInt(), 0xDB28282A.toInt()), null, Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = shaders(w, h)

    override fun onDraw(c: Canvas) {
        val b = sharp ?: return
        val s = soft ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // fill the panel, cutting off what does not fit
        val scale = max(w / b.width, h / b.height)
        val dw = b.width * scale
        val dh = b.height * scale
        dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)

        c.drawBitmap(b, null, dst, picture)
        // a touch darker everywhere, so that white labels hold on a bright picture
        c.drawColor(0x29000000)

        var layer = c.saveLayer(0f, 0f, w, h, null)
        c.drawBitmap(s, null, dst, picture)
        c.drawRect(0f, 0f, w, h, blurX)
        c.drawRect(0f, 0f, w, h, blurY)
        c.restoreToCount(layer)

        layer = c.saveLayer(0f, 0f, w, h, null)
        c.drawRect(0f, 0f, w, h, veil)
        c.drawRect(0f, 0f, w, h, veilX)
        c.drawRect(0f, 0f, w, h, veilY)
        c.restoreToCount(layer)
    }

    /** A small copy smoothed by three box passes; stretched back over the panel it is a soft blur. */
    private fun blurred(src: Bitmap): Bitmap {
        val w = 112
        val h = max(1, src.height * w / max(1, src.width))
        val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            box(px, tmp, w, h, 4, true)
            box(tmp, px, w, h, 4, false)
        }
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }

    private fun box(from: IntArray, to: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (l in 0 until lines) {
            for (i in 0 until len) {
                var red = 0
                var green = 0
                var blue = 0
                var n = 0
                for (k in max(0, i - r)..min(len - 1, i + r)) {
                    val p = if (horizontal) from[l * w + k] else from[k * w + l]
                    red += (p shr 16) and 0xFF
                    green += (p shr 8) and 0xFF
                    blue += p and 0xFF
                    n++
                }
                val at = if (horizontal) l * w + i else i * w + l
                to[at] = (0xFF shl 24) or ((red / n) shl 16) or ((green / n) shl 8) or (blue / n)
            }
        }
    }
}
