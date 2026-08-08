package com.scrappy.receipts

/**
 * One OCR'd line, with the two signals we use to decide whether it belongs to
 * the receipt. Geometry is kept as plain ints rather than a [android.graphics.Rect]
 * so this stays testable on the JVM.
 */
data class ScannedLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** Mean chroma magnitude, 0 (neutral) .. 127 (vivid). 0 when unknown. */
    val saturation: Int = 0,
    val isReceipt: Boolean = true
) {
    val height: Int get() = bottom - top
}

/**
 * Throws away OCR lines that clearly aren't the receipt.
 *
 * The camera reads everything in frame — packaging, flyers, whatever else is on
 * the table — and those lines otherwise flow straight into the parser, where
 * they become bogus merchants and line items. Two cheap signals separate them:
 *
 *  - **Colour.** Thermal receipt print is black on white, so its chroma sits at
 *    ~0. Flyers and logos are coloured and sit well above it.
 *  - **Type size.** Receipt print is uniform. Display type on nearby packaging
 *    is several times taller.
 *
 * Both thresholds are relative to the frame's own median, so a clean shot of a
 * receipt on its own passes through untouched — which is the common case, and
 * the one this must not make worse.
 */
object FrameFilter {

    /** Below this there isn't enough of a sample for a median to mean anything. */
    private const val MIN_LINES_TO_JUDGE = 6

    /** How far above the frame's median chroma a line must sit to count as coloured. */
    private const val SATURATION_MARGIN = 20

    /** Absolute floor, so a purely monochrome frame can never trip the colour rule. */
    private const val SATURATION_FLOOR = 24

    private const val MAX_HEIGHT_RATIO = 2.2f
    private const val MIN_HEIGHT_RATIO = 0.45f

    /** If we'd discard more than this, the assumption is wrong — keep everything. */
    private const val MAX_REJECT_FRACTION = 0.6f

    fun classify(lines: List<ScannedLine>): List<ScannedLine> {
        if (lines.size < MIN_LINES_TO_JUDGE) return lines

        val medianHeight = lines.map { it.height }.median()
        val medianSaturation = lines.map { it.saturation }.median()

        val judged = lines.map { line ->
            val tooColourful = line.saturation > medianSaturation + SATURATION_MARGIN &&
                    line.saturation > SATURATION_FLOOR

            val wrongSize = medianHeight > 0 && (
                    line.height > medianHeight * MAX_HEIGHT_RATIO ||
                            line.height < medianHeight * MIN_HEIGHT_RATIO
                    )

            line.copy(isReceipt = !tooColourful && !wrongSize)
        }

        val rejected = judged.count { !it.isReceipt }
        if (rejected > lines.size * MAX_REJECT_FRACTION) return lines

        // Both rules key off the frame's median, which assumes the receipt is most
        // of what's in shot. When it isn't, the median tracks the clutter instead
        // and we throw away the receipt. Amounts are the tell: if nothing we kept
        // carries a price and something we dropped does, the medians lied.
        val keptHasAmounts = judged.any { it.isReceipt && ReceiptParser.moneyIn(it.text).isNotEmpty() }
        val droppedHasAmounts = judged.any { !it.isReceipt && ReceiptParser.moneyIn(it.text).isNotEmpty() }
        if (!keptHasAmounts && droppedHasAmounts) return lines

        return judged
    }

    private fun List<Int>.median(): Int {
        if (isEmpty()) return 0
        val sorted = sorted()
        return sorted[sorted.size / 2]
    }
}
