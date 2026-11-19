package app.shelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.GridView

/**
 * A grid whose tiles can be picked up with a long press and dropped somewhere else.
 *
 * While a tile is carried, a copy of it follows the pointer, a bar shows where it would
 * land, and the grid scrolls by itself when the pointer nears its top or bottom edge.
 * The grid only reports "tile [from] dropped at [insertAt]"; what that means for the data
 * is up to the owner.
 */
class DragGrid(c: Context) : GridView(c) {

    /** Called when the tile lands: [from] is where it was, [insertAt] the index it should go before. */
    var onDrop: (from: Int, insertAt: Int) -> Unit = { _, _ -> }

    /** Called when the carrying is over, dropped or not. */
    var onDragEnd: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val edge = 72 * density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.ACCENT }
    private val shotPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 235 }

    private var dragging = false
    private var from = -1
    private var insertAt = -1
    private var shot: Bitmap? = null
    private var lastX = 0f
    private var lastY = 0f
    private var grabX = 0f
    private var grabY = 0f

    // where the "it lands here" bar is drawn
    private var barX = 0f
    private var barTop = 0f
    private var barBottom = 0f

    val isDragging: Boolean get() = dragging

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastX = ev.x
        lastY = ev.y
        if (!dragging) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                updateTarget()
                invalidate()
            }
            MotionEvent.ACTION_UP -> finish(drop = true)
            MotionEvent.ACTION_CANCEL -> finish(drop = false)
        }
        return true
    }

    /** Picks up the tile at [position]; call it from the long-click listener. */
    fun startDrag(position: Int) {
        val v = getChildAt(position - firstVisiblePosition) ?: return
        if (v.width <= 0 || v.height <= 0) return
        shot = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
        grabX = lastX - v.left
        grabY = lastY - v.top
        from = position
        insertAt = -1
        dragging = true
        v.alpha = 0.25f
        parent?.requestDisallowInterceptTouchEvent(true)
        // the list must forget this gesture, or it would also scroll or click with it
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, lastX, lastY, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        updateTarget()
        postOnAnimation(scroller)
        invalidate()
    }

    private fun updateTarget() {
        val n = childCount
        if (n == 0) return
        val pos = pointToPosition(lastX.toInt(), lastY.toInt())
        val gap = horizontalSpacing / 2f
        if (pos != INVALID_POSITION) {
            val v = getChildAt(pos - firstVisiblePosition) ?: return
            val after = lastX > v.left + v.width / 2f
            insertAt = if (after) pos + 1 else pos
            barX = if (after) v.right + gap else v.left - gap
            barTop = v.top.toFloat()
            barBottom = v.bottom.toFloat()
            return
        }
        // between tiles or past the last one: only "after the very last tile" is a new answer
        val last = getChildAt(n - 1)
        val pastEnd = lastVisiblePosition == count - 1 &&
            (lastY > last.bottom || (lastY >= last.top && lastX > last.right))
        if (pastEnd) {
            insertAt = count
            barX = last.right + gap
            barTop = last.top.toFloat()
            barBottom = last.bottom.toFloat()
        }
    }

    /** Scrolls while the pointer is held near the top or the bottom, faster the closer to the edge. */
    private val scroller = object : Runnable {
        override fun run() {
            if (!dragging) return
            val dy = when {
                lastY < edge -> -speed(edge - lastY)
                lastY > height - edge -> speed(lastY - (height - edge))
                else -> 0
            }
            if (dy != 0 && canScrollList(dy)) {
                scrollListBy(dy)
                updateTarget()
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    private fun speed(depth: Float) = ((depth / edge).coerceIn(0.15f, 1f) * 22 * density).toInt()

    private fun finish(drop: Boolean) {
        dragging = false
        removeCallbacks(scroller)
        shot?.recycle()
        shot = null
        // dropping right before or right after itself changes nothing
        if (drop && insertAt >= 0 && insertAt != from && insertAt != from + 1) onDrop(from, insertAt)
        from = -1
        insertAt = -1
        onDragEnd()
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!dragging) return
        if (insertAt >= 0) {
            val w = 3 * density
            val inset = 14 * density
            canvas.drawRoundRect(barX - w, barTop + inset, barX + w, barBottom - inset * 3, w, w, barPaint)
        }
        val b = shot ?: return
        canvas.save()
        canvas.translate(lastX - grabX, lastY - grabY)
        canvas.scale(1.06f, 1.06f, b.width / 2f, b.height / 2f)
        canvas.drawBitmap(b, 0f, 0f, shotPaint)
        canvas.restore()
    }
}
