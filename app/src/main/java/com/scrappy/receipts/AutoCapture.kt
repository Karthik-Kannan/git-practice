package com.scrappy.receipts

/** An axis-aligned box in the upright frame coordinate space. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = maxOf(0, width).toLong() * maxOf(0, height).toLong()

    fun intersectionOverUnion(other: Bounds): Float {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        if (r <= l || b <= t) return 0f

        val intersection = (r - l).toLong() * (b - t).toLong()
        val union = area + other.area - intersection
        return if (union <= 0L) 0f else intersection.toFloat() / union.toFloat()
    }

    companion object {
        /** The region the receipt occupies, taken as the union of its text. */
        fun around(lines: List<ScannedLine>): Bounds? {
            if (lines.isEmpty()) return null
            return Bounds(
                left = lines.minOf { it.left },
                top = lines.minOf { it.top },
                right = lines.maxOf { it.right },
                bottom = lines.maxOf { it.bottom }
            )
        }
    }
}

/**
 * Decides when to fire the shutter on its own, the way a document scanner does.
 *
 * A dedicated document detector would give a tighter outline, but every gate one
 * of those trips on is already available here for free: the OCR output tells us
 * something readable is in frame, its union box tells us where and how large,
 * frame-to-frame overlap tells us the phone is being held still, and the
 * accumulator's confidence tells us the reading has settled. Blur needs no
 * special handling — an out-of-focus frame simply yields fewer lines.
 */
class AutoCapture(
    private val requiredStableFrames: Int = 6,
    private val cooldownFrames: Int = 30
) {

    data class Verdict(
        val bounds: Bounds?,
        /** 0..1 toward firing, for the viewfinder animation. */
        val progress: Float,
        val fire: Boolean,
        /** What the user needs to do, if anything. */
        val hint: String
    )

    private var stableFrames = 0
    private var lastBounds: Bounds? = null
    private var cooldown = 0

    fun observe(
        lines: List<ScannedLine>,
        frameWidth: Int,
        frameHeight: Int,
        confidence: Float
    ): Verdict {
        if (cooldown > 0) {
            cooldown--
            reset()
            return Verdict(null, 0f, false, HINT_SAVED)
        }

        val bounds = Bounds.around(lines)
        if (bounds == null || lines.size < MIN_LINES) {
            reset()
            return Verdict(bounds, 0f, false, HINT_SEARCHING)
        }

        val frameArea = frameWidth.toLong() * frameHeight.toLong()
        val coverage = if (frameArea <= 0L) 0f else bounds.area.toFloat() / frameArea.toFloat()
        if (coverage < MIN_COVERAGE) {
            stableFrames = 0
            lastBounds = bounds
            return Verdict(bounds, 0f, false, HINT_CLOSER)
        }

        val previous = lastBounds
        lastBounds = bounds
        if (previous == null || previous.intersectionOverUnion(bounds) < MIN_IOU) {
            stableFrames = 0
            return Verdict(bounds, 0f, false, HINT_STILL)
        }

        if (confidence < MIN_CONFIDENCE) {
            stableFrames = 0
            return Verdict(bounds, 0f, false, HINT_READING)
        }

        stableFrames++
        if (stableFrames >= requiredStableFrames) {
            cooldown = cooldownFrames
            return Verdict(bounds, 1f, true, HINT_SAVED)
        }

        val progress = (stableFrames.toFloat() / requiredStableFrames).coerceIn(0f, 1f)
        return Verdict(bounds, progress, false, HINT_HOLDING)
    }

    /** Called once a capture has been dealt with, to start looking again. */
    fun rearm() {
        reset()
        cooldown = cooldownFrames
    }

    private fun reset() {
        stableFrames = 0
        lastBounds = null
    }

    private companion object {
        /** Fewer lines than this isn't a receipt. */
        const val MIN_LINES = 8

        /** Fraction of the frame the text must cover before it's worth reading. */
        const val MIN_COVERAGE = 0.12f

        /** Frame-to-frame overlap that counts as "held still". */
        const val MIN_IOU = 0.90f

        const val MIN_CONFIDENCE = 0.55f

        const val HINT_SEARCHING = "Point at a receipt…"
        const val HINT_CLOSER = "Move closer"
        const val HINT_STILL = "Hold still"
        const val HINT_READING = "Reading…"
        const val HINT_HOLDING = "Hold steady…"
        const val HINT_SAVED = "Saved"
    }
}
