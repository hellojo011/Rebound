package dev.rebound.editor

import dev.rebound.core.chart.GridObject

/**
 * The objects being authored.
 *
 * A plain mutable list, kept sorted by time. A chart is hundreds of objects, not
 * millions, and every operation here happens at the speed of a finger — an index
 * would be machinery with nothing to do.
 *
 * Holds [GridObject] straight from core, so what the editor manipulates is
 * exactly what the writer serialises with no translation layer in between.
 */
class EditorChart(val columns: Int = 16) {

    private val items = mutableListOf<GridObject>()

    val objects: List<GridObject> get() = items

    val size: Int get() = items.size

    /**
     * Places an object, replacing whatever already occupied that cell.
     *
     * Replacing rather than refusing is what makes retyping a note a single tap
     * instead of an erase followed by a place.
     */
    fun place(objectToPlace: GridObject, toleranceMs: Double) {
        removeAt(objectToPlace.timeMs, objectToPlace.column, toleranceMs)
        items += objectToPlace
        items.sortBy { it.timeMs }
    }

    /** @return the object removed, or null if the cell was empty. */
    fun removeAt(timeMs: Double, column: Int, toleranceMs: Double): GridObject? {
        val index = items.indexOfFirst {
            it.column == column && it.coversTime(timeMs, toleranceMs)
        }
        if (index < 0) return null
        return items.removeAt(index)
    }

    fun findAt(timeMs: Double, column: Int, toleranceMs: Double): GridObject? =
        items.firstOrNull { it.column == column && it.coversTime(timeMs, toleranceMs) }

    /** Objects overlapping a time range, for drawing only what is on screen. */
    fun between(startMs: Double, endMs: Double): List<GridObject> =
        items.filter { it.endTimeMs >= startMs && it.timeMs <= endMs }

    /** Objects whose head falls inside a time range, for firing preview ticks. */
    fun headsBetween(startMs: Double, endMs: Double): List<GridObject> =
        items.filter { it.timeMs > startMs && it.timeMs <= endMs }

    fun clear() = items.clear()

    /** Replaces everything, for opening a chart that already exists. */
    fun replaceAll(replacements: List<GridObject>) {
        items.clear()
        items += replacements
        items.sortBy { it.timeMs }
    }

    /** Total judgments, matching how the engine counts a long object twice. */
    fun maxCombo(): Int = items.fold(0) { total, item -> total + if (item.isLong) 2 else 1 }

    val lastTimeMs: Double get() = items.maxOfOrNull { it.endTimeMs } ?: 0.0
}
