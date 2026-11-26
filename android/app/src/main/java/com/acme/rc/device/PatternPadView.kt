package com.acme.rc.device

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * A 3x3 lock-pattern pad: drag through the dots, the connecting lines are drawn,
 * and the selected dot numbers are exposed as the sequence (1-9 row-major).
 *
 * Android's real LockPatternView also inserts an unvisited dot that lies between
 * two consecutive dots; the same rule is applied here.
 */
class PatternPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onPatternChanged: ((String) -> Unit)? = null

    val sequence: String
        get() = dots.joinToString("")

    private val dots = mutableListOf<Int>()
    private var active = false
    private var touchX = 0f
    private var touchY = 0f

    private val dotRadius = dp(9f)
    private val hitRadius = dp(30f)
    private val lineWidth = dp(3.5f)
    private val backgroundRect = RectF()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF8FAFC.toInt()
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF98A2B3.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF98A2B3.toInt()
        style = Paint.Style.FILL
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2563EB.toInt()
        style = Paint.Style.FILL
    }
    private val selectedHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x332563EB.toInt()
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2563EB.toInt()
        style = Paint.Style.STROKE
        strokeWidth = lineWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dragPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x992563EB.toInt()
        style = Paint.Style.STROKE
        strokeWidth = lineWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        isClickable = true
        contentDescription = "Pattern lock. Draw a pattern."
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val size = if (width > 0) width else dp(280f).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundRect.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cell = size / 3f

        canvas.drawRoundRect(backgroundRect, dp(16f), dp(16f), backgroundPaint)

        val points = dots.map { dotPoint(it, cell) }
        if (points.isNotEmpty()) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (point in points.drop(1)) lineTo(point.x, point.y)
                if (active) lineTo(touchX, touchY)
            }
            canvas.drawPath(path, if (active) dragPaint else linePaint)
        }

        for (dot in 1..9) {
            val point = dotPoint(dot, cell)
            val selected = dot in dots
            if (selected) {
                canvas.drawCircle(point.x, point.y, dotRadius * 2.2f, selectedHaloPaint)
                canvas.drawCircle(point.x, point.y, dotRadius, selectedPaint)
            } else {
                canvas.drawCircle(point.x, point.y, dotRadius, dotPaint)
                canvas.drawCircle(point.x, point.y, dotRadius * 0.32f, innerDotPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val size = min(width, height).toFloat()
        if (size <= 0f) return false
        val cell = size / 3f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dot = findDot(event.x, event.y, cell) ?: return false
                dots.clear()
                dots.add(dot)
                active = true
                touchX = event.x
                touchY = event.y
                updateDescription()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!active) return false
                touchX = event.x
                touchY = event.y
                val dot = findDot(event.x, event.y, cell)
                if (dot != null && dot !in dots) addDotWithMiddle(dot)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!active) return false
                active = false
                val dot = findDot(event.x, event.y, cell)
                if (dot != null && dot !in dots) addDotWithMiddle(dot)
                onPatternChanged?.invoke(sequence)
                updateDescription()
                performClick()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun clear() {
        dots.clear()
        active = false
        onPatternChanged?.invoke("")
        updateDescription()
        invalidate()
    }

    private fun addDotWithMiddle(dot: Int) {
        val last = dots.lastOrNull()
        if (last != null) {
            middleDot(last, dot)?.let { middle ->
                if (middle !in dots) dots.add(middle)
            }
        }
        dots.add(dot)
    }

    /** The dot between two dots on a straight 3-dot line, as LockPatternView adds. */
    private fun middleDot(from: Int, to: Int): Int? {
        val fromRow = (from - 1) / 3
        val fromCol = (from - 1) % 3
        val toRow = (to - 1) / 3
        val toCol = (to - 1) % 3
        val rowDelta = toRow - fromRow
        val colDelta = toCol - fromCol
        val straight = (rowDelta == 0 && abs(colDelta) == 2) ||
            (colDelta == 0 && abs(rowDelta) == 2) ||
            (abs(rowDelta) == 2 && abs(colDelta) == 2)
        if (!straight) return null
        return ((fromRow + toRow) / 2) * 3 + ((fromCol + toCol) / 2) + 1
    }

    private fun findDot(x: Float, y: Float, cell: Float): Int? {
        for (dot in 1..9) {
            val point = dotPoint(dot, cell)
            val dx = x - point.x
            val dy = y - point.y
            if (dx * dx + dy * dy <= hitRadius * hitRadius) return dot
        }
        return null
    }

    private fun dotPoint(dot: Int, cell: Float): PointF {
        val index = dot - 1
        val row = index / 3
        val col = index % 3
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        return PointF(
            left + (col + 0.5f) * cell,
            top + (row + 0.5f) * cell
        )
    }

    private fun updateDescription() {
        contentDescription = if (dots.isEmpty()) {
            "Pattern lock. Draw a pattern."
        } else {
            "Pattern lock. ${dots.size} dots selected."
        }
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
