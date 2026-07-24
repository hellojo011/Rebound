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
    /**
     * Marks this as the object a gold becomes when it is struck.
     *
     * The author's way of pinning down a rally that would otherwise be chosen
     * automatically: written as the `7`/`8`/`9`/`G` form of the object, and read
     * back the same way. Only a tap, a gold, a green or a chain's first link may
     * carry it -- the same restriction the parser enforces.
     */
    val rallied: Boolean = false,
    /**
     * Chain brackets. A chain runs from the object flagged [chainStart] to the
     * one flagged [chainStop] in the same column; both are written as their own
     * `[` / `]` characters. Only a [NoteType.CHAIN] object carries a bracket.
     */
    val chainStart: Boolean = false,
    val chainStop: Boolean = false,
    /**
     * A green (tap-point) long. Only meaningful for a [NoteType.LONG]: it is held
     * up at a tap point rather than at the bar, and is written with `{` / `}`.
     */
    val green: Boolean = false,
) {
    val isLong: Boolean get() = type == NoteType.LONG

    fun coversTime(ms: Double, toleranceMs: Double): Boolean =
        if (isLong) {
            ms >= timeMs - toleranceMs && ms <= endTimeMs + toleranceMs
        } else {
            abs(ms - timeMs) <= toleranceMs
        }
}
