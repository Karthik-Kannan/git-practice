package com.scrappy.receipts

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

data class Point(val x: Float, val y: Float)

enum class DetectedClass { RECEIPT, MERCHANT_BLOCK, TOTALS_BLOCK }

/**
 * A rotated box, as YOLO-OBB emits them: centre, size, angle.
 *
 * The angle is the whole reason for using oriented boxes here. An axis-aligned
 * box around a receipt lying at 40° still contains most of the table, and — more
 * importantly — throws away the one number that lets us straighten the receipt
 * before OCR. Sideways receipts are what make ML Kit merge text across rows.
 *
 * Angles are clockwise-positive in image coordinates (y grows downward).
 */
data class OrientedBox(
    val label: DetectedClass,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val angleRadians: Float,
    val confidence: Float
) {

    /** The same box described so its long edge is the height. */
    fun longEdgeVertical(): OrientedBox =
        if (height >= width) this
        else copy(
            width = height,
            height = width,
            angleRadians = angleRadians + (PI / 2).toFloat()
        )

    fun corners(): List<Point> {
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        return listOf(
            -halfWidth to -halfHeight,
            halfWidth to -halfHeight,
            halfWidth to halfHeight,
            -halfWidth to halfHeight
        ).map { (x, y) -> rotate(x, y, angleRadians, centerX, centerY) }
    }

    fun axisAlignedBounds(): Bounds = boundsOf(corners())

    companion object {
        fun rotate(x: Float, y: Float, radians: Float, aboutX: Float, aboutY: Float): Point {
            val c = cos(radians)
            val s = sin(radians)
            return Point(x * c - y * s + aboutX, x * s + y * c + aboutY)
        }

        /**
         * Rounds outward. These bounds become crop rectangles, so erring wide
         * costs a few background pixels while erring narrow clips a glyph.
         */
        fun boundsOf(points: List<Point>): Bounds = Bounds(
            left = floor(points.minOf { it.x }).toInt(),
            top = floor(points.minOf { it.y }).toInt(),
            right = ceil(points.maxOf { it.x }).toInt(),
            bottom = ceil(points.maxOf { it.y }).toInt()
        )
    }
}

/**
 * Finds receipts and the two regions we care about within them.
 *
 * Implementations run a model; [NullDetector] lets the rest of the pipeline be
 * built and tested before one exists.
 */
interface ReceiptDetector {
    fun detect(width: Int, height: Int, rgb: ByteArray): List<OrientedBox>
    fun close() {}
}

/** Stands in until a trained model ships. Finds nothing, never crashes. */
object NullDetector : ReceiptDetector {
    override fun detect(width: Int, height: Int, rgb: ByteArray): List<OrientedBox> = emptyList()
}

/** Picks the most confident box for a class, ignoring anything below [minConfidence]. */
fun List<OrientedBox>.best(label: DetectedClass, minConfidence: Float = 0.25f): OrientedBox? =
    filter { it.label == label && it.confidence >= minConfidence }.maxByOrNull { it.confidence }

/** True when [inner] sits (mostly) inside [outer] — a field block within its receipt. */
fun OrientedBox.contains(inner: OrientedBox, slack: Float = 0.15f): Boolean {
    val outerBounds = axisAlignedBounds()
    val innerBounds = inner.axisAlignedBounds()
    val padX = (outerBounds.width * slack).toInt()
    val padY = (outerBounds.height * slack).toInt()
    return innerBounds.left >= outerBounds.left - padX &&
            innerBounds.top >= outerBounds.top - padY &&
            innerBounds.right <= outerBounds.right + padX &&
            innerBounds.bottom <= outerBounds.bottom + padY
}

internal fun Float.degrees(): Float = (this * 180f / PI).toFloat()

/** Wraps to (-180, 180]. */
internal fun Float.normalizeDegrees(): Float {
    var d = this % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return if (abs(d) < 1e-4f) 0f else d
}
