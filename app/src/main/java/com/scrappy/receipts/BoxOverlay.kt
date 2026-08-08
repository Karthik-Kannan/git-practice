package com.scrappy.receipts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws the OCR boxes over the camera preview so you can see, live, what the app
 * is actually reading. Lines that contain money are highlighted.
 */
class BoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Style {
        /** Receipt text. */
        TEXT,

        /** Receipt text carrying an amount. */
        MONEY,

        /** Read, but judged not to be part of the receipt. */
        REJECTED
    }

    data class Box(val rect: Rect, val style: Style)

    private var boxes: List<Box> = emptyList()
    private var sourceWidth = 0
    private var sourceHeight = 0

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.argb(90, 255, 255, 255)
    }

    private val moneyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#FF4ADE80")
    }

    private val moneyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(38, 74, 222, 128)
    }

    /** Faint, so you can see what was discarded without it competing for attention. */
    private val rejectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(70, 248, 113, 113)
    }

    /** Viewfinder brackets around the detected receipt. */
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }

    private var frame: Rect? = null
    private var frameProgress = 0f

    private val scratch = RectF()

    /**
     * @param sourceWidth  width of the analysed frame, already corrected for rotation
     * @param sourceHeight height of the analysed frame, already corrected for rotation
     */
    fun update(newBoxes: List<Box>, sourceWidth: Int, sourceHeight: Int) {
        this.boxes = newBoxes
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
        postInvalidateOnAnimation()
    }

    /**
     * @param progress 0..1 toward auto-capture; the brackets warm from white to
     *                 the accent colour as it fills.
     */
    fun setFrame(bounds: Rect?, progress: Float) {
        frame = bounds
        frameProgress = progress.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    fun clear() {
        frame = null
        frameProgress = 0f
        update(emptyList(), sourceWidth, sourceHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sourceWidth <= 0 || sourceHeight <= 0) return

        // PreviewView defaults to FILL_CENTER, so match that mapping: scale to cover,
        // then centre the overflow.
        val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val offsetX = (width - sourceWidth * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f
        val radius = dp(3f)

        frame?.let { drawBrackets(canvas, it, scale, offsetX, offsetY) }
        if (boxes.isEmpty()) return

        for (box in boxes) {
            scratch.set(
                box.rect.left * scale + offsetX,
                box.rect.top * scale + offsetY,
                box.rect.right * scale + offsetX,
                box.rect.bottom * scale + offsetY
            )
            when (box.style) {
                Style.MONEY -> {
                    canvas.drawRoundRect(scratch, radius, radius, moneyFill)
                    canvas.drawRoundRect(scratch, radius, radius, moneyStroke)
                }
                Style.TEXT -> canvas.drawRoundRect(scratch, radius, radius, textPaint)
                Style.REJECTED -> canvas.drawRoundRect(scratch, radius, radius, rejectedPaint)
            }
        }
    }

    private fun drawBrackets(canvas: Canvas, rect: Rect, scale: Float, dx: Float, dy: Float) {
        val pad = dp(10f)
        val left = rect.left * scale + dx - pad
        val top = rect.top * scale + dy - pad
        val right = rect.right * scale + dx + pad
        val bottom = rect.bottom * scale + dy + pad

        // Corner arms, sized off the shorter edge so they stay proportional.
        val arm = minOf(right - left, bottom - top) * 0.16f
        if (arm <= 0f) return

        framePaint.color = blend(Color.WHITE, ACCENT, frameProgress)
        framePaint.alpha = (140 + 115 * frameProgress).toInt().coerceIn(0, 255)

        // Top-left, top-right, bottom-left, bottom-right.
        canvas.drawLine(left, top, left + arm, top, framePaint)
        canvas.drawLine(left, top, left, top + arm, framePaint)
        canvas.drawLine(right, top, right - arm, top, framePaint)
        canvas.drawLine(right, top, right, top + arm, framePaint)
        canvas.drawLine(left, bottom, left + arm, bottom, framePaint)
        canvas.drawLine(left, bottom, left, bottom - arm, framePaint)
        canvas.drawLine(right, bottom, right - arm, bottom, framePaint)
        canvas.drawLine(right, bottom, right, bottom - arm, framePaint)
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        )
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        val ACCENT = Color.parseColor("#FF4ADE80")
    }
}
