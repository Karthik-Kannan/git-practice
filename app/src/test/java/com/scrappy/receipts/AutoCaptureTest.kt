package com.scrappy.receipts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCaptureTest {

    private val frameWidth = 1080
    private val frameHeight = 1920

    /** Lines whose union is exactly the given box. */
    private fun lines(
        count: Int = 12,
        left: Int = 200,
        top: Int = 300,
        right: Int = 900,
        bottom: Int = 1500
    ): List<ScannedLine> {
        val step = (bottom - top - 20) / (count - 1).coerceAtLeast(1)
        return (0 until count).map { i ->
            val y = top + i * step
            ScannedLine("line $i", left, y, right, minOf(y + 20, bottom))
        }.mapIndexed { i, line ->
            // Make sure the union reaches the bottom edge exactly.
            if (i == count - 1) line.copy(bottom = bottom) else line
        }
    }

    private fun feed(
        auto: AutoCapture,
        frames: Int,
        lines: List<ScannedLine> = lines(),
        confidence: Float = 0.8f
    ): List<AutoCapture.Verdict> = (0 until frames).map {
        auto.observe(lines, frameWidth, frameHeight, confidence)
    }

    @Test
    fun `fires once the framing has been held steady`() {
        val auto = AutoCapture(requiredStableFrames = 3, cooldownFrames = 5)
        val verdicts = feed(auto, 4)

        assertFalse(verdicts[0].fire) // no previous frame to compare against yet
        assertFalse(verdicts[1].fire)
        assertFalse(verdicts[2].fire)
        assertTrue(verdicts[3].fire)
    }

    @Test
    fun `progress climbs toward the shutter`() {
        val auto = AutoCapture(requiredStableFrames = 4, cooldownFrames = 5)
        val progress = feed(auto, 5).map { it.progress }
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, progress.last(), 0.001f)
    }

    @Test
    fun `never fires while the phone is moving`() {
        val auto = AutoCapture(requiredStableFrames = 3, cooldownFrames = 5)
        val here = lines()
        val there = lines(left = 500, right = 1050)

        val verdicts = (0 until 10).map { i ->
            auto.observe(if (i % 2 == 0) here else there, frameWidth, frameHeight, 0.9f)
        }
        assertTrue(verdicts.none { it.fire })
        assertEquals("Hold still", verdicts.last().hint)
    }

    @Test
    fun `asks you to move closer when the receipt is a speck`() {
        val auto = AutoCapture(requiredStableFrames = 3, cooldownFrames = 5)
        val verdicts = feed(auto, 6, lines(left = 100, top = 100, right = 300, bottom = 300))

        assertTrue(verdicts.none { it.fire })
        assertEquals("Move closer", verdicts.last().hint)
    }

    @Test
    fun `waits for the reading to settle before firing`() {
        val auto = AutoCapture(requiredStableFrames = 3, cooldownFrames = 5)
        val verdicts = feed(auto, 6, confidence = 0.2f)

        assertTrue(verdicts.none { it.fire })
        assertEquals("Reading…", verdicts.last().hint)
    }

    @Test
    fun `a few stray lines are not a receipt`() {
        val auto = AutoCapture(requiredStableFrames = 3, cooldownFrames = 5)
        val verdicts = feed(auto, 6, lines(count = 4))

        assertTrue(verdicts.none { it.fire })
        assertEquals("Point at a receipt…", verdicts.last().hint)
    }

    @Test
    fun `does not fire again while cooling down`() {
        val auto = AutoCapture(requiredStableFrames = 3, cooldownFrames = 5)
        feed(auto, 4) // fires on the fourth

        // Receipt is still sitting in frame; it must not be captured over and over.
        assertTrue(feed(auto, 5).none { it.fire })
    }

    @Test
    fun `arms again for the next receipt once the cooldown passes`() {
        val auto = AutoCapture(requiredStableFrames = 3, cooldownFrames = 5)
        feed(auto, 4)
        feed(auto, 5) // burn off the cooldown

        assertTrue(feed(auto, 4).last().fire)
    }

    @Test
    fun `rearm imposes a fresh cooldown`() {
        val auto = AutoCapture(requiredStableFrames = 3, cooldownFrames = 5)
        auto.rearm()
        assertTrue(feed(auto, 5).none { it.fire })
    }

    @Test
    fun `union box covers every line`() {
        val bounds = Bounds.around(lines())!!
        assertEquals(200, bounds.left)
        assertEquals(300, bounds.top)
        assertEquals(900, bounds.right)
        assertEquals(1500, bounds.bottom)
    }

    @Test
    fun `iou is 1 for identical boxes and 0 for disjoint ones`() {
        val a = Bounds(0, 0, 100, 100)
        assertEquals(1f, a.intersectionOverUnion(a), 0.001f)
        assertEquals(0f, a.intersectionOverUnion(Bounds(200, 200, 300, 300)), 0.001f)
    }
}
