package com.scrappy.receipts.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * DBNet text detection.
 *
 * The model emits a per-pixel probability of "this is text". Turning that into
 * boxes is threshold → dilate → connected components → score → unclip. PaddleOCR
 * fits a minimum-area rectangle to each blob; we take the axis-aligned bounding
 * box instead, which is both simpler and sufficient here because the image is
 * straightened before recognition.
 *
 * Defaults mirror RapidOCR's shipped config so results match the reference.
 */
class TextDetector(
    private val runner: OnnxRunner,
    private val limitSideLen: Int = 736,
    private val maxSideLen: Int = 2000,
    private val binaryThreshold: Float = 0.3f,
    private val boxThreshold: Float = 0.5f,
    private val unclipRatio: Float = 1.6f,
    private val maxCandidates: Int = 1000,
    private val dilate: Boolean = true
) {

    data class TextBox(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val score: Float
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        /** Upright text sits in boxes wider than they are tall. */
        val isWide: Boolean get() = width > height
    }

    fun detect(image: Raster): List<TextBox> {
        val scaled = resizeForNetwork(image)
        val probabilities = runner.run(
            scaled.toChwFloat(MEAN, STD),
            longArrayOf(1, 3, scaled.height.toLong(), scaled.width.toLong())
        )

        val mapHeight = probabilities.dim(2)
        val mapWidth = probabilities.dim(3)

        return boxesFrom(
            probabilities.data, mapWidth, mapHeight,
            scaleX = image.width.toFloat() / mapWidth,
            scaleY = image.height.toFloat() / mapHeight
        )
    }

    /** Sizes must be multiples of 32; RapidOCR also floors the short side at [limitSideLen]. */
    internal fun resizeForNetwork(image: Raster): Raster {
        var ratio = 1f
        val longest = max(image.width, image.height).toFloat()
        if (longest > maxSideLen) ratio = maxSideLen / longest

        val shortest = min(image.width, image.height) * ratio
        if (shortest < limitSideLen) ratio *= limitSideLen / shortest

        val width = roundToMultipleOf32(image.width * ratio)
        val height = roundToMultipleOf32(image.height * ratio)
        return image.resized(width, height)
    }

    private fun roundToMultipleOf32(value: Float): Int =
        ((value / 32f).roundToInt() * 32).coerceAtLeast(32)

    private fun boxesFrom(
        probabilities: FloatArray,
        width: Int,
        height: Int,
        scaleX: Float,
        scaleY: Float
    ): List<TextBox> {
        var mask = BooleanArray(width * height) { probabilities[it] > binaryThreshold }
        if (dilate) mask = dilated(mask, width, height)

        val boxes = mutableListOf<TextBox>()
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            if (boxes.size >= maxCandidates) break

            // Flood fill this blob, tracking its extent as we go.
            var top = 0
            stack[top++] = start
            visited[start] = true

            var minX = width; var maxX = -1
            var minY = height; var maxY = -1

            while (top > 0) {
                val index = stack[--top]
                val x = index % width
                val y = index / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0) {
                    val n = index - 1
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n }
                }
                if (x < width - 1) {
                    val n = index + 1
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n }
                }
                if (y > 0) {
                    val n = index - width
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n }
                }
                if (y < height - 1) {
                    val n = index + width
                    if (mask[n] && !visited[n]) { visited[n] = true; stack[top++] = n }
                }
            }

            if (maxX < minX || maxY < minY) continue
            if (maxX - minX < MIN_BOX_SIDE || maxY - minY < MIN_BOX_SIDE) continue

            val score = meanProbability(probabilities, width, minX, minY, maxX, maxY)
            if (score < boxThreshold) continue

            val expanded = unclip(minX, minY, maxX + 1, maxY + 1)
            boxes += TextBox(
                left = (expanded[0] * scaleX).roundToInt().coerceAtLeast(0),
                top = (expanded[1] * scaleY).roundToInt().coerceAtLeast(0),
                right = (expanded[2] * scaleX).roundToInt(),
                bottom = (expanded[3] * scaleY).roundToInt(),
                score = score
            )
        }
        return boxes
    }

    private fun meanProbability(
        probabilities: FloatArray,
        width: Int,
        minX: Int, minY: Int, maxX: Int, maxY: Int
    ): Float {
        var total = 0f
        var count = 0
        for (y in minY..maxY) {
            val row = y * width
            for (x in minX..maxX) {
                total += probabilities[row + x]
                count++
            }
        }
        return if (count == 0) 0f else total / count
    }

    /**
     * DBNet shrinks text regions during training, so detections come back tight.
     * Expand by area*ratio/perimeter — the Vatti offset, reduced to a rectangle.
     */
    private fun unclip(left: Int, top: Int, right: Int, bottom: Int): IntArray {
        val w = (right - left).toFloat()
        val h = (bottom - top).toFloat()
        val perimeter = 2f * (w + h)
        if (perimeter <= 0f) return intArrayOf(left, top, right, bottom)

        val distance = (w * h * unclipRatio / perimeter).roundToInt()
        return intArrayOf(left - distance, top - distance, right + distance, bottom + distance)
    }

    private fun dilated(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!mask[y * width + x]) continue
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx in 0 until width) out[ny * width + nx] = true
                    }
                }
            }
        }
        return out
    }

    private companion object {
        val MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        val STD = floatArrayOf(0.5f, 0.5f, 0.5f)
        const val MIN_BOX_SIDE = 2
    }
}
