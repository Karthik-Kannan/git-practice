package com.scrappy.receipts.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CRNN text recognition with CTC decoding.
 *
 * The model reads a fixed-height strip and emits a per-timestep distribution over
 * the character set. CTC decoding collapses runs of the same class and drops the
 * blank at index 0, which is how a variable number of characters comes out of a
 * fixed number of timesteps.
 */
class TextRecognizer(
    private val runner: OnnxRunner,
    private val charset: List<String>,
    private val targetHeight: Int = 48,
    private val maxWidth: Int = 1600
) {

    data class Reading(val text: String, val confidence: Float)

    fun recognise(strip: Raster): Reading {
        if (strip.width < 2 || strip.height < 2) return Reading("", 0f)

        val scaledWidth = min(
            max(8, (targetHeight.toFloat() * strip.width / strip.height).roundToInt()),
            maxWidth
        )
        val resized = strip.resized(scaledWidth, targetHeight)

        val output = runner.run(
            resized.toChwFloat(MEAN, STD),
            longArrayOf(1, 3, targetHeight.toLong(), scaledWidth.toLong())
        )
        return decode(output)
    }

    internal fun decode(output: Tensor): Reading {
        val steps = output.dim(1)
        val classes = output.dim(2)

        val text = StringBuilder()
        var previous = -1
        var confidenceTotal = 0f
        var characters = 0

        for (step in 0 until steps) {
            val base = step * classes
            var bestIndex = 0
            var bestProbability = -1f
            for (c in 0 until classes) {
                val p = output.data[base + c]
                if (p > bestProbability) {
                    bestProbability = p
                    bestIndex = c
                }
            }

            // Index 0 is CTC blank; a repeat of the previous class is the same glyph.
            if (bestIndex != BLANK && bestIndex != previous) {
                text.append(charset.getOrElse(bestIndex) { "" })
                confidenceTotal += bestProbability
                characters++
            }
            previous = bestIndex
        }

        return Reading(
            text = text.toString(),
            confidence = if (characters == 0) 0f else confidenceTotal / characters
        )
    }

    companion object {
        private const val BLANK = 0
        private val MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val STD = floatArrayOf(0.5f, 0.5f, 0.5f)

        /**
         * PP-OCR's index space: blank, then the model's dictionary, then a space.
         * Getting this wrong shifts every character by one, so it is built in one
         * place rather than at each call site.
         */
        fun charsetFrom(dictionary: String): List<String> =
            listOf("") + dictionary.split("\n").filter { it.isNotEmpty() } + listOf(" ")
    }
}
