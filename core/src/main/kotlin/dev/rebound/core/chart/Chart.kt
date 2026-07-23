package dev.rebound.core.chart

import dev.rebound.core.FieldGeometry

/**
 * The object kinds the prototype supports.
 *
 * Two things separate them: where they are judged, and what happens after a hit.
 * [TAP], [GOLD] and [LONG] are all struck as they cross the player's bar, and
 * only [GOLD] does anything afterwards. [GREEN] is the exception -- it is judged
 * higher up the field, as it passes over one of the fixed circular tap points.
 */
enum class NoteType {
    /** Plain object. Disappears when struck. */
    TAP,

    /** Struck the same way, but flies off toward the opponent afterwards. */
    GOLD,

    /** Held from the moment it reaches the bar until its tail passes. */
    LONG,

    /** Judged over a circular tap point rather than at the bar. */
    GREEN,

    /**
     * One link of a run that crosses the same spot at the same interval.
     *
     * Struck like a tap. What makes it a chain is that the whole run shares a
     * landing position, so the hand stays put and only the rhythm moves.
     */
    CHAIN,
}

/**
 * A single playable object.
 *
 * Position is a continuous fraction of the field rather than a lane index. That
 * is the defining property of this style of game: the bar is one unbroken line,
 * objects cross it anywhere along its length, and the player's job is to be in
 * the right *place* as well as at the right *time*.
 *
 * @param x where the object is judged horizontally. 0 is the left wall, 1 the right.
 * @param y where it is judged vertically, in field space -- [FieldGeometry.BAR_Y]
 *   for most objects, [FieldGeometry.TAP_POINT_Y] for green ones.
 * @param spawnX where its path begins on the far side, giving each object the
 *   diagonal approach that makes the field readable.
 * @param timeMs when it reaches its judgment point and must be struck.
 * @param endTimeMs for [NoteType.LONG], when the finger may leave.
 */
data class Note(
    val index: Int,
    val type: NoteType,
    val x: Float,
    val spawnX: Float,
    val timeMs: Double,
    val endTimeMs: Double = timeMs,
    val y: Float = FieldGeometry.BAR_Y,
    /**
     * The authoring cell this came from.
     *
     * Play does not use it -- a bar object lands wherever it lands -- but an
     * editor reopening a chart needs the grid back, and the parser is the only
     * place that still knows it.
     */
    val column: Int = 0,
    /**
     * The link before this one in a chain, or -1 if this starts one.
     *
     * Only the drawing uses it -- the objects are judged independently -- but a
     * chain that is not visibly joined is just a fast run of taps.
     */
    val chainPrevIndex: Int = -1,
) {
    val isLong: Boolean get() = type == NoteType.LONG

    val durationMs: Double get() = endTimeMs - timeMs

    /** Long objects are judged twice, on the press and on the release. */
    val judgmentCount: Int get() = if (isLong) 2 else 1
}

data class ChartMeta(
    val title: String = "Untitled",
    val artist: String = "Unknown",
    val audio: String = "",
    val bpm: Double = 120.0,
    /**
     * Milliseconds from the first audio sample to the first beat of measure 1.
     * A property of the chart, distinct from the per-device playback latency the
     * player calibrates in settings.
     */
    val offsetMs: Double = 0.0,
    /**
     * How many discrete positions the authoring grid divides the bar into. Higher
     * values give finer placement at the cost of wider rows in the source file.
     */
    val columns: Int = 16,
    val level: Int = 1,
    val difficulty: String = "NORMAL",
    /**
     * Where the chart is over, in milliseconds.
     *
     * Zero means "when the last object has been dealt with". Set it to hold the
     * run open past that -- a song with an outro should be allowed to finish
     * rather than having the result thrown up over it.
     */
    val endMs: Double = 0.0,
)

data class Chart(
    val meta: ChartMeta,
    val notes: List<Note>,
) {
    /** Total judgments available, i.e. the combo shown on a perfect clear. */
    val maxCombo: Int = notes.sumOf { it.judgmentCount }

    /** When the run is over: the last object, or the chart's own end if later. */
    val durationMs: Double =
        maxOf(notes.maxOfOrNull { it.endTimeMs } ?: 0.0, meta.endMs)

    init {
        require(meta.columns > 0) { "chart must have at least one column" }
        notes.forEach {
            require(it.x in 0f..1f) { "note ${it.index} sits at x=${it.x}, outside 0..1" }
            require(it.y in 0f..1f) { "note ${it.index} sits at y=${it.y}, outside 0..1" }
        }
    }
}
