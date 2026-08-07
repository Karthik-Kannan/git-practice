package com.scrappy.receipts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `merchant is never taken from far above the receipt body`() {
        // Anchoring bounds how far from the body a merchant can come. It does not
        // rescue you from clutter sitting immediately above it — that is what
        // FrameFilter removes before the parser ever sees these lines.
        val lines = listOf(
            "DISTANT POSTER ONE",
            "DISTANT POSTER TWO",
            "NEARBY POSTER",
            "MORE POSTER",
            "STILL POSTER",
            "CORNER CAFE",
            "TOTAL 12.00"
        )
        val merchant = ReceiptParser.parse(lines).merchant
        assertNotEquals("DISTANT POSTER ONE", merchant)
        assertNotEquals("DISTANT POSTER TWO", merchant)
    }

    @Test
    fun `still finds a merchant sitting above a multi-line address`() {
        val lines = listOf(
            "TRADER JOE'S",
            "5885 Meadow Expressway",
            "San Jose, CA 95116",
            "Store #0003",
            "BANANAS 4.49"
        )
        assertEquals("TRADER JOE'S", ReceiptParser.parse(lines).merchant)
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
    fun `a lone surviving amount is not reported as subtotal and tax too`() {
        // Straight from a real capture: OCR mangled everything except one amount,
        // so every lookup landed on it and the receipt claimed subtotal == tax ==
        // total == 3.99. Tax equal to the total is impossible.
        val lines = listOf(
            "Luoky",
            "h W Cecol tol Exoresswa/",
            "Coshier: FastLanel",
            "TOTAL TAK",
            "TOTAL",
            "USD\$ 3.99",
            "Total:",
            "SHOPPING AT LICKV"
        )
        val parsed = ReceiptParser.parse(lines)

        assertEquals(3.99, parsed.total!!, 0.001)
        assertNull(parsed.subtotal)
        assertNull(parsed.tax)
    }

    /** Transcribed from a real Lucky receipt, including its zero tax line. */
    private val lucky = """
        Lucky
        565 W Capitol Expressway
        (408) 445-6900
        Store:758
        08/06/26          18:30:12
        Cashier: FastLane1
        PRODUCE-GARDEN
        SSL PTO BABY GOLD        3.99F
        SUBTOTAL      3.99
        TOTAL TAX       .00
        TOTAL         3.99
        Visa    TENDER      3.99
        CASH    CHANGE       .00
        NUMBER OF ITEMS    1
    """.trimIndent().lines()

    @Test
    fun `reads amounts printed without a leading zero`() {
        // Thermal printers drop the leading zero on sub-dollar amounts.
        assertEquals(0.0, ReceiptParser.moneyIn("TOTAL TAX   .00").last(), 0.001)
        assertEquals(0.99, ReceiptParser.moneyIn("CANDY   .99").last(), 0.001)
    }

    @Test
    fun `a bare decimal is not read off the tail of another number`() {
        assertEquals(listOf(12.34), ReceiptParser.moneyIn("REF 12.34.56"))
    }

    @Test
    fun `reports a genuine zero tax rather than nothing`() {
        val parsed = ReceiptParser.parse(lucky)
        assertEquals(3.99, parsed.total!!, 0.001)
        assertEquals(3.99, parsed.subtotal!!, 0.001)
        assertEquals(0.0, parsed.tax!!, 0.001)
    }

    @Test
    fun `TOTAL TAX is not mistaken for the total`() {
        assertEquals("Lucky", ReceiptParser.parse(lucky).merchant)
        assertEquals("08/06/26", ReceiptParser.parse(lucky).date)
        // The tax line sits directly above the total; the total must still win.
        assertEquals(3.99, ReceiptParser.parse(lucky).total!!, 0.001)
    }

    @Test
    fun `keeps the purchase and drops the payment lines`() {
        val labels = ReceiptParser.parse(lucky).items.map { it.label }
        assertTrue(labels.contains("SSL PTO BABY GOLD"))
        assertTrue(labels.none { it.contains("TENDER", true) })
        assertTrue(labels.none { it.contains("CHANGE", true) })
        assertTrue(labels.none { it.contains("SUBTOTAL", true) })
    }

    @Test
    fun `a bare currency code is not a purchased item`() {
        val labels = ReceiptParser.parse(listOf("SHOP", "USD\$ 3.99", "TOTAL 3.99"))
            .items.map { it.label }
        assertTrue(labels.none { it.equals("USD", ignoreCase = true) })
    }

    @Test
    fun `tax at or above the total is discarded`() {
        val lines = listOf("SHOP", "WIDGET 5.00", "TAX 9.99", "TOTAL 9.99")
        assertNull(ReceiptParser.parse(lines).tax)
    }

    @Test
    fun `a genuine subtotal and tax still come through`() {
        // The guards must not cost us the normal case.
        val parsed = ReceiptParser.parse(grocery)
        assertEquals(14.98, parsed.subtotal!!, 0.001)
        assertEquals(1.20, parsed.tax!!, 0.001)
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
