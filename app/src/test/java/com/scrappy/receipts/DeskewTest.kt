package com.scrappy.receipts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class DeskewTest {

    private fun box(
        label: DetectedClass,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        angle: Float = 0f,
        confidence: Float = 0.9f
    ) = OrientedBox(label, cx, cy, w, h, angle, confidence)

    // --- box geometry ----------------------------------------------------

    @Test
    fun `an unrotated box has the corners you would expect`() {
        val corners = box(DetectedClass.RECEIPT, 50f, 100f, 100f, 200f).corners()
        assertEquals(Point(0f, 0f), corners[0])
        assertEquals(Point(100f, 0f), corners[1])
        assertEquals(Point(100f, 200f), corners[2])
        assertEquals(Point(0f, 200f), corners[3])
    }

    @Test
    fun `axis aligned bounds wrap a rotated box`() {
        val bounds = box(DetectedClass.RECEIPT, 0f, 0f, 100f, 100f, (PI / 4).toFloat())
            .axisAlignedBounds()
        // A square rotated 45 degrees needs a box sqrt(2) times wider: 141.42,
        // rounded outward to 142 so a crop can't clip the corners.
        assertEquals(142, bounds.width)
        assertEquals(142, bounds.height)
    }

    @Test
    fun `a sideways box is re-expressed with its long edge vertical`() {
        val sideways = box(DetectedClass.RECEIPT, 0f, 0f, 400f, 100f)
        val upright = sideways.longEdgeVertical()
        assertEquals(100f, upright.width, 0.01f)
        assertEquals(400f, upright.height, 0.01f)
        assertTrue(upright.height > upright.width)
    }

    // --- rotation --------------------------------------------------------

    @Test
    fun `an upright receipt needs no rotation`() {
        val receipt = box(DetectedClass.RECEIPT, 500f, 500f, 200f, 800f)
        assertEquals(0f, Deskew.uprightRotationDegrees(receipt), 0.01f)
    }

    @Test
    fun `a receipt lying on its side is stood back up`() {
        val receipt = box(DetectedClass.RECEIPT, 500f, 500f, 800f, 200f)
        assertEquals(-90f, Deskew.uprightRotationDegrees(receipt), 0.01f)
    }

    @Test
    fun `a tilted receipt is rotated back by its own angle`() {
        val tilted = box(
            DetectedClass.RECEIPT, 500f, 500f, 200f, 800f,
            angle = (30 * PI / 180).toFloat()
        )
        assertEquals(-30f, Deskew.uprightRotationDegrees(tilted), 0.01f)
    }

    @Test
    fun `rotating by 90 degrees swaps the canvas and shifts it back into view`() {
        val plan = Deskew.planRotation(100, 200, 90f)
        assertEquals(200, plan.outputWidth)
        assertEquals(100, plan.outputHeight)

        // Every corner must land inside the new canvas, none negative.
        val mapped = listOf(
            plan.map(0f, 0f), plan.map(100f, 0f),
            plan.map(100f, 200f), plan.map(0f, 200f)
        )
        assertTrue(mapped.all { it.x >= -0.01f && it.y >= -0.01f })
        assertTrue(mapped.all { it.x <= 200.01f && it.y <= 100.01f })
    }

    @Test
    fun `rotating by zero is the identity`() {
        val plan = Deskew.planRotation(640, 480, 0f)
        assertEquals(640, plan.outputWidth)
        assertEquals(480, plan.outputHeight)
        assertEquals(Point(12f, 34f), plan.map(12f, 34f))
    }

    // --- crop planning ---------------------------------------------------

    private val receipt = box(DetectedClass.RECEIPT, 500f, 500f, 200f, 800f)
    private val merchant = box(DetectedClass.MERCHANT_BLOCK, 500f, 180f, 160f, 80f)
    private val totals = box(DetectedClass.TOTALS_BLOCK, 500f, 780f, 170f, 120f)

    @Test
    fun `no receipt means no plan`() {
        assertNull(Deskew.plan(listOf(merchant, totals), 1000, 1000))
        assertNull(Deskew.plan(emptyList(), 1000, 1000))
    }

    @Test
    fun `both field crops land inside the receipt`() {
        val plan = Deskew.plan(listOf(receipt, merchant, totals), 1000, 1000)
        assertNotNull(plan)
        plan!!

        assertEquals(2, plan.fields.size)
        for ((_, field) in plan.fields) {
            assertTrue(field.left >= plan.receipt.left)
            assertTrue(field.top >= plan.receipt.top)
            assertTrue(field.right <= plan.receipt.right)
            assertTrue(field.bottom <= plan.receipt.bottom)
        }
    }

    @Test
    fun `a field block out on the table is ignored`() {
        val strayTotals = box(DetectedClass.TOTALS_BLOCK, 50f, 50f, 100f, 60f)
        val plan = Deskew.plan(listOf(receipt, merchant, strayTotals), 1000, 1000)!!

        assertTrue(plan.fields.containsKey(DetectedClass.MERCHANT_BLOCK))
        assertFalse(plan.fields.containsKey(DetectedClass.TOTALS_BLOCK))
    }

    @Test
    fun `the most confident detection of a class wins`() {
        val weak = box(DetectedClass.RECEIPT, 100f, 100f, 50f, 50f, confidence = 0.3f)
        val strong = box(DetectedClass.RECEIPT, 500f, 500f, 200f, 800f, confidence = 0.95f)
        assertEquals(strong, listOf(weak, strong).best(DetectedClass.RECEIPT))
    }

    @Test
    fun `low confidence detections are discarded`() {
        val noise = box(DetectedClass.RECEIPT, 100f, 100f, 50f, 50f, confidence = 0.05f)
        assertNull(listOf(noise).best(DetectedClass.RECEIPT))
    }

    @Test
    fun `a sideways receipt still yields crops inside itself`() {
        // The case that breaks ML Kit today: receipt lying across the frame.
        val sideways = box(DetectedClass.RECEIPT, 500f, 500f, 800f, 200f)
        val block = box(DetectedClass.TOTALS_BLOCK, 800f, 500f, 120f, 170f)

        val plan = Deskew.plan(listOf(sideways, block), 1000, 1000)!!
        assertEquals(-90f, plan.rotation.degrees, 0.01f)

        val totalsCrop = plan.fields[DetectedClass.TOTALS_BLOCK]
        assertNotNull(totalsCrop)
        assertTrue(totalsCrop!!.width > 0 && totalsCrop.height > 0)
    }

    // --- bounds helpers --------------------------------------------------

    @Test
    fun `padding grows a box proportionally`() {
        val padded = Bounds(100, 100, 200, 300).padded(0.1f)
        assertEquals(Bounds(90, 80, 210, 320), padded)
    }

    @Test
    fun `intersection returns null when boxes miss each other`() {
        assertNull(Bounds(0, 0, 10, 10).intersect(Bounds(20, 20, 30, 30)))
        assertEquals(Bounds(5, 5, 10, 10), Bounds(0, 0, 10, 10).intersect(Bounds(5, 5, 30, 30)))
    }

    @Test
    fun `clamping keeps a box on the canvas`() {
        assertEquals(
            Bounds(0, 0, 50, 50),
            Bounds(-20, -20, 50, 50).clampedTo(100, 100)
        )
    }
}
