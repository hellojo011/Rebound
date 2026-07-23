package dev.rebound.core.play

import kotlin.math.min

/**
 * The JUST REFLEC gauge: the resource that turns an ordinary gold object into a
 * powered shot.
 *
 * Only [Judgment.JUST] charges it, which is what ties the mechanic to precision
 * rather than to volume of notes hit. It fills in units and is spent a whole
 * segment at a time, so the player can see how close the next charge is.
 */
class JustReflecGauge(
    val segments: Int = 5,
    /**
     * JUSTs needed per segment. High enough that a full gauge is something the
     * player works toward rather than something that sits pinned at maximum.
     */
    private val unitsPerSegment: Int = 8,
) {
    private var units: Int = 0

    val maxUnits: Int = segments * unitsPerSegment

    /** Whole segments available to spend. */
    val filledSegments: Int get() = units / unitsPerSegment

    /** 0..1 across the whole gauge, for drawing. */
    val fillFraction: Float get() = units.toFloat() / maxUnits

    /** 0..1 within the segment currently filling. */
    val partialFraction: Float get() = (units % unitsPerSegment).toFloat() / unitsPerSegment

    fun onJudgment(judgment: Judgment) {
        if (judgment == Judgment.JUST) {
            units = min(maxUnits, units + 1)
        }
    }

    /**
     * Spends one whole segment.
     *
     * @return false if there was not a full segment to spend, in which case
     *   nothing is deducted -- a failed reflec costs the player nothing.
     */
    fun consumeSegment(): Boolean {
        if (filledSegments <= 0) return false
        units -= unitsPerSegment
        return true
    }

    fun reset() {
        units = 0
    }
}
