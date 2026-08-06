package com.scrappy.receipts

import java.text.NumberFormat
import java.util.Locale

data class LineItem(val label: String, val amount: Double)

/** One frame's worth of understanding of whatever the camera is pointed at. */
data class ParsedReceipt(
    val merchant: String? = null,
    val date: String? = null,
    val total: Double? = null,
    val subtotal: Double? = null,
    val tax: Double? = null,
    val items: List<LineItem> = emptyList(),
    val rawText: String = ""
) {
    /** How much of a receipt we think we're looking at, 0..4. */
    val filledFields: Int
        get() = (if (merchant != null) 1 else 0) +
                (if (date != null) 1 else 0) +
                (if (total != null) 1 else 0) +
                (if (items.isNotEmpty()) 1 else 0)

    val isEmpty: Boolean get() = rawText.isBlank()
}

data class SavedReceipt(
    val id: String,
    val capturedAt: Long,
    val imagePath: String?,
    val parsed: ParsedReceipt
)

fun formatMoney(amount: Double?): String {
    if (amount == null) return "—"
    return runCatching { NumberFormat.getCurrencyInstance().format(amount) }
        .getOrElse { String.format(Locale.US, "%.2f", amount) }
}
