package app.shelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The game cover behind the whole launcher.
 *
 * The picture fills the panel evenly (a square cover on a wide panel loses a strip at the top
 * and the bottom). It is left as it is all the way round the frame; towards the centre of the
 * panel, in every direction alike, it loses its sharpness and then sinks under a grey veil,
 * because that is where the tiles and the texts are. Only the very middle is fully blurred.
 */
class Backdrop(c: Context) : View(c) {
    private var sharp: Bitmap? = null
    private var soft: Bitmap? = null
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG)
    private val veil = Paint()
    private val keep = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    private val blurMask = Paint().apply { xfermode = keep }
    private val veilMask = Paint().apply { xfermode = keep }
    private val dst = RectF()
    private val shape = Matrix()

    fun setCover(b: Bitmap?) {
        sharp = b
        soft = b?.let { blurred(it) }
        visibility = if (b == null) GONE else VISIBLE
        invalidate()
    }

    /**
     * A mask that is opaque in the middle of the panel and fades out towards its edges, the same
     * way in every direction: [stops] are pairs of (distance from the centre, opacity 0..1), the
     * distance being 1 at the middle of each edge and about 1.4 in the corners.
     */
    private fun oval(w: Float, h: Float, vararg stops: Float): Shader {
        val n = stops.size / 2
        val reach = 1.5f
        val pos = FloatArray(n) { stops[it * 2] / reach }
        val col = IntArray(n) { (stops[it * 2 + 1] * 255).toInt().coerceIn(0, 255) shl 24 }
        return RadialGradient(0f, 0f, reach, col, pos, Shader.TileMode.CLAMP).apply {
            shape.reset()
            shape.setScale(w / 2, h / 2)
            shape.postTranslate(w / 2, h / 2)
            setLocalMatrix(shape)
        }
    }

    private fun shaders(wi: Int, hi: Int) {
        if (wi <= 0 || hi <= 0) return
        val w = wi.toFloat()
        val h = hi.toFloat()
        // fully blurred only in the very middle, as it is from about the frame outwards
        blurMask.shader = oval(w, h, 0f, 1f, 0.28f, 1f, 0.62f, 0.55f, 0.92f, 0f, 1.5f, 0f)
        // the veil reaches a little further: the outermost tiles and the bars still get some of it
        veilMask.shader = oval(w, h, 0f, 1f, 0.34f, 1f, 0.7f, 0.72f, 0.98f, 0.34f, 1.28f, 0f, 1.5f, 0f)
        // lighter at the top, denser at the bottom where the dock is
        veil.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x8A2C2C2E.toInt(), 0xA62A2A2C.toInt(), 0xBD28282A.toInt()), null, Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = shaders(w, h)

    override fun onDraw(c: Canvas) {
        val b = sharp ?: return
        val s = soft ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // fill the panel evenly, cutting off what does not fit
        val scale = max(w / b.width, h / b.height)
        val dw = b.width * scale
        val dh = b.height * scale
        dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)

        c.drawBitmap(b, null, dst, picture)
        // a touch darker everywhere, so that white labels hold on a bright picture
        c.drawColor(0x1C000000)

        // the same picture blurred, kept only towards the middle
        val blur = c.saveLayer(0f, 0f, w, h, null)
        c.drawBitmap(s, null, dst, picture)
        c.drawRect(0f, 0f, w, h, blurMask)
        c.restoreToCount(blur)

        val grey = c.saveLayer(0f, 0f, w, h, null)
        c.drawRect(0f, 0f, w, h, veil)
        c.drawRect(0f, 0f, w, h, veilMask)
        c.restoreToCount(grey)
    }

    /** A small copy smoothed by three box passes; stretched back over the panel it is a soft blur. */
    private fun blurred(src: Bitmap): Bitmap {
        val w = 96
        val h = max(1, src.height * w / max(1, src.width))
        val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            box(px, tmp, w, h, 5, true)
            box(tmp, px, w, h, 5, false)
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
