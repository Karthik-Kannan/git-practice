package com.scrappy.receipts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFilterTest {

    /** Receipt print: uniform small type, no colour. */
    private fun receiptLine(text: String, top: Int, saturation: Int = 3) =
        ScannedLine(text, left = 100, top = top, right = 400, bottom = top + 20, saturation = saturation)

    /** Flyer print: large and vividly coloured. */
    private fun flyerLine(text: String, top: Int, saturation: Int = 70) =
        ScannedLine(text, left = 60, top = top, right = 900, bottom = top + 90, saturation = saturation)

    private val receipt = listOf(
        receiptLine("TRADER JOE'S", 400),
        receiptLine("5885 Meadow Expressway", 430),
        receiptLine("San Jose, CA 95116", 460),
        receiptLine("Store #0003", 490),
        receiptLine("BANANAS 4.49", 520),
        receiptLine("OAT MILK 4.49", 550),
        receiptLine("TAX 0.85", 580),
        receiptLine("TOTAL 9.83", 610)
    )

    private val flyer = listOf(
        flyerLine("TEMPLE WORKS", 60),
        flyerLine("KUNDAN JEWELLERY", 160),
        flyerLine("GRT JEWELLERS", 260)
    )

    @Test
    fun `drops coloured oversized flyer text and keeps the receipt`() {
        val kept = FrameFilter.classify(flyer + receipt).filter { it.isReceipt }.map { it.text }

        assertTrue(kept.contains("TRADER JOE'S"))
        assertTrue(kept.contains("TOTAL 9.83"))
        assertTrue(kept.none { it.contains("JEWELL") })
        assertTrue(kept.none { it.contains("TEMPLE") })
    }

    @Test
    fun `the merchant survives the flyer once filtering has run`() {
        val kept = FrameFilter.classify(flyer + receipt).filter { it.isReceipt }.map { it.text }
        assertEquals("TRADER JOE'S", ReceiptParser.parse(kept).merchant)
    }

    @Test
    fun `without filtering the flyer still steals the merchant`() {
        // Anchoring the merchant to the receipt body narrows the damage but can't
        // undo it on text alone — the flyer sits close enough to the body to win.
        // Removing those lines is FrameFilter's job, not the parser's.
        val unfiltered = (flyer + receipt).map { it.text }
        assertEquals("GRT JEWELLERS", ReceiptParser.parse(unfiltered).merchant)
    }

    @Test
    fun `a clean receipt passes through untouched`() {
        val kept = FrameFilter.classify(receipt)
        assertTrue(kept.all { it.isReceipt })
    }

    @Test
    fun `colour rule ignores a warm white balance that lifts every line equally`() {
        // A tungsten cast pushes the whole frame off neutral; nothing should drop.
        val warm = receipt.map { it.copy(saturation = 30) }
        assertTrue(FrameFilter.classify(warm).all { it.isReceipt })
    }

    @Test
    fun `monochrome frame never trips the colour rule`() {
        val faint = receipt.mapIndexed { i, line -> line.copy(saturation = i % 4) }
        assertTrue(FrameFilter.classify(faint).all { it.isReceipt })
    }

    @Test
    fun `keeps everything when the clutter outnumbers the receipt`() {
        // Six flyer lines against two receipt lines drags the medians the wrong
        // way. The amounts in the discarded lines are what catches it.
        val kept = FrameFilter.classify(flyer + flyer + receipt.takeLast(2))
        assertTrue(kept.all { it.isReceipt })
    }

    @Test
    fun `too few lines to judge leaves them alone`() {
        val kept = FrameFilter.classify(flyer)
        assertTrue(kept.all { it.isReceipt })
    }
}
