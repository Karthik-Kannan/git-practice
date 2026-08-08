package com.scrappy.receipts

import kotlin.math.roundToInt

/**
 * How to rotate an image, and where things land once you have.
 *
 * Mirrors what `Bitmap.createBitmap(src, .., matrix, ..)` does: rotate about the
 * origin, then translate so the rotated content starts at (0, 0). Keeping that
 * arithmetic here — free of android.graphics — means the mapping is unit-testable
 * on the JVM, which is where every coordinate bug in this app has hidden.
 */
data class RotationPlan(
    val degrees: Float,
    val outputWidth: Int,
    val outputHeight: Int,
    val offsetX: Float,
    val offsetY: Float
) {
    private val radians = Math.toRadians(degrees.toDouble()).toFloat()

    fun map(x: Float, y: Float): Point {
        val rotated = OrientedBox.rotate(x, y, radians, 0f, 0f)
        return Point(rotated.x + offsetX, rotated.y + offsetY)
    }

    fun map(box: OrientedBox): Bounds =
        OrientedBox.boundsOf(box.corners().map { map(it.x, it.y) })
}

/** Everything needed to turn one still into the two crops we OCR. */
data class CropPlan(
    val rotation: RotationPlan,
    val receipt: Bounds,
    val fields: Map<DetectedClass, Bounds>
)

object Deskew {

    /** Padding added around a field crop so glyphs aren't clipped at the edge. */
    private const val FIELD_PADDING = 0.04f

    /**
     * Degrees to rotate the image by so [receipt] stands upright with its long
     * edge vertical — the orientation OCR engines expect.
     */
    fun uprightRotationDegrees(receipt: OrientedBox): Float =
        (-receipt.longEdgeVertical().angleRadians.degrees()).normalizeDegrees()

    /** Where the image lands after rotating [degrees] about the origin. */
    fun planRotation(sourceWidth: Int, sourceHeight: Int, degrees: Float): RotationPlan {
        val radians = Math.toRadians(degrees.toDouble()).toFloat()
        val corners = listOf(
            0f to 0f,
            sourceWidth.toFloat() to 0f,
            sourceWidth.toFloat() to sourceHeight.toFloat(),
            0f to sourceHeight.toFloat()
        ).map { (x, y) -> OrientedBox.rotate(x, y, radians, 0f, 0f) }

        val minX = corners.minOf { it.x }
        val minY = corners.minOf { it.y }
        val maxX = corners.maxOf { it.x }
        val maxY = corners.maxOf { it.y }

        return RotationPlan(
            degrees = degrees,
            outputWidth = (maxX - minX).roundToInt().coerceAtLeast(1),
            outputHeight = (maxY - minY).roundToInt().coerceAtLeast(1),
            offsetX = -minX,
            offsetY = -minY
        )
    }

    /**
     * Straighten the receipt and locate the field blocks inside the straightened
     * image. Field boxes are padded and clamped to the receipt, so a crop can
     * never wander onto the table.
     */
    fun plan(
        detections: List<OrientedBox>,
        sourceWidth: Int,
        sourceHeight: Int
    ): CropPlan? {
        val receipt = detections.best(DetectedClass.RECEIPT) ?: return null

        val rotation = planRotation(sourceWidth, sourceHeight, uprightRotationDegrees(receipt))
        val receiptBounds = rotation.map(receipt)
            .clampedTo(rotation.outputWidth, rotation.outputHeight)

        val fields = listOf(DetectedClass.MERCHANT_BLOCK, DetectedClass.TOTALS_BLOCK)
            .mapNotNull { label ->
                val field = detections.best(label)?.takeIf { receipt.contains(it) }
                    ?: return@mapNotNull null
                val padded = rotation.map(field)
                    .padded(FIELD_PADDING)
                    .intersect(receiptBounds)
                    ?: return@mapNotNull null
                label to padded
            }
            .toMap()

        return CropPlan(rotation, receiptBounds, fields)
    }
}

// --- Bounds helpers ------------------------------------------------------

fun Bounds.padded(fraction: Float): Bounds {
    val padX = (width * fraction).roundToInt()
    val padY = (height * fraction).roundToInt()
    return Bounds(left - padX, top - padY, right + padX, bottom + padY)
}

fun Bounds.clampedTo(width: Int, height: Int) = Bounds(
    left = left.coerceIn(0, width),
    top = top.coerceIn(0, height),
    right = right.coerceIn(0, width),
    bottom = bottom.coerceIn(0, height)
)

fun Bounds.intersect(other: Bounds): Bounds? {
    val result = Bounds(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = minOf(right, other.right),
        bottom = minOf(bottom, other.bottom)
    )
    return if (result.width <= 0 || result.height <= 0) null else result
}
