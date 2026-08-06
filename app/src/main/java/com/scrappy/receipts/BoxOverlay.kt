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

    data class Box(val rect: Rect, val hasMoney: Boolean)

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

    fun clear() = update(emptyList(), sourceWidth, sourceHeight)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty() || sourceWidth <= 0 || sourceHeight <= 0) return

        // PreviewView defaults to FILL_CENTER, so match that mapping: scale to cover,
        // then centre the overflow.
        val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val offsetX = (width - sourceWidth * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f
        val radius = dp(3f)

        for (box in boxes) {
            scratch.set(
                box.rect.left * scale + offsetX,
                box.rect.top * scale + offsetY,
                box.rect.right * scale + offsetX,
                box.rect.bottom * scale + offsetY
            )
            if (box.hasMoney) {
                canvas.drawRoundRect(scratch, radius, radius, moneyFill)
                canvas.drawRoundRect(scratch, radius, radius, moneyStroke)
            } else {
                canvas.drawRoundRect(scratch, radius, radius, textPaint)
            }
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
