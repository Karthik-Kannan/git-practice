package com.scrappy.receipts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a downscaled photo and lets you drag a rectangle over it, reporting that
 * rectangle back in **source image** pixels so a region can be decoded at full
 * resolution.
 */
class CropSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var sourceWidth = 0
    private var sourceHeight = 0

    private var selection: RectF? = null
    private var anchorX = 0f
    private var anchorY = 0f

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.parseColor("#FF4ADE80")
    }

    private val shade = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 0, 0, 0)
    }

    fun setImage(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int) {
        this.bitmap = bitmap
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
        selection = null
        invalidate()
    }

    fun clearSelection() {
        selection = null
        invalidate()
    }

    /** The dragged rectangle in source-image pixels, or null if nothing is selected. */
    fun selectionInSource(): Rect? {
        val rect = selection ?: return null
        val image = bitmap ?: return null
        if (rect.width() < 8f || rect.height() < 8f) return null

        val scale = displayScale(image)
        if (scale <= 0f) return null
        val offsetX = (width - image.width * scale) / 2f
        val offsetY = (height - image.height * scale) / 2f

        // View -> displayed bitmap -> original source pixels.
        val toSourceX = sourceWidth.toFloat() / image.width
        val toSourceY = sourceHeight.toFloat() / image.height

        val left = ((rect.left - offsetX) / scale * toSourceX).toInt()
        val top = ((rect.top - offsetY) / scale * toSourceY).toInt()
        val right = ((rect.right - offsetX) / scale * toSourceX).toInt()
        val bottom = ((rect.bottom - offsetY) / scale * toSourceY).toInt()

        val clamped = Rect(
            left.coerceIn(0, sourceWidth),
            top.coerceIn(0, sourceHeight),
            right.coerceIn(0, sourceWidth),
            bottom.coerceIn(0, sourceHeight)
        )
        return if (clamped.width() < 4 || clamped.height() < 4) null else clamped
    }

    private fun displayScale(image: Bitmap): Float =
        min(width.toFloat() / image.width, height.toFloat() / image.height)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anchorX = event.x
                anchorY = event.y
                selection = RectF(anchorX, anchorY, anchorX, anchorY)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                selection = RectF(
                    min(anchorX, event.x),
                    min(anchorY, event.y),
                    max(anchorX, event.x),
                    max(anchorY, event.y)
                )
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            else -> return false
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return

        val scale = displayScale(image)
        val offsetX = (width - image.width * scale) / 2f
        val offsetY = (height - image.height * scale) / 2f

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(image, 0f, 0f, null)
        canvas.restore()

        val rect = selection ?: return
        // Dim everything outside the selection so the crop is obvious.
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, shade)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), shade)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, shade)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, shade)
        canvas.drawRect(rect, outline)
    }
}
