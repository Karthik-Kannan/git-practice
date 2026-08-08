package com.scrappy.receipts.ocr

import kotlin.math.roundToInt

/**
 * A plain ARGB image.
 *
 * Deliberately free of android.graphics so the entire OCR pipeline — resizing,
 * normalisation, box maths, CTC decoding — can be run and tested on the JVM
 * against the real models before it ever touches a device.
 */
class Raster(val width: Int, val height: Int, val pixels: IntArray) {

    init {
        require(pixels.size == width * height) {
            "expected ${width * height} pixels, got ${pixels.size}"
        }
    }

    /** Exact, interpolation-free rotation. Positive turns are clockwise. */
    fun rotatedQuarterTurns(turns: Int): Raster {
        val t = ((turns % 4) + 4) % 4
        if (t == 0) return this

        val (newWidth, newHeight) = if (t == 2) width to height else height to width
        val out = IntArray(newWidth * newHeight)

        for (y in 0 until newHeight) {
            for (x in 0 until newWidth) {
                val sourceX: Int
                val sourceY: Int
                when (t) {
                    1 -> { sourceX = y; sourceY = height - 1 - x }
                    2 -> { sourceX = width - 1 - x; sourceY = height - 1 - y }
                    else -> { sourceX = width - 1 - y; sourceY = x }
                }
                out[y * newWidth + x] = pixels[sourceY * width + sourceX]
            }
        }
        return Raster(newWidth, newHeight, out)
    }

    fun cropped(left: Int, top: Int, right: Int, bottom: Int): Raster {
        val l = left.coerceIn(0, width)
        val t = top.coerceIn(0, height)
        val r = right.coerceIn(l + 1, width)
        val b = bottom.coerceIn(t + 1, height)

        val w = r - l
        val h = b - t
        val out = IntArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(pixels, (t + y) * width + l, out, y * w, w)
        }
        return Raster(w, h, out)
    }

    fun resized(newWidth: Int, newHeight: Int): Raster {
        if (newWidth == width && newHeight == height) return this
        val w = newWidth.coerceAtLeast(1)
        val h = newHeight.coerceAtLeast(1)
        val out = IntArray(w * h)

        val scaleX = width.toFloat() / w
        val scaleY = height.toFloat() / h

        for (y in 0 until h) {
            val sourceY = ((y + 0.5f) * scaleY - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = sourceY.toInt()
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val fracY = sourceY - y0

            for (x in 0 until w) {
                val sourceX = ((x + 0.5f) * scaleX - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = sourceX.toInt()
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val fracX = sourceX - x0

                out[y * w + x] = blend(
                    pixels[y0 * width + x0], pixels[y0 * width + x1],
                    pixels[y1 * width + x0], pixels[y1 * width + x1],
                    fracX, fracY
                )
            }
        }
        return Raster(w, h, out)
    }

    /**
     * Planar CHW float tensor in RGB order, normalised as `(v/255 - mean) / std`
     * — the layout every PP-OCR model expects.
     */
    fun toChwFloat(mean: FloatArray, std: FloatArray): FloatArray {
        val plane = width * height
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = (((p ushr 16 and 0xFF) / 255f) - mean[0]) / std[0]
            out[plane + i] = (((p ushr 8 and 0xFF) / 255f) - mean[1]) / std[1]
            out[2 * plane + i] = (((p and 0xFF) / 255f) - mean[2]) / std[2]
        }
        return out
    }

    private fun blend(
        topLeft: Int, topRight: Int, bottomLeft: Int, bottomRight: Int,
        fracX: Float, fracY: Float
    ): Int {
        var result = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (topLeft ushr shift and 0xFF).toFloat()
            val b = (topRight ushr shift and 0xFF).toFloat()
            val c = (bottomLeft ushr shift and 0xFF).toFloat()
            val d = (bottomRight ushr shift and 0xFF).toFloat()
            val top = a + (b - a) * fracX
            val bottom = c + (d - c) * fracX
            val value = (top + (bottom - top) * fracY).roundToInt().coerceIn(0, 255)
            result = result or (value shl shift)
        }
        return result
    }
}
