package dev.rebound.editor

import dev.rebound.core.chart.GridObject
import dev.rebound.core.chart.NoteType

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

    /** Removes a specific object, wherever it sits. @return true if it was there. */
    fun remove(item: GridObject): Boolean = items.remove(item)

    /** Swaps one object for another in place, keeping the list ordered. */
    fun replace(old: GridObject, new: GridObject) {
        val index = items.indexOf(old)
        if (index < 0) return
        items[index] = new
        items.sortBy { it.timeMs }
    }

    /**
     * Whether [item] may be a gold's rally target.
     *
     * A green is judged where a shot cannot arrive and a long has a length a
     * flight cannot carry, so neither can be one.
     */
    fun canRally(item: GridObject): Boolean = item.type in RALLIABLE

    /**
     * Toggles whether the object at a cell is a gold's rally target.
     *
     * Only a tap, a gold or a chain object can be one -- a green is judged where
     * a shot cannot arrive and a long has a length a flight cannot carry -- so a
     * request on anything else is refused.
     *
     * @return true if something changed.
     */
    fun toggleRallied(timeMs: Double, column: Int, toleranceMs: Double): Boolean {
        val index = items.indexOfFirst {
            it.column == column && it.coversTime(timeMs, toleranceMs)
        }
        if (index < 0) return false
        val item = items[index]
        if (item.type !in RALLIABLE) return false
        items[index] = item.copy(rallied = !item.rallied)
        return true
    }

    private companion object {
        // A gold may become any of these; a long has a length no flight carries.
        val RALLIABLE = setOf(NoteType.TAP, NoteType.GOLD, NoteType.GREEN, NoteType.CHAIN)
    }

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
