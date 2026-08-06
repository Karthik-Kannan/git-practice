package com.scrappy.receipts

/**
 * Turns a pile of OCR'd lines into a best-effort receipt.
 *
 * Deliberately heuristic: receipts have no standard layout, so this leans on the
 * couple of conventions that hold almost everywhere — the merchant is at the top,
 * amounts sit at the right edge of their line, and the total is labelled.
 */
object ReceiptParser {

    private fun words(vararg w: String) =
        Regex("""\b(${w.joinToString("|")})\b""", RegexOption.IGNORE_CASE)

    /** Matches 1234.56 / 1,234.56 / 1.234,56 / -5.00, optionally with a currency symbol. */
    private val moneyRe = Regex("""(-)?\s*[$€£₹¥]?\s*(\d{1,3}(?:[,.]\d{3})*|\d+)[.,](\d{2})(?!\d)""")

    private val grandTotalRe = Regex(
        """\b(grand\s*total|total\s*due|total\s*amount|amount\s*due|balance\s*due|amount\s*paid)\b""",
        RegexOption.IGNORE_CASE
    )
    private val totalRe = words("totals?")
    private val subtotalRe = Regex("""\bsub[\s\-]*total\b""", RegexOption.IGNORE_CASE)
    private val taxRe = words("tax", "vat", "gst", "hst", "pst")

    /** Lines that are receipt bookkeeping rather than something you bought. */
    private val notAnItemRe = words(
        "sub\\s*total", "sub-total", "totals?", "tax", "vat", "gst", "hst", "pst",
        "change", "cash", "card", "visa", "mastercard", "amex", "debit", "credit",
        "tender", "balance", "due", "payment", "paid", "auth", "approval", "ref",
        "terminal", "acct", "account", "invoice", "receipt", "thank", "welcome",
        "survey", "www", "http", "tel", "phone", "register", "cashier", "server"
    )

    private val notAMerchantRe = words(
        "receipt", "invoice", "order", "tel", "phone", "fax", "www", "http",
        "customer", "copy", "welcome", "thank", "date", "time", "cashier",
        "register", "server", "table", "qty", "description"
    )

    private val dateRes = listOf(
        Regex("""\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b"""),
        Regex("""\b\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}\b"""),
        Regex(
            """\b\d{1,2}\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?,?\s+\d{2,4}\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2},?\s+\d{2,4}\b""",
            RegexOption.IGNORE_CASE
        )
    )

    fun parse(rawLines: List<String>): ParsedReceipt {
        val lines = rawLines.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParsedReceipt()

        val allAmounts = lines.flatMap { moneyIn(it) }

        return ParsedReceipt(
            merchant = findMerchant(lines),
            date = findDate(lines),
            total = findTotal(lines, allAmounts),
            subtotal = findLabelled(lines, subtotalRe),
            tax = findLabelled(lines, taxRe),
            items = findItems(lines),
            rawText = lines.joinToString("\n")
        )
    }

    // --- amounts ---------------------------------------------------------

    fun moneyIn(line: String): List<Double> =
        moneyRe.findAll(line).mapNotNull { m ->
            val negative = m.groupValues[1] == "-"
            val whole = m.groupValues[2].replace(",", "").replace(".", "")
            val cents = m.groupValues[3]
            "$whole.$cents".toDoubleOrNull()?.let { if (negative) -it else it }
        }.toList()

    /** Receipts right-align amounts, so the last one on a line is the one that matters. */
    private fun lastMoney(line: String): Double? = moneyIn(line).lastOrNull()

    /** Some layouts put the label and the amount on separate lines. */
    private fun moneyOnFollowingLine(lines: List<String>, index: Int): Double? {
        for (j in index + 1..minOf(index + 2, lines.lastIndex)) {
            val amounts = moneyIn(lines[j])
            // Only trust a nearby line if it is essentially just a number.
            if (amounts.size == 1 && lines[j].count { it.isLetter() } <= 2) return amounts[0]
        }
        return null
    }

    // --- fields ----------------------------------------------------------

    private fun findMerchant(lines: List<String>): String? {
        for (line in lines.take(6)) {
            if (line.length < 3) continue
            if (moneyIn(line).isNotEmpty()) continue
            if (notAMerchantRe.containsMatchIn(line)) continue

            val letters = line.count { it.isLetter() }
            val meaningful = line.count { !it.isWhitespace() }
            if (letters < 3 || meaningful == 0) continue
            if (letters.toDouble() / meaningful < 0.5) continue

            val cleaned = line.trim(' ', '*', '-', '=', ':', '.', ',')
            if (cleaned.length >= 3) return cleaned
        }
        return null
    }

    private fun findDate(lines: List<String>): String? {
        for (line in lines) {
            for (re in dateRes) {
                re.find(line)?.let { return it.value }
            }
        }
        return null
    }

    private fun findTotal(lines: List<String>, allAmounts: List<Double>): Double? {
        // Strongest signal: an explicitly labelled grand total.
        for (i in lines.indices) {
            val line = lines[i]
            if (subtotalRe.containsMatchIn(line)) continue
            if (grandTotalRe.containsMatchIn(line)) {
                val amount = lastMoney(line) ?: moneyOnFollowingLine(lines, i)
                if (amount != null) return amount
            }
        }
        // Next best: a bare "TOTAL" line. Scan bottom-up, since totals live at the end.
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            if (subtotalRe.containsMatchIn(line)) continue
            if (totalRe.containsMatchIn(line)) {
                val amount = lastMoney(line) ?: moneyOnFollowingLine(lines, i)
                if (amount != null) return amount
            }
        }
        // Last resort: the biggest number on the page is usually the total.
        return allAmounts.filter { it > 0 }.maxOrNull()
    }

    private fun findLabelled(lines: List<String>, label: Regex): Double? {
        for (i in lines.indices) {
            if (label.containsMatchIn(lines[i])) {
                val amount = lastMoney(lines[i]) ?: moneyOnFollowingLine(lines, i)
                if (amount != null) return amount
            }
        }
        return null
    }

    private fun findItems(lines: List<String>): List<LineItem> {
        val items = mutableListOf<LineItem>()
        for (line in lines) {
            if (notAnItemRe.containsMatchIn(line)) continue

            val match = moneyRe.findAll(line).lastOrNull() ?: continue
            val amount = moneyIn(line).lastOrNull() ?: continue

            val label = line.substring(0, match.range.first)
                .trim(' ', '.', '-', '*', ':', '\t', '$')
            if (label.count { it.isLetter() } < 2) continue
            if (label.length > 48) continue

            items += LineItem(label, amount)
        }
        return items
    }
}
