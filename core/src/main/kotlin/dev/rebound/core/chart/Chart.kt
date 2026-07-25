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
 * @param spawnX where its path begins, in *unfolded* space -- a value outside
 *   0..1 means the approach meets a side wall and comes off it. See [approachX].
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
    /**
     * For a [NoteType.GOLD], the object on the far side that this one becomes
     * when it is struck, or -1 if it becomes nothing.
     *
     * Decided when the chart is read rather than hunted for during play. The two
     * sides play the same chart, so the pairing is a property of the chart
     * itself -- and fixing it up front is what lets the far side know an object
     * is *conditional* before the moment it would otherwise have appeared.
     */
    val rallyTargetIndex: Int = -1,
    /**
     * The gold whose strike brings this object into existence, or -1 for an
     * object that arrives under its own power.
     *
     * An object with a source does not exist until that gold is struck: it is
     * not drawn, cannot be pressed, and is never missed. Striking gold has never
     * *created* anything -- it changes how an object arrives -- so when the gold
     * is let through, there is nothing for the arrival to happen to.
     */
    val rallySourceIndex: Int = -1,
    /**
     * True when this object's rally pairing was written into the chart by hand
     * (`7`/`8`/`9`) rather than chosen for it.
     *
     * Play does not care -- an arriving object arrives the same either way. The
     * editor does: an author's explicit choice must survive a round trip, while
     * an automatic one is re-derived on every save and would only get in the way
     * if it were pinned down.
     */
    val rallyExplicit: Boolean = false,
    /**
     * Chain brackets, for the editor's round trip -- play joins links by
     * [chainPrevIndex] and never reads these. A run opens at a [chainStart] link
     * and closes at a [chainStop] one; both are ordinary chain links otherwise.
     */
    val chainStart: Boolean = false,
    val chainStop: Boolean = false,
) {
    val isLong: Boolean get() = type == NoteType.LONG

    val durationMs: Double get() = endTimeMs - timeMs

    /** Long objects are judged twice, on the press and on the release. */
    val judgmentCount: Int get() = if (isLong) 2 else 1

    /** True when this object only appears if some gold is struck for it. */
    val isRallyTarget: Boolean get() = rallySourceIndex >= 0

    /** Judged up at a tap point: a green, or a green (tap-point) long. */
    val isTapPoint: Boolean get() = y == FieldGeometry.TAP_POINT_Y

    /** A long held up at a tap point rather than at the bar. */
    val isGreenLong: Boolean get() = isLong && isTapPoint

    /**
     * Where the object sits across the field, [progress] of the way in.
     *
     * The straight line from [spawnX] to [x] is drawn in unfolded space and
     * folded back, so a spawn outside 0..1 comes in off a side wall. Objects
     * mostly cross the field cleanly; the ones that carom are what stop a chart
     * from reading as a field of parallel diagonals.
     */
    fun approachX(progress: Float): Float =
        FieldGeometry.fold(spawnX + (x - spawnX) * progress.coerceIn(0f, 1f))

    /**
     * How far into its approach the object meets its *last* wall, or 0 if it
     * never meets one.
     *
     * A long object uses this as the point it starts to stretch from: it comes in
     * looking like a single object, bouncing along compact, and only off the last
     * wall does it pay its length out toward the bar. A long that meets no wall
     * has 0 here and so stretches from the very start.
     */
    val bounceProgress: Float get() = FieldGeometry.lastWallProgress(spawnX, x)

    /** True when the approach carries the object into a side wall. */
    val bouncesOnApproach: Boolean get() = FieldGeometry.wallCrossings(spawnX, x) > 0
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

    private val byIndex: Map<Int, Note> = notes.associateBy { it.index }

    /** The object with this index, or null. -1 is the "no object" index. */
    fun noteAt(index: Int): Note? = if (index < 0) null else byIndex[index]

    init {
        require(meta.columns > 0) { "chart must have at least one column" }
        notes.forEach {
            require(it.x in 0f..1f) { "note ${it.index} sits at x=${it.x}, outside 0..1" }
            require(it.y in 0f..1f) { "note ${it.index} sits at y=${it.y}, outside 0..1" }
        }
    }
}
