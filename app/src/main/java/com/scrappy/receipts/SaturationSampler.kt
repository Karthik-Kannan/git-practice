package com.scrappy.receipts

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import kotlin.math.abs

/**
 * Mean colour saturation for a region of the frame, read straight out of the
 * YUV chroma planes.
 *
 * Black-on-white thermal print leaves U and V both parked at ~128, so its
 * saturation is near zero; coloured print doesn't. Chroma is already quarter
 * resolution and we subsample it further, so this costs far less than
 * converting frames to RGB would.
 */
class SaturationSampler private constructor(
    private val grid: ByteArray,
    private val gridWidth: Int,
    private val gridHeight: Int,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val rotationDegrees: Int
) {

    /** @param box in the upright coordinate space ML Kit reports boxes in. */
    fun meanSaturation(box: Rect): Int {
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val rotatedWidth = if (rotated) sourceHeight else sourceWidth
        val rotatedHeight = if (rotated) sourceWidth else sourceHeight

        var total = 0
        var count = 0

        for (i in 0 until SAMPLES) {
            for (j in 0 until SAMPLES) {
                val x = box.left + box.width() * (2 * i + 1) / (2 * SAMPLES)
                val y = box.top + box.height() * (2 * j + 1) / (2 * SAMPLES)

                // Undo the rotation ML Kit's coordinates already have applied.
                val sourceX: Int
                val sourceY: Int
                when (rotationDegrees) {
                    90 -> { sourceX = y; sourceY = rotatedWidth - 1 - x }
                    180 -> { sourceX = rotatedWidth - 1 - x; sourceY = rotatedHeight - 1 - y }
                    270 -> { sourceX = rotatedHeight - 1 - y; sourceY = x }
                    else -> { sourceX = x; sourceY = y }
                }
                if (sourceX < 0 || sourceY < 0) continue
                if (sourceX >= sourceWidth || sourceY >= sourceHeight) continue

                val gx = sourceX * gridWidth / sourceWidth
                val gy = sourceY * gridHeight / sourceHeight
                total += grid[gy * gridWidth + gx].toInt() and 0xFF
                count++
            }
        }

        return if (count == 0) 0 else total / count
    }

    companion object {
        /** Lattice of probe points per line box. */
        private const val SAMPLES = 5

        /** Subsampling applied on top of chroma's existing 2x. */
        private const val STEP = 2

        fun from(image: Image, rotationDegrees: Int): SaturationSampler? {
            if (image.format != ImageFormat.YUV_420_888) return null
            if (image.planes.size < 3) return null

            val gridWidth = image.width / 2 / STEP
            val gridHeight = image.height / 2 / STEP
            if (gridWidth <= 0 || gridHeight <= 0) return null

            val u = image.planes[1]
            val v = image.planes[2]
            val uBuffer = u.buffer
            val vBuffer = v.buffer
            val grid = ByteArray(gridWidth * gridHeight)

            // Odd strides and short buffers vary by device; a bad frame just
            // means we skip colour filtering for it.
            return runCatching {
                for (gy in 0 until gridHeight) {
                    val uRow = gy * STEP * u.rowStride
                    val vRow = gy * STEP * v.rowStride
                    for (gx in 0 until gridWidth) {
                        val column = gx * STEP
                        val uValue = (uBuffer.get(uRow + column * u.pixelStride).toInt() and 0xFF) - 128
                        val vValue = (vBuffer.get(vRow + column * v.pixelStride).toInt() and 0xFF) - 128
                        grid[gy * gridWidth + gx] = maxOf(abs(uValue), abs(vValue))
                            .coerceAtMost(255)
                            .toByte()
                    }
                }
                SaturationSampler(
                    grid, gridWidth, gridHeight,
                    image.width, image.height, rotationDegrees
                )
            }.getOrNull()
        }
    }
}
