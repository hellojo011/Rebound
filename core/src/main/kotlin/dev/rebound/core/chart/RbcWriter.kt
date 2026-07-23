package dev.rebound.core.chart

import kotlin.math.abs
import kotlin.math.roundToLong

/** Thrown when objects cannot be laid onto a measure grid. */
class ChartWriteException(message: String) : Exception(message)

/**
 * Turns the editor's grid of objects back into `.rbc` text.
 *
 * The interesting decision is how many rows a measure gets. Writing every chart
 * at the finest subdivision the format allows would produce files that are
 * mostly zeroes and miserable to read or hand-edit; writing at too coarse a one
 * would silently move objects. So the resolution is derived from the chart:
 * the coarsest subdivision that still lands every object exactly on a row.
 */
object RbcWriter {

    private const val BEATS_PER_MEASURE = 4

    /**
     * Subdivisions per beat that may be tried, coarsest first.
     *
     * Includes thirds so triplets survive a round trip rather than being forced
     * onto the nearest sixteenth.
     */
    private val CANDIDATE_DIVISIONS = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16)

    /** How far off a row an object may be and still count as on it. */
    private const val STEP_TOLERANCE = 0.02

    fun write(meta: ChartMeta, objects: List<GridObject>): String {
        val header = buildString {
            appendLine("title=${meta.title}")
            appendLine("artist=${meta.artist}")
            if (meta.audio.isNotEmpty()) appendLine("audio=${meta.audio}")
            appendLine("bpm=${trimNumber(meta.bpm)}")
            appendLine("offset=${trimNumber(meta.offsetMs)}")
            appendLine("columns=${meta.columns}")
            appendLine("level=${meta.level}")
            appendLine("difficulty=${meta.difficulty}")
            // Omitted when unset, so charts that end with their last object stay
            // as short as they read.
            if (meta.endMs > 0.0) appendLine("end=${trimNumber(meta.endMs)}")
            appendLine("--")
        }

        if (objects.isEmpty()) return header

        objects.forEach {
            if (it.column !in 0 until meta.columns) {
                throw ChartWriteException(
                    "object at ${it.timeMs} ms is in column ${it.column}, " +
                        "outside the chart's ${meta.columns}",
                )
            }
            if (it.timeMs < meta.offsetMs - 1.0) {
                throw ChartWriteException(
                    "object at ${it.timeMs} ms starts before the first beat " +
                        "(offset ${meta.offsetMs} ms)",
                )
            }
        }

        val division = divisionFor(objects, meta.bpm, meta.offsetMs)
        val rowsPerMeasure = BEATS_PER_MEASURE * division
        val stepMs = 60_000.0 / meta.bpm / division

        fun stepOf(timeMs: Double): Long =
            ((timeMs - meta.offsetMs) / stepMs).roundToLong().coerceAtLeast(0L)

        val lastStep = objects.maxOf { stepOf(it.endTimeMs) }
        val measures = (lastStep / rowsPerMeasure + 1).toInt()

        // One CharArray per row, filled with '0' and then written into.
        val grid = Array(measures * rowsPerMeasure) { CharArray(meta.columns) { '0' } }

        objects.forEach { item ->
            val start = stepOf(item.timeMs).toInt()
            if (item.isLong) {
                val end = stepOf(item.endTimeMs).toInt()
                if (end <= start) {
                    throw ChartWriteException(
                        "long object at ${item.timeMs} ms has no length once quantised",
                    )
                }
                grid[start][item.column] = '3'
                grid[end][item.column] = '4'
            } else {
                grid[start][item.column] = charFor(item.type)
            }
        }

        return buildString {
            append(header)
            for (measure in 0 until measures) {
                for (row in 0 until rowsPerMeasure) {
                    appendLine(String(grid[measure * rowsPerMeasure + row]))
                }
                appendLine("--")
            }
        }
    }

    /**
     * The coarsest subdivision per beat that places every object exactly.
     *
     * @throws ChartWriteException if even the finest candidate cannot, which
     *   means an object is off the grid entirely rather than merely fine.
     */
    fun divisionFor(objects: List<GridObject>, bpm: Double, offsetMs: Double): Int {
        val beatMs = 60_000.0 / bpm

        for (division in CANDIDATE_DIVISIONS) {
            val stepMs = beatMs / division
            val fits = objects.all { item ->
                landsOnStep(item.timeMs, offsetMs, stepMs) &&
                    (!item.isLong || landsOnStep(item.endTimeMs, offsetMs, stepMs))
            }
            if (fits) return division
        }

        throw ChartWriteException(
            "some objects do not line up with any supported subdivision; " +
                "check the tempo and offset",
        )
    }

    private fun landsOnStep(timeMs: Double, offsetMs: Double, stepMs: Double): Boolean {
        val steps = (timeMs - offsetMs) / stepMs
        return abs(steps - Math.round(steps)) < STEP_TOLERANCE
    }

    private fun charFor(type: NoteType): Char = when (type) {
        NoteType.TAP -> '1'
        NoteType.GOLD -> '2'
        NoteType.GREEN -> '5'
        NoteType.CHAIN -> '6'
        NoteType.LONG -> '3'
    }

    /** Writes 150 rather than 150.0, but keeps 150.25. */
    private fun trimNumber(value: Double): String =
        if (abs(value - value.toLong()) < 1e-9) value.toLong().toString() else value.toString()
}
