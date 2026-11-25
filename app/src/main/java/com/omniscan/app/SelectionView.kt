package com.omniscan.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Transparent overlay on top of the camera preview. The user drags a box over the text
 * they want read: drag inside to move it, drag a corner to resize it, or drag on an
 * empty spot to draw a new box.
 */
class SelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private var ready = false

    private val minSize = 72 * density
    private val touchRadius = 44 * density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.WHITE
    }
    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private enum class Mode { NONE, MOVE, ANCHOR }
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var anchorX = 0f
    private var anchorY = 0f

    var onChanged: (() -> Unit)? = null

    /** Selected box as fractions of this view: left, top, right, bottom (0..1). */
    fun fractions(): FloatArray {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        return floatArrayOf(rect.left / w, rect.top / h, rect.right / w, rect.bottom / h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (!ready) {
            rect.set(w * 0.1f, h * 0.25f, w * 0.9f, h * 0.75f)
            ready = true
        } else {
            rect.left = rect.left.coerceIn(0f, w.toFloat())
            rect.right = rect.right.coerceIn(0f, w.toFloat())
            rect.top = rect.top.coerceIn(0f, h.toFloat())
            rect.bottom = rect.bottom.coerceIn(0f, h.toFloat())
        }
        onChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, rect.top, dim)
        canvas.drawRect(0f, rect.bottom, w, h, dim)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, dim)
        canvas.drawRect(rect, border)
        val r = 9 * density
        canvas.drawCircle(rect.left, rect.top, r, handle)
        canvas.drawCircle(rect.right, rect.top, r, handle)
        canvas.drawCircle(rect.left, rect.bottom, r, handle)
        canvas.drawCircle(rect.right, rect.bottom, r, handle)
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= touchRadius * touchRadius
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val x = e.x.coerceIn(0f, w)
        val y = e.y.coerceIn(0f, h)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = when {
                    near(x, y, rect.left, rect.top) -> { anchorX = rect.right; anchorY = rect.bottom; Mode.ANCHOR }
                    near(x, y, rect.right, rect.top) -> { anchorX = rect.left; anchorY = rect.bottom; Mode.ANCHOR }
                    near(x, y, rect.left, rect.bottom) -> { anchorX = rect.right; anchorY = rect.top; Mode.ANCHOR }
                    near(x, y, rect.right, rect.bottom) -> { anchorX = rect.left; anchorY = rect.top; Mode.ANCHOR }
                    rect.contains(x, y) -> Mode.MOVE
                    else -> {
                        anchorX = x
                        anchorY = y
                        rect.set(x, y, x, y)
                        Mode.ANCHOR
                    }
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.MOVE -> {
                        val bw = rect.width()
                        val bh = rect.height()
                        val nl = (rect.left + (x - lastX)).coerceIn(0f, w - bw)
                        val nt = (rect.top + (y - lastY)).coerceIn(0f, h - bh)
                        rect.set(nl, nt, nl + bw, nt + bh)
                    }
                    Mode.ANCHOR -> rect.set(
                        minOf(anchorX, x), minOf(anchorY, y),
                        maxOf(anchorX, x), maxOf(anchorY, y)
                    )
                    Mode.NONE -> {}
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode != Mode.NONE) {
                    enforceMinSize(w, h)
                    mode = Mode.NONE
                    invalidate()
                    onChanged?.invoke()
                    if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun enforceMinSize(w: Float, h: Float) {
        if (rect.width() < minSize) {
            val cx = rect.centerX().coerceIn(minSize / 2, w - minSize / 2)
            rect.left = cx - minSize / 2
            rect.right = cx + minSize / 2
        }
        if (rect.height() < minSize) {
            val cy = rect.centerY().coerceIn(minSize / 2, h - minSize / 2)
            rect.top = cy - minSize / 2
            rect.bottom = cy + minSize / 2
        }
    }
}
