package dev.rebound.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Field coordinates shared by the engine and the renderer.
 *
 * The field is normalised: x runs 0 (left wall) to 1 (right wall), y runs 0 (the
 * opponent's bar) to 1 (the player's bar). Keeping these here rather than in the
 * renderer means the judgment positions and the drawn positions cannot drift
 * apart -- a green object is judged exactly where its tap point is painted.
 */
object FieldGeometry {

    /** Where ordinary objects are judged: the player's bar. */
    const val BAR_Y = 1f

    /**
     * The row of circular tap points on the player's side.
     *
     * Far enough above the bar that a press meant for one cannot plausibly be
     * mistaken for a press meant for the other.
     */
    const val TAP_POINT_Y = 0.74f

    /** The three fixed tap points, across the width. */
    val TAP_POINT_XS: FloatArray = floatArrayOf(0.10f, 0.50f, 0.90f)

    /** The tap point nearest [x]. Green objects are snapped onto one of these. */
    fun nearestTapPointX(x: Float): Float =
        TAP_POINT_XS.minByOrNull { abs(it - x) } ?: 0.5f

    /**
     * Folds unfolded space into the field.
     *
     * A triangle wave of period 2: 0..1 passes through, 1..2 comes back, and so
     * on outwards in both directions. Drawing a straight line in unfolded space
     * and folding it is what turns it into a path that bounces off the side
     * walls -- one function, and it can neither drift nor tunnel the way
     * stepwise reflection can.
     *
     * Both an object's approach and a reflected shot's flight use this, which is
     * the point of it living here: the two must bounce identically or a rallied
     * object would visibly change course as it changed hands.
     */
    fun fold(value: Float): Float {
        var v = value % 2f
        if (v < 0f) v += 2f
        return if (v <= 1f) v else 2f - v
    }

    /**
     * How many side walls a straight line through unfolded space meets.
     *
     * Every whole-number boundary crossed is a wall.
     */
    fun wallCrossings(from: Float, to: Float): Int {
        val low = minOf(from, to).toDouble()
        val high = maxOf(from, to).toDouble()
        return (floor(high) - floor(low)).toInt()
    }

    /**
     * How far along a path from [from] to [to] the first wall is met, as a
     * fraction of the whole, or 0 if the path meets none.
     */
    fun firstWallProgress(from: Float, to: Float): Float {
        if (wallCrossings(from, to) == 0) return 0f
        val boundary = if (to > from) floor(from.toDouble()) + 1.0 else ceil(from.toDouble()) - 1.0
        val span = (to - from).toDouble()
        if (abs(span) < 1e-6) return 0f
        return ((boundary - from) / span).toFloat().coerceIn(0f, 1f)
    }
}
