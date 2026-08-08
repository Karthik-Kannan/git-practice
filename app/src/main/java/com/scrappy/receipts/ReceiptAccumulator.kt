package com.scrappy.receipts

/**
 * Any single OCR frame is noisy — glare, motion blur and partial framing make fields
 * flicker in and out. This votes across a sliding window of recent frames so the
 * readout stays still, and reports how strongly the frames agree ("lock").
 */
class ReceiptAccumulator(private val windowSize: Int = 14) {

    private val frames = ArrayDeque<ParsedReceipt>()

    data class Stable(
        val receipt: ParsedReceipt,
        val confidence: Float // 0f..1f
    )

    fun reset() = frames.clear()

    fun push(frame: ParsedReceipt): Stable {
        // Frames with no text at all shouldn't wipe out a good lock instantly, but
        // they should still age the window out.
        frames.addLast(frame)
        while (frames.size > windowSize) frames.removeFirst()

        val populated = frames.filter { !it.isEmpty }
        if (populated.isEmpty()) return Stable(ParsedReceipt(), 0f)

        val merchant = vote(populated.mapNotNull { it.merchant })
        val date = vote(populated.mapNotNull { it.date })
        val totals = populated.mapNotNull { it.total }
        val total = vote(totals)
        val subtotal = vote(populated.mapNotNull { it.subtotal })
        val tax = vote(populated.mapNotNull { it.tax })

        // Items are all-or-nothing per frame: mixing item lists across frames produces
        // duplicates, so take the richest single frame instead.
        val best = populated.maxByOrNull { it.items.size } ?: populated.last()

        val receipt = ParsedReceipt(
            merchant = merchant,
            date = date,
            total = total,
            subtotal = subtotal,
            tax = tax,
            items = best.items,
            rawText = (populated.maxByOrNull { it.rawText.length } ?: best).rawText
        )

        return Stable(receipt, confidenceOf(receipt, totals, total))
    }

    /** Most frequently seen value wins; ties break toward the most recent. */
    private fun <T> vote(values: List<T>): T? {
        if (values.isEmpty()) return null
        val counts = LinkedHashMap<T, Int>()
        for (v in values) counts[v] = (counts[v] ?: 0) + 1
        return counts.maxByOrNull { it.value }?.key
    }

    private fun confidenceOf(
        receipt: ParsedReceipt,
        totals: List<Double>,
        winningTotal: Double?
    ): Float {
        // How consistently the winning total shows up across frames that saw one.
        val agreement = if (totals.isEmpty() || winningTotal == null) 0f
        else totals.count { it == winningTotal }.toFloat() / totals.size

        val completeness = receipt.filledFields / 4f

        // Don't advertise a strong lock before we've actually seen enough frames.
        val warmup = (frames.size.toFloat() / MIN_FRAMES_FOR_LOCK).coerceAtMost(1f)

        return ((0.6f * agreement + 0.4f * completeness) * warmup).coerceIn(0f, 1f)
    }

    companion object {
        private const val MIN_FRAMES_FOR_LOCK = 6f
    }
}
