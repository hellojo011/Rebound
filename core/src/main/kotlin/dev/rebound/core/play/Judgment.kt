package dev.rebound.core.play

import kotlin.math.abs

enum class Judgment {
    JUST,
    GREAT,
    GOOD,
    MISS,

    /**
     * Awarded repeatedly while a long object is being held.
     *
     * Unlike the others this is not a verdict on a press -- it is the reward for
     * sustaining one, ticking away for as long as the finger stays down. It sits
     * outside combo and outside the clear gauge, and only adds score.
     */
    KEEP,
    ;

    val isHit: Boolean get() = this != MISS

    /** Whether this is a verdict on an object, rather than a sustain tick. */
    val isVerdict: Boolean get() = this != KEEP
}

/**
 * Timing tolerances, in milliseconds either side of the object's exact time.
 *
 * These are deliberately data rather than constants: touchscreens vary enough
 * that difficulty tuning ends up being window tuning, and the values want to be
 * adjustable from a debug menu without a rebuild.
 */
data class JudgeWindows(
    val justMs: Double = 35.0,
    val greatMs: Double = 65.0,
    val goodMs: Double = 110.0,
) {
    init {
        require(justMs < greatMs && greatMs < goodMs) { "windows must widen: just < great < good" }
    }

    /** Widest deviation that still registers as a hit. Beyond this the object is missed. */
    val hitMs: Double get() = goodMs

    fun judge(deltaMs: Double): Judgment {
        val d = abs(deltaMs)
        return when {
            d <= justMs -> Judgment.JUST
            d <= greatMs -> Judgment.GREAT
            d <= goodMs -> Judgment.GOOD
            else -> Judgment.MISS
        }
    }
}
