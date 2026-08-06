package com.scrappy.receipts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptParserTest {

    private val grocery = """
        WHOLE FOODS MARKET
        123 Main Street
        Tel: 555-0142
        03/14/2024  10:32
        BANANAS ORGANIC        3.49
        OAT MILK 1L            4.99
        SOURDOUGH LOAF         6.50
        SUBTOTAL              14.98
        TAX                    1.20
        TOTAL                 16.18
        VISA ************1234 16.18
        THANK YOU
    """.trimIndent().lines()

    @Test
    fun `pulls merchant from the top of the receipt`() {
        assertEquals("WHOLE FOODS MARKET", ReceiptParser.parse(grocery).merchant)
    }

    @Test
    fun `prefers the labelled total over subtotal and the largest amount`() {
        assertEquals(16.18, ReceiptParser.parse(grocery).total!!, 0.001)
    }

    @Test
    fun `reads subtotal and tax separately`() {
        val parsed = ReceiptParser.parse(grocery)
        assertEquals(14.98, parsed.subtotal!!, 0.001)
        assertEquals(1.20, parsed.tax!!, 0.001)
    }

    @Test
    fun `finds the date`() {
        assertEquals("03/14/2024", ReceiptParser.parse(grocery).date)
    }

    @Test
    fun `keeps purchased lines and drops bookkeeping lines`() {
        val labels = ReceiptParser.parse(grocery).items.map { it.label }
        assertTrue(labels.contains("BANANAS ORGANIC"))
        assertTrue(labels.contains("OAT MILK 1L"))
        assertTrue(labels.contains("SOURDOUGH LOAF"))
        assertTrue(labels.none { it.contains("TOTAL", true) })
        assertTrue(labels.none { it.contains("TAX", true) })
        assertTrue(labels.none { it.contains("VISA", true) })
    }

    @Test
    fun `handles thousands separators and european decimal commas`() {
        assertEquals(1234.56, ReceiptParser.moneyIn("TOTAL 1,234.56").last(), 0.001)
        assertEquals(1234.56, ReceiptParser.moneyIn("TOTAL 1.234,56").last(), 0.001)
        assertEquals(12.34, ReceiptParser.moneyIn("TOTAL $12.34").last(), 0.001)
        assertEquals(-5.00, ReceiptParser.moneyIn("DISCOUNT -5.00").last(), 0.001)
    }

    @Test
    fun `ignores digit runs that are not money`() {
        assertTrue(ReceiptParser.moneyIn("ORDER 123456789").isEmpty())
        assertTrue(ReceiptParser.moneyIn("TEL 555 0142").isEmpty())
    }

    @Test
    fun `reads an amount printed on the line below its label`() {
        val lines = listOf("CAFE ROMA", "AMOUNT DUE", "42.00")
        assertEquals(42.00, ReceiptParser.parse(lines).total!!, 0.001)
    }

    @Test
    fun `falls back to the largest amount when nothing is labelled`() {
        val lines = listOf("CORNER SHOP", "WIDGET 2.00", "GADGET 9.75")
        assertEquals(9.75, ReceiptParser.parse(lines).total!!, 0.001)
    }

    @Test
    fun `empty input stays empty`() {
        val parsed = ReceiptParser.parse(listOf("", "   "))
        assertNull(parsed.total)
        assertNull(parsed.merchant)
        assertTrue(parsed.isEmpty)
    }

    @Test
    fun `accumulator settles on the most frequent total`() {
        val accumulator = ReceiptAccumulator()
        var last: ReceiptAccumulator.Stable? = null
        // Two bad frames, six good ones: the majority should win.
        val frames = listOf(99.99, 16.18, 16.18, 16.18, 88.88, 16.18, 16.18, 16.18)
        for (value in frames) {
            last = accumulator.push(
                ParsedReceipt(merchant = "WHOLE FOODS", total = value, rawText = "x")
            )
        }
        assertEquals(16.18, last!!.receipt.total!!, 0.001)
        assertTrue(last.confidence > 0.5f)
    }
}
