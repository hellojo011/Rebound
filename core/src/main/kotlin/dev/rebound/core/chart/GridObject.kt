package dev.rebound.core.chart

import kotlin.math.abs

/**
 * An object as it sits on the authoring grid: a time, a column, and a type.
 *
 * Distinct from [Note], which is what the engine plays. A [Note] carries a
 * continuous position along the bar and a derived approach path; a grid object
 * carries the cell an author clicked. The translation happens once, on write.
 */
data class GridObject(
    val timeMs: Double,
    val column: Int,
    val type: NoteType,
    /** Only meaningful for [NoteType.LONG]. */
    val endTimeMs: Double = timeMs,
) {
    val isLong: Boolean get() = type == NoteType.LONG

    fun coversTime(ms: Double, toleranceMs: Double): Boolean =
        if (isLong) {
            ms >= timeMs - toleranceMs && ms <= endTimeMs + toleranceMs
        } else {
            abs(ms - timeMs) <= toleranceMs
        }
}
