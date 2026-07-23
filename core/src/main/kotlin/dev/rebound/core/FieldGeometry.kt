package dev.rebound.core

import kotlin.math.abs

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
}
