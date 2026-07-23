package dev.rebound.core.play

import kotlin.math.max

/**
 * Running score, combo and clear gauge.
 *
 * Score is a plain running total: every judgment is worth a fixed number of
 * points and they add up. A longer or denser chart therefore scores higher,
 * which is the point -- the number says what you did, not what fraction of an
 * arbitrary ceiling you reached.
 *
 * The clear gauge is separate and *is* chart-relative: it starts empty, fills as
 * objects are handled, tops out at exactly 100 on a perfect run, and the run
 * clears at [CLEAR_THRESHOLD] or above.
 */
class ScoreState(private val maxCombo: Int) {

    var combo: Int = 0
        private set

    var bestCombo: Int = 0
        private set

    var score: Int = 0
        private set

    private val counts = IntArray(Judgment.entries.size)
    private var filled = 0.0

    fun count(judgment: Judgment): Int = counts[judgment.ordinal]

    /**
     * Verdicts delivered so far.
     *
     * Excludes [Judgment.KEEP], which is a sustain tick rather than an object
     * being resolved -- counting it here would make a chart look unfinished or
     * over-finished depending on how long its holds were.
     */
    val judgedCount: Int get() = counts.sum() - counts[Judgment.KEEP.ordinal]

    /**
     * Clear gauge as a percentage, 0..100.
     *
     * Capped rather than merely expected to land on 100: a perfect run fills it
     * exactly, and anything that would push it past -- an object the chart did
     * not budget for -- is a bug that should show up as a suspiciously easy clear
     * rather than as an impossible number on screen.
     */
    val gauge: Double
        get() = if (maxCombo == 0) 0.0 else ((filled / maxCombo) * 100.0).coerceAtMost(100.0)

    /**
     * Whether the run would clear if it ended now.
     *
     * The line is exactly 70.0: at or above clears, anything below fails. Only
     * meaningful once the chart is over -- part way through, the gauge is still
     * filling and is below the threshold by definition.
     */
    val isCleared: Boolean get() = gauge >= CLEAR_THRESHOLD

    /** True only if every verdict so far was [Judgment.JUST]. */
    val isPerfect: Boolean get() = judgedCount > 0 && counts[Judgment.JUST.ordinal] == judgedCount

    /**
     * Points earned outside the judgment table.
     *
     * A JUST REFLEC is not a verdict on an object -- the object was already
     * judged when it was struck -- so it pays separately rather than distorting
     * the counts or the clear gauge.
     */
    fun addBonus(points: Int) {
        score += points
    }

    fun apply(judgment: Judgment) {
        counts[judgment.ordinal]++
        score += pointsFor(judgment)
        filled += gaugeWeight(judgment)

        // A sustain tick is neither a hit nor a drop, so it leaves combo alone:
        // holding a long object should not inflate the number, and letting go is
        // already punished by the release verdict.
        if (!judgment.isVerdict) return

        if (judgment == Judgment.MISS) {
            combo = 0
        } else {
            combo++
            bestCombo = max(bestCombo, combo)
        }
    }

    private fun pointsFor(j: Judgment) = when (j) {
        Judgment.JUST -> 6
        Judgment.GREAT -> 4
        Judgment.GOOD -> 2
        Judgment.KEEP -> 1
        Judgment.MISS -> 0
    }

    /**
     * A clean hit fills the gauge fully and a scrappy one only half, so timing
     * matters to clearing as well as to score -- but not as sharply, since the
     * gauge decides whether you pass at all.
     *
     * [Judgment.KEEP] contributes nothing: the gauge is measured against the
     * chart's object count, and sustain ticks are not objects.
     */
    private fun gaugeWeight(j: Judgment) = when (j) {
        Judgment.JUST -> 1.0
        Judgment.GREAT -> 1.0
        Judgment.GOOD -> 0.5
        Judgment.MISS -> 0.0
        Judgment.KEEP -> 0.0
    }

    companion object {
        /** Percentage of the gauge a run must reach to clear. */
        const val CLEAR_THRESHOLD = 70.0

        /** Paid on top of the object's own verdict when a reflec succeeds. */
        const val JUST_REFLEC_BONUS = 10
    }
}
