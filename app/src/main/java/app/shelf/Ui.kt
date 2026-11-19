package app.shelf

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The palette: one dark surface family, one accent, one colour per mark. */
object Pal {
    const val BG = 0xFF0F1116.toInt()
    const val SURFACE = 0xFF171A23.toInt()
    const val CARD = 0xFF222734.toInt()
    const val CARD_HI = 0xFF313849.toInt()
    const val ACCENT = 0xFF6EA8FF.toInt()
    const val ACCENT_HI = 0xFF9BC3FF.toInt()
    const val TEXT = 0xFFF1F3F8.toInt()
    const val DIM = 0xFF98A0B3.toInt()
    const val DARK = 0xFF0B0D12.toInt()
    const val GOLD = 0xFFFFC83D.toInt()
    const val RED = 0xFFFF7B7B.toInt()
    const val PURPLE = 0xFFB99BFF.toInt()
    const val SLATE = 0xFFA9B4C8.toInt()
    const val GREEN = 0xFF6FD08C.toInt()

    /** The same colour as a faint wash, for the plate behind an icon. */
    fun wash(color: Int, alpha: Int = 0x2B) = (color and 0x00FFFFFF) or (alpha shl 24)
}

/** UI language: follows the headset unless the user picked one in the settings. */
object Lang {
    /** code -> the language's own name */
    val ALL = listOf(
        "en" to "English",
        "ru" to "Русский",
        "de" to "Deutsch",
        "es" to "Español",
        "fr" to "Français",
    )

    fun wrap(base: Context): Context {
        Store.init(base)
        val code = Store.lang
        if (code.isEmpty()) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale(code))
        return base.createConfigurationContext(cfg)
    }
}

enum class Ic {
    PLAY, DOWNLOAD, STAR, STAR_LINE, MORE, BAG, FOLDER, TROPHY, SAD, FLASK, CLOCK, MOVE, EDIT,
    EYE, EYE_OFF, INFO, TRASH, SLIDERS, SORT, SEARCH, CHECK, CLOSE, PLUS, REFRESH, GLOBE, POWER,
    SWAP, GRID, IMAGE, TARGET, LIST, OPEN, CHART, OK_CIRCLE, GEAR, BELL, CAMERA, BOLT, PEOPLE, USER, FUNNEL, SWITCH, CHEVRON,
}

/** Line icons drawn in code on a 24-unit grid, so they stay sharp at any size and in any colour. */
class Icon(private val kind: Ic, color: Int) : Drawable() {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 1.2f
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val path = Path()
    private val box = RectF()

    private fun Canvas.l(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(x1, y1, x2, y2, line)

    private fun Canvas.poly(p: Paint, close: Boolean, vararg xy: Float) {
        path.rewind()
        path.moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) {
            path.lineTo(xy[i], xy[i + 1])
            i += 2
        }
        if (close) path.close()
        drawPath(path, p)
    }

    private fun Canvas.arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float) {
        box.set(l, t, r, b)
        drawArc(box, start, sweep, false, line)
    }

    private fun Canvas.star(p: Paint) {
        path.rewind()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 9f else 3.9f
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = 12f + (r * cos(a)).toFloat()
            val y = 12.6f + (r * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, p)
    }

    private fun Canvas.eye() {
        path.rewind()
        path.moveTo(2.5f, 12f)
        path.quadTo(12f, 3.2f, 21.5f, 12f)
        path.quadTo(12f, 20.8f, 2.5f, 12f)
        drawPath(path, line)
        drawCircle(12f, 12f, 2.8f, line)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) / 24f
        if (s <= 0f) return
        c.save()
        c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2)
        c.scale(s, s)
        when (kind) {
            Ic.PLAY -> c.poly(fill, true, 8.5f, 5.5f, 18.5f, 12f, 8.5f, 18.5f)
            Ic.DOWNLOAD -> {
                c.l(12f, 4f, 12f, 15f)
                c.poly(line, false, 7f, 10.5f, 12f, 15.5f, 17f, 10.5f)
                c.l(5f, 20f, 19f, 20f)
            }
            Ic.STAR -> c.star(fill)
            Ic.STAR_LINE -> c.star(line)
            Ic.MORE -> {
                c.drawCircle(5.5f, 12f, 1.4f, fill)
                c.drawCircle(12f, 12f, 1.4f, fill)
                c.drawCircle(18.5f, 12f, 1.4f, fill)
            }
            Ic.BAG -> {
                box.set(4.5f, 8.5f, 19.5f, 20.5f)
                c.drawRoundRect(box, 2.5f, 2.5f, line)
                c.arc(8.5f, 4f, 15.5f, 12f, 180f, 180f)
            }
            Ic.FOLDER -> c.poly(line, true, 3f, 6f, 9.5f, 6f, 11.5f, 8.5f, 21f, 8.5f, 21f, 19f, 3f, 19f)
            Ic.TROPHY -> {
                path.rewind()
                path.moveTo(7f, 4f)
                path.lineTo(17f, 4f)
                path.lineTo(17f, 9.5f)
                box.set(7f, 4.5f, 17f, 14.5f)
                path.arcTo(box, 0f, 180f)
                path.close()
                c.drawPath(path, line)
                c.arc(3.5f, 5.5f, 9f, 10.5f, 90f, 180f)
                c.arc(15f, 5.5f, 20.5f, 10.5f, -90f, 180f)
                c.l(12f, 14.5f, 12f, 19.5f)
                c.l(8f, 19.8f, 16f, 19.8f)
            }
            Ic.SAD -> {
                c.drawCircle(12f, 12f, 9f, line)
                c.drawCircle(8.8f, 10f, 0.9f, fill)
                c.drawCircle(15.2f, 10f, 0.9f, fill)
                c.arc(8f, 14.2f, 16f, 20.2f, 205f, 130f)
            }
            Ic.FLASK -> {
                c.l(9f, 4f, 15f, 4f)
                c.poly(line, false, 10.5f, 4f, 10.5f, 9.5f, 5f, 19.5f, 19f, 19.5f, 13.5f, 9.5f, 13.5f, 4f)
                c.l(7.9f, 14.5f, 16.1f, 14.5f)
            }
            Ic.CLOCK -> {
                c.drawCircle(12f, 12f, 9f, line)
                c.poly(line, false, 12f, 7f, 12f, 12f, 15.5f, 14f)
            }
            Ic.MOVE -> {
                c.l(12f, 3f, 12f, 21f)
                c.l(3f, 12f, 21f, 12f)
                c.poly(line, false, 9.5f, 5.5f, 12f, 3f, 14.5f, 5.5f)
                c.poly(line, false, 9.5f, 18.5f, 12f, 21f, 14.5f, 18.5f)
                c.poly(line, false, 5.5f, 9.5f, 3f, 12f, 5.5f, 14.5f)
                c.poly(line, false, 18.5f, 9.5f, 21f, 12f, 18.5f, 14.5f)
            }
            Ic.EDIT -> {
                c.poly(line, true, 4f, 20f, 4.9f, 16.1f, 15.5f, 5.5f, 18.5f, 8.5f, 7.9f, 19.1f)
                c.l(13.4f, 7.6f, 16.4f, 10.6f)
            }
            Ic.EYE -> c.eye()
            Ic.EYE_OFF -> {
                c.eye()
                c.l(4.5f, 4.5f, 19.5f, 19.5f)
            }
            Ic.INFO -> {
                c.drawCircle(12f, 12f, 9f, line)
                c.l(12f, 11f, 12f, 16.5f)
                c.drawCircle(12f, 7.7f, 0.8f, fill)
            }
            Ic.TRASH -> {
                c.l(4.5f, 6.5f, 19.5f, 6.5f)
                c.poly(line, false, 9f, 6.5f, 9f, 4f, 15f, 4f, 15f, 6.5f)
                c.poly(line, false, 6.5f, 6.5f, 7.5f, 20f, 16.5f, 20f, 17.5f, 6.5f)
                c.l(10.2f, 10f, 10.2f, 16.5f)
                c.l(13.8f, 10f, 13.8f, 16.5f)
            }
            Ic.SLIDERS -> {
                c.l(4f, 7f, 20f, 7f)
                c.l(4f, 12f, 20f, 12f)
                c.l(4f, 17f, 20f, 17f)
                c.drawCircle(15f, 7f, 2f, fill)
                c.drawCircle(9f, 12f, 2f, fill)
                c.drawCircle(14f, 17f, 2f, fill)
            }
            Ic.SORT -> {
                c.l(4f, 7f, 20f, 7f)
                c.l(4f, 12f, 15f, 12f)
                c.l(4f, 17f, 10f, 17f)
            }
            Ic.SEARCH -> {
                c.drawCircle(10.5f, 10.5f, 6f, line)
                c.l(15f, 15f, 20.5f, 20.5f)
            }
            Ic.CHECK -> c.poly(line, false, 5f, 12.5f, 10f, 17.5f, 19.5f, 7f)
            Ic.CLOSE -> {
                c.l(6f, 6f, 18f, 18f)
                c.l(18f, 6f, 6f, 18f)
            }
            Ic.PLUS -> {
                c.l(12f, 5f, 12f, 19f)
                c.l(5f, 12f, 19f, 12f)
            }
            Ic.REFRESH -> {
                c.arc(4.5f, 4.5f, 19.5f, 19.5f, 20f, 290f)
                c.poly(line, false, 17.7f, 2.6f, 16.9f, 6.4f, 13.2f, 6f)
            }
            Ic.GLOBE -> {
                c.drawCircle(12f, 12f, 9f, line)
                box.set(8f, 3f, 16f, 21f)
                c.drawOval(box, line)
                c.l(3f, 12f, 21f, 12f)
            }
            Ic.POWER -> {
                c.arc(5f, 5.5f, 19f, 19.5f, -60f, 300f)
                c.l(12f, 3f, 12f, 11f)
            }
            Ic.SWAP -> {
                c.l(5f, 8f, 19f, 8f)
                c.poly(line, false, 16f, 5f, 19f, 8f, 16f, 11f)
                c.l(19f, 16f, 5f, 16f)
                c.poly(line, false, 8f, 13f, 5f, 16f, 8f, 19f)
            }
            Ic.GRID -> {
                for (x in floatArrayOf(4f, 13.5f)) for (y in floatArrayOf(4f, 13.5f)) {
                    box.set(x, y, x + 6.5f, y + 6.5f)
                    c.drawRoundRect(box, 1.6f, 1.6f, line)
                }
            }
            Ic.IMAGE -> {
                box.set(3.5f, 5f, 20.5f, 19f)
                c.drawRoundRect(box, 2.5f, 2.5f, line)
                c.poly(line, false, 6f, 16f, 10f, 11.5f, 13f, 14.5f, 15.5f, 12f, 18.5f, 16f)
                c.drawCircle(15.8f, 8.6f, 0.9f, fill)
            }
            Ic.TARGET -> {
                c.drawCircle(12f, 12f, 9f, line)
                c.drawCircle(12f, 12f, 4.5f, line)
                c.drawCircle(12f, 12f, 0.9f, fill)
            }
            Ic.LIST -> {
                for (y in floatArrayOf(7f, 12f, 17f)) {
                    c.drawCircle(5f, y, 0.9f, fill)
                    c.l(9f, y, 20f, y)
                }
            }
            Ic.OPEN -> {
                c.poly(line, false, 10f, 5.5f, 5f, 5.5f, 5f, 19f, 18.5f, 19f, 18.5f, 14f)
                c.l(20f, 4f, 11.5f, 12.5f)
                c.poly(line, false, 14.5f, 4f, 20f, 4f, 20f, 9.5f)
            }
            Ic.CHART -> {
                c.l(6f, 19f, 6f, 12f)
                c.l(12f, 19f, 12f, 5.5f)
                c.l(18f, 19f, 18f, 14.5f)
            }
            Ic.GEAR -> {
                // eight teeth: each rises from the rim, runs flat and comes back down
                path.rewind()
                for (k in 0 until 8) {
                    val mid = k * 45.0
                    val pts = arrayOf(mid - 17 to 7.0, mid - 10 to 9.6, mid + 10 to 9.6, mid + 17 to 7.0)
                    for ((i, pt) in pts.withIndex()) {
                        val a = Math.toRadians(pt.first)
                        val x = 12f + (pt.second * cos(a)).toFloat()
                        val y = 12f + (pt.second * sin(a)).toFloat()
                        if (k == 0 && i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                }
                path.close()
                c.drawPath(path, line)
                c.drawCircle(12f, 12f, 3f, line)
            }
            Ic.SWITCH -> {
                box.set(2.5f, 7f, 21.5f, 17f)
                c.drawRoundRect(box, 5f, 5f, line)
                c.drawCircle(16.5f, 12f, 2.4f, fill)
            }
            Ic.CHEVRON -> c.poly(line, false, 6.5f, 9.5f, 12f, 15f, 17.5f, 9.5f)
            Ic.BELL -> {
                path.rewind()
                path.moveTo(6f, 17f)
                path.lineTo(6f, 11f)
                box.set(6f, 5f, 18f, 17f)
                path.arcTo(box, 180f, 180f)
                path.lineTo(18f, 17f)
                c.drawPath(path, line)
                c.l(4.5f, 17f, 19.5f, 17f)
                c.arc(10f, 17f, 14f, 21f, 0f, 180f)
            }
            Ic.CAMERA -> {
                box.set(3.5f, 7.5f, 20.5f, 19.5f)
                c.drawRoundRect(box, 2.5f, 2.5f, line)
                c.drawCircle(12f, 13.5f, 3.2f, line)
                c.poly(line, false, 8.5f, 7.5f, 10f, 4.5f, 14f, 4.5f, 15.5f, 7.5f)
            }
            Ic.BOLT -> c.poly(line, true, 13f, 3f, 6f, 13.5f, 11.5f, 13.5f, 10.5f, 21f, 18f, 10.5f, 12.5f, 10.5f)
            Ic.PEOPLE -> {
                c.drawCircle(9f, 8.5f, 3f, line)
                c.arc(3f, 14f, 15f, 25f, 180f, 180f)
                c.drawCircle(17f, 9.5f, 2.3f, line)
                c.arc(14.5f, 14.5f, 21.5f, 23.5f, 215f, 145f)
            }
            Ic.FUNNEL -> c.poly(line, true, 4f, 5f, 20f, 5f, 14f, 12.5f, 14f, 19.5f, 10f, 17.5f, 10f, 12.5f)
            Ic.USER -> {
                c.drawCircle(12f, 8.5f, 3.6f, line)
                c.arc(4.5f, 14.5f, 19.5f, 27.5f, 180f, 180f)
            }
            Ic.OK_CIRCLE -> {
                c.drawCircle(12f, 12f, 9f, line)
                c.poly(line, false, 8f, 12.3f, 11f, 15.3f, 16.2f, 9.2f)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) {
        line.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        line.colorFilter = filter
        fill.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The bar under the selected tab. */
class Underline(color: Int, private val inset: Int, private val thick: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun draw(c: Canvas) {
        val b = bounds
        val r = thick / 2f
        c.drawRoundRect(
            (b.left + inset).toFloat(), (b.bottom - thick).toFloat(),
            (b.right - inset).toFloat(), b.bottom.toFloat(), r, r, p
        )
    }

    override fun setAlpha(alpha: Int) {
        p.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        p.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A row or tile that knows whether the pointer is anywhere over it, children included. */
class HoverBox(c: Context) : LinearLayout(c) {
    var onHot: (Boolean) -> Unit = {}

    override fun dispatchHoverEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> onHot(true)
            MotionEvent.ACTION_HOVER_EXIT -> onHot(false)
        }
        return super.dispatchHoverEvent(e)
    }
}

/** An on/off pill. */
class Toggle(c: Context) : View(c) {
    var on = false
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = if (on) Pal.ACCENT else 0xFF3A4254.toInt()
        cv.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
        p.color = if (on) Pal.DARK else 0xFFD2D8E4.toInt()
        cv.drawCircle(if (on) w - h / 2 else h / 2, h / 2, h * 0.36f, p)
    }
}

/** Building blocks shared by the main screen and its sheets. */
class Kit(val c: Context) {
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** The layer over the whole screen where tooltips are drawn. */
    var tipHost: FrameLayout? = null
    private var tipView: TextView? = null

    /** Dark at the bottom, a little lighter and bluer at the top. */
    fun backdrop() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF171C2A.toInt(), 0xFF0F1219.toInt(), 0xFF0B0D12.toInt())
    )

    /** A small label next to [anchor]: above it in the lower half of the screen, below it in the upper. */
    fun showTip(anchor: View, label: String) {
        val host = tipHost ?: return
        val tv = tipView ?: text("", 13f, Pal.DARK, bold = true).also {
            it.background = round(0xFFE9ECF3.toInt(), 10)
            it.setPadding(dp(10), dp(6), dp(10), dp(6))
            it.elevation = dp(8).toFloat()
            host.addView(it, FrameLayout.LayoutParams(wrap, wrap))
            tipView = it
        }
        tv.text = label
        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val a = IntArray(2)
        val h = IntArray(2)
        anchor.getLocationInWindow(a)
        host.getLocationInWindow(h)
        val w = tv.measuredWidth
        val x = (a[0] - h[0] + anchor.width / 2 - w / 2).coerceIn(dp(8), maxOf(dp(8), host.width - w - dp(8)))
        val top = a[1] - h[1]
        val y = if (top > host.height / 2) top - tv.measuredHeight - dp(8) else top + anchor.height + dp(8)
        tv.translationX = x.toFloat()
        tv.translationY = y.toFloat()
        tv.visibility = View.VISIBLE
    }

    fun hideTip() {
        tipView?.visibility = View.GONE
    }

    /** A round button that is only an icon; what it does is told by a tooltip on hover. */
    fun iconButton(
        ic: Ic,
        tip: String,
        size: Int = 48,
        active: Boolean = false,
        plain: Boolean = false,
        onClick: (View) -> Unit,
    ): ImageView {
        val normal = if (active) Pal.ACCENT else if (plain) 0 else Pal.CARD
        val hi = if (active) Pal.ACCENT_HI else Pal.CARD_HI
        return ImageView(c).apply {
            setImageDrawable(Icon(ic, if (active) Pal.DARK else Pal.TEXT))
            val p = dp(size) * 27 / 100
            setPadding(p, p, p, p)
            background = oval(normal)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                hideTip()
                onClick(it)
            }
            setOnHoverListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER -> {
                        v.background = oval(hi)
                        showTip(v, tip)
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        v.background = oval(normal)
                        hideTip()
                    }
                }
                false
            }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { leftMargin = dp(8) }
        }
    }

    /** A list that drops down right under [anchor]; one item is picked and it closes. */
    fun dropdown(anchor: View, items: List<Item>, width: Int = 300, onPick: (Int) -> Unit) {
        val rows = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        val panel = ScrollView(c).apply {
            background = round(0xFF1D2230.toInt(), 20)
            clipToOutline = true
            isVerticalScrollBarEnabled = false
            addView(rows)
        }
        val height = minOf(items.size * 50 + 12, 470)
        val pop = PopupWindow(panel, dp(width), dp(height), true)
        pop.setBackgroundDrawable(ColorDrawable(0))
        pop.elevation = dp(12).toFloat()
        items.forEachIndexed { i, it ->
            val normal = if (it.checked) Pal.CARD_HI else 0
            rows.addView(
                text(it.title, 15f, if (it.checked) Pal.ACCENT_HI else Pal.TEXT, bold = it.checked).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(14), 0)
                    background = round(normal, 14)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { _ ->
                        pop.dismiss()
                        onPick(i)
                    }
                    hover(this, normal, Pal.CARD_HI, 14)
                },
                LinearLayout.LayoutParams(match, dp(46)).apply { bottomMargin = dp(4) }
            )
        }
        // right edges aligned: the box usually sits near the right side of the screen
        pop.showAsDropDown(anchor, minOf(0, anchor.width - dp(width)), dp(6))
    }

    /** A plain-text tab; the selected one is brighter and underlined. */
    fun tab(title: String, active: Boolean, ic: Ic? = null, tint: Int = Pal.ACCENT, onClick: () -> Unit) =
        TextView(c).apply {
            text = title
            textSize = 16f
            typeface = medium
            setTextColor(if (active) Pal.TEXT else Pal.DIM)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(14), 0, dp(14), 0)
            if (ic != null) {
                setCompoundDrawablesRelative(icon(ic, tint, 16), null, null, null)
                compoundDrawablePadding = dp(7)
            }
            background = if (active) Underline(Pal.ACCENT, dp(14), dp(3)) else null
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setOnHoverListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER -> setTextColor(Pal.TEXT)
                    MotionEvent.ACTION_HOVER_EXIT -> setTextColor(if (active) Pal.TEXT else Pal.DIM)
                }
                false
            }
        }

    /** A few options side by side, one of them selected. */
    fun segment(options: List<String>, selected: Int, onPick: (Int) -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        background = round(Pal.CARD, 18)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        options.forEachIndexed { i, title ->
            val on = i == selected
            addView(
                text(title, 15f, if (on) Pal.DARK else Pal.TEXT, bold = on).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(46)
                    background = if (on) round(Pal.ACCENT, 14) else null
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onPick(i) }
                    if (!on) hover(this, 0, Pal.CARD_HI, 14)
                },
                LinearLayout.LayoutParams(0, wrap, 1f)
            )
        }
    }

    fun dp(v: Int) = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun round(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** An icon with fixed bounds, for use next to text. */
    fun icon(ic: Ic, color: Int, size: Int = 18): Drawable = Icon(ic, color).apply { setBounds(0, 0, dp(size), dp(size)) }

    fun text(s: CharSequence, size: Float, color: Int = Pal.TEXT, bold: Boolean = false) = TextView(c).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = medium
    }

    /** Rounds the corners of whatever the view draws; [fraction] of its width. */
    fun rounded(v: View, fraction: Float) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * fraction)
            }
        }
    }

    /** Pointer highlight: the controller ray has no "pressed" feedback of its own. */
    fun hover(v: View, normal: Int, hi: Int, radius: Int) {
        v.setOnHoverListener { x, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> x.background = round(hi, radius)
                MotionEvent.ACTION_HOVER_EXIT -> x.background = round(normal, radius)
            }
            false
        }
    }

    fun chip(
        title: String,
        active: Boolean,
        small: Boolean = false,
        ic: Ic? = null,
        tint: Int? = null,
        onClick: () -> Unit,
    ): TextView {
        val normal = if (active) Pal.ACCENT else Pal.CARD
        val fg = if (active) Pal.DARK else if (small) Pal.DIM else Pal.TEXT
        return TextView(c).apply {
            text = title
            textSize = if (small) 13f else 15f
            if (!small) typeface = medium
            setTextColor(fg)
            gravity = Gravity.CENTER
            minHeight = dp(if (small) 40 else 48)
            val pad = dp(if (small) 14 else 18)
            setPadding(pad, 0, pad, 0)
            if (ic != null) {
                val d = icon(ic, if (active) Pal.DARK else tint ?: fg, if (small) 16 else 18)
                setCompoundDrawablesRelative(d, null, null, null)
                compoundDrawablePadding = dp(if (title.isEmpty()) 0 else 8)
            }
            background = round(normal, 24)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            hover(this, normal, if (active) Pal.ACCENT_HI else Pal.CARD_HI, 24)
            layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dp(8) }
        }
    }

    /** A wide button: icon and label centred together. */
    fun button(title: String, ic: Ic?, primary: Boolean, onClick: () -> Unit): View {
        val normal = if (primary) Pal.ACCENT else Pal.CARD
        val fg = if (primary) Pal.DARK else Pal.TEXT
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(56)
            setPadding(dp(18), 0, dp(18), 0)
            background = round(normal, 18)
            if (ic != null) {
                addView(
                    ImageView(c).apply { setImageDrawable(Icon(ic, fg)) },
                    LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) }
                )
            }
            addView(text(title, 17f, fg, bold = true))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            hover(this, normal, if (primary) Pal.ACCENT_HI else Pal.CARD_HI, 18)
        }
    }

    /** A round button holding only an icon. */
    fun roundButton(ic: Ic, size: Int, bg: Int, fg: Int, onClick: (View) -> Unit) = ImageView(c).apply {
        setImageDrawable(Icon(ic, fg))
        val p = dp(size) / 4
        setPadding(p, p, p, p)
        background = oval(bg)
        isClickable = true
        setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** The coloured plate with an icon that starts a row. */
    fun bubble(ic: Ic, tint: Int) = ImageView(c).apply {
        setImageDrawable(Icon(ic, tint))
        val p = dp(10)
        setPadding(p, p, p, p)
        background = oval(Pal.wash(tint))
    }

    class Row(val view: LinearLayout, val title: TextView, val sub: TextView, val value: TextView)

    /**
     * A list row: icon plate, title, optional second line, optional value on the right.
     * Added to [parent] when given; clickable when [onClick] is given.
     */
    fun row(
        parent: ViewGroup?,
        ic: Ic?,
        title: String,
        sub: String? = null,
        value: String? = null,
        tint: Int = Pal.ACCENT,
        checked: Boolean = false,
        trailing: View? = null,
        onClick: (() -> Unit)? = null,
    ): Row {
        val v = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(62)
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = round(Pal.CARD, 18)
        }
        if (ic != null) {
            v.addView(bubble(ic, tint), LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) })
        }
        val t = text(title, 16f)
        val s = text(sub ?: "", 13f, Pal.DIM).apply {
            visibility = if (sub == null) View.GONE else View.VISIBLE
            setPadding(0, dp(2), 0, 0)
        }
        v.addView(
            LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(t)
                addView(s)
            },
            LinearLayout.LayoutParams(0, wrap, 1f)
        )
        val vt = text(value ?: "", 14f, Pal.DIM).apply {
            visibility = if (value == null) View.GONE else View.VISIBLE
            setPadding(dp(12), 0, 0, 0)
        }
        v.addView(vt)
        if (checked) {
            v.addView(
                ImageView(c).apply { setImageDrawable(Icon(Ic.CHECK, Pal.ACCENT)) },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(12) }
            )
        }
        if (trailing != null) v.addView(trailing)
        if (onClick != null) {
            v.isClickable = true
            v.isFocusable = true
            v.setOnClickListener { onClick() }
            hover(v, Pal.CARD, Pal.CARD_HI, 18)
        }
        parent?.addView(v, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(8) })
        return Row(v, t, s, vt)
    }

    fun toggle(parent: ViewGroup, ic: Ic, title: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val sw = Toggle(c).apply {
            on = value
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(28)).apply { leftMargin = dp(12) }
        }
        row(parent, ic, title, trailing = sw) {
            sw.on = !sw.on
            onChange(sw.on)
        }
    }

    fun section(parent: ViewGroup, title: String) {
        parent.addView(
            text(title.uppercase(), 12f, Pal.DIM, bold = true).apply {
                letterSpacing = 0.08f
                setPadding(dp(6), dp(14), 0, dp(8))
            }
        )
    }

    /**
     * A rounded panel over the dimmed screen: title, close button, scrolling body.
     * [build] fills the body and may keep the dialog to dismiss it.
     */
    fun sheet(title: String, width: Int = 660, build: (body: LinearLayout, d: Dialog) -> Unit): Dialog {
        val d = Dialog(c, R.style.Sheet)
        val panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Pal.SURFACE, 30)
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        val head = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            text(title, 21f, bold = true).apply { setPadding(dp(4), 0, dp(12), 0) },
            LinearLayout.LayoutParams(0, wrap, 1f)
        )
        val close = roundButton(Ic.CLOSE, 44, Pal.CARD, Pal.TEXT) { d.dismiss() }
        close.setOnHoverListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> v.background = oval(Pal.CARD_HI)
                MotionEvent.ACTION_HOVER_EXIT -> v.background = oval(Pal.CARD)
            }
            false
        }
        head.addView(close)
        panel.addView(head, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(12) })

        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(
            ScrollView(c).apply {
                isVerticalScrollBarEnabled = false
                addView(body)
            },
            LinearLayout.LayoutParams(match, wrap)
        )
        d.setContentView(panel, ViewGroup.LayoutParams(dp(width), wrap))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(dp(width), wrap)
            setDimAmount(0.65f)
        }
        d.setCanceledOnTouchOutside(true)
        build(body, d)
        d.show()
        return d
    }

    class Item(
        val ic: Ic?,
        val title: String,
        val sub: String? = null,
        val checked: Boolean = false,
        val tint: Int = Pal.ACCENT,
    )

    /** A sheet with a list to pick one item from. */
    fun list(title: String, items: List<Item>, onPick: (Int) -> Unit) = sheet(title, 560) { body, d ->
        items.forEachIndexed { i, it ->
            row(body, it.ic, it.title, sub = it.sub, tint = it.tint, checked = it.checked) {
                d.dismiss()
                onPick(i)
            }
        }
    }

    fun prompt(title: String, initial: String, allowEmpty: Boolean, ok: String, cancel: String, done: (String) -> Unit) =
        sheet(title, 560) { body, d ->
            val input = EditText(c).apply {
                setText(initial)
                isSingleLine = true
                textSize = 17f
                setTextColor(Pal.TEXT)
                background = round(Pal.CARD, 16)
                setPadding(dp(16), 0, dp(16), 0)
                setSelection(text.length)
            }
            body.addView(input, LinearLayout.LayoutParams(match, dp(56)))
            val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            bar.addView(
                button(cancel, null, false) { d.dismiss() },
                LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dp(10) }
            )
            bar.addView(
                button(ok, Ic.CHECK, true) {
                    val s = input.text.toString().trim()
                    if (s.isNotEmpty() || allowEmpty) {
                        d.dismiss()
                        done(s)
                    }
                },
                LinearLayout.LayoutParams(0, wrap, 1f)
            )
            body.addView(bar, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(14) })
        }
}
