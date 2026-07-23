package dev.rebound.core.chart

import dev.rebound.core.FieldGeometry
import kotlin.math.abs

/** Thrown with a 1-based source line number so chart authors can find the mistake. */
class ChartParseException(val line: Int, message: String) :
    Exception("line $line: $message")

/**
 * Parser for the `.rbc` chart format.
 *
 * The layout borrows from K-Shoot Mania's `.ksh`, because that design has proven
 * itself for hand-authored charts: plain text, clean diffs, and a measure's
 * rhythm visible in the shape of the block.
 *
 * ```
 * title=Demo Track
 * bpm=150
 * columns=16
 * --
 * 0000000100000000
 * 0000000000000000
 * 0010000000000000
 * 0000000000000000
 * --
 * ```
 *
 * Everything before the first `--` is `key=value` metadata. After that, each
 * block terminated by `--` is one measure of 4 beats, subdivided evenly by the
 * number of rows it contains. A row has one character per column, and a column
 * is a position along the bar -- not a lane. Sixteen columns is fine enough that
 * objects read as landing anywhere, while keeping the source legible.
 *
 * | char | meaning        |
 * |------|----------------|
 * | `0`  | nothing        |
 * | `1`  | tap            |
 * | `2`  | gold           |
 * | `3`  | long start     |
 * | `4`  | long end       |
 * | `5`  | green          |
 *
 * Green objects are judged over a circular tap point rather than at the bar, so
 * they snap onto whichever of the three tap points their column is nearest. The
 * column still decides which one -- put a `5` on the left of the grid and it
 * lands on the left tap point.
 *
 * A `key=value` line *inside* a measure is a mid-song command; `bpm=` is the
 * only one so far, and it takes effect from that row onward.
 */
object ChartParser {

    private const val MEASURE_END = "--"
    private const val BEATS_PER_MEASURE = 4.0

    /** Objects landing within this of each other count as arriving together. */
    private const val SIMULTANEOUS_MS = 90.0

    /** How far apart such objects must land, as a fraction of the bar. */
    private const val MIN_LANDING_GAP = 0.22f

    /** Tries before giving up on finding room; a crowded chord runs out. */
    private const val SEPARATION_ATTEMPTS = 24

    private const val CH_EMPTY = '0'
    private const val CH_TAP = '1'
    private const val CH_GOLD = '2'
    private const val CH_LONG_START = '3'
    private const val CH_LONG_END = '4'
    private const val CH_GREEN = '5'
    private const val CH_CHAIN = '6'

    /** Beyond this gap a chain link is treated as starting a new run. */
    private const val MAX_CHAIN_GAP_BEATS = 1.05

    /**
     * Reads only the header.
     *
     * A song list needs each difficulty's name and level but none of its
     * objects, and there is no reason to build the whole note list to show a row.
     */
    fun parseMeta(text: String): ChartMeta = readHeader(splitLines(text)).first

    fun parse(text: String): Chart {
        val lines = splitLines(text)
        val (meta, headerEnd) = readHeader(lines)
        var cursor = headerEnd

        val notes = mutableListOf<Note>()
        // A long object occupies its column until the matching '4' arrives.
        val openLongs = arrayOfNulls<Pair<Double, Int>>(meta.columns) // startMs to sourceLine

        // The chain being built in each column, so links can find their predecessor.
        val chainAt = IntArray(meta.columns) { -1 }
        val chainTime = DoubleArray(meta.columns)
        val chainX = FloatArray(meta.columns)
        val chainSpawn = FloatArray(meta.columns)

        var bpm = meta.bpm
        var timeMs = meta.offsetMs
        var nextIndex = 0

        val rows = mutableListOf<Pair<String, Int>>()
        val bpmCommands = mutableListOf<Pair<Int, Double>>()

        fun add(type: NoteType, column: Int, startMs: Double, endMs: Double) {
            // A chain continues from the last link written in the same column,
            // unless enough time has passed that it is plainly a new run.
            //
            // Gold takes part on the same terms as a plain link: a chain can be
            // struck open, closed or interrupted by one, so being chainable is a
            // property of the position in the run rather than of the object.
            val chainable = type == NoteType.CHAIN || type == NoteType.GOLD
            val linkTo = if (chainable &&
                chainAt[column] >= 0 &&
                startMs - chainTime[column] <= 60_000.0 / bpm * MAX_CHAIN_GAP_BEATS
            ) {
                chainAt[column]
            } else {
                -1
            }

            // Only the three tap points are fixed positions. Everything that
            // lands on the bar lands somewhere random along it, so the column an
            // author writes in decides *which* tap point a green object takes and
            // nothing at all for the rest. A chain is the exception: its whole
            // run shares one spot, which is the point of it.
            val x = when {
                type == NoteType.GREEN ->
                    FieldGeometry.nearestTapPointX(columnToX(column, meta.columns))
                linkTo >= 0 -> chainX[column]
                else -> randomLandingX(nextIndex, startMs, notes)
            }
            val y = if (type == NoteType.GREEN) FieldGeometry.TAP_POINT_Y else FieldGeometry.BAR_Y

            // Links share the whole path, not only where it ends. Given their own
            // approach angles they would fan out across the field and only meet
            // at the bar, which reads as a diagonal streak rather than as a run
            // arriving on one spot.
            val spawn = if (linkTo >= 0) chainSpawn[column] else spawnXFor(x, nextIndex)

            notes += Note(nextIndex, type, x, spawn, startMs, endMs, y, column, linkTo)

            if (chainable) {
                // A gold left standing alone is simply a gold; nothing follows it
                // and the head it left behind is never picked up.
                chainAt[column] = nextIndex
                chainTime[column] = startMs
                chainX[column] = x
                chainSpawn[column] = spawn
            } else {
                chainAt[column] = -1
            }
            nextIndex++
        }

        fun flushMeasure() {
            if (rows.isEmpty()) {
                timeMs += msPerMeasure(bpm)
                bpmCommands.clear()
                return
            }
            var measureTime = timeMs
            var stepMs = msPerMeasure(bpm) / rows.size

            rows.forEachIndexed { rowIndex, (row, sourceLine) ->
                bpmCommands.filter { it.first == rowIndex }.forEach { (_, newBpm) ->
                    bpm = newBpm
                    stepMs = msPerMeasure(bpm) / rows.size
                }
                if (row.length != meta.columns) {
                    throw ChartParseException(
                        sourceLine,
                        "row \"$row\" has ${row.length} columns, chart declares ${meta.columns}",
                    )
                }
                row.forEachIndexed { column, ch ->
                    when (ch) {
                        CH_EMPTY -> Unit
                        CH_TAP -> add(NoteType.TAP, column, measureTime, measureTime)
                        CH_GOLD -> add(NoteType.GOLD, column, measureTime, measureTime)
                        CH_GREEN -> add(NoteType.GREEN, column, measureTime, measureTime)
                        CH_CHAIN -> add(NoteType.CHAIN, column, measureTime, measureTime)
                        CH_LONG_START -> {
                            if (openLongs[column] != null) {
                                throw ChartParseException(
                                    sourceLine,
                                    "column $column already has an open long object",
                                )
                            }
                            openLongs[column] = measureTime to sourceLine
                        }
                        CH_LONG_END -> {
                            val open = openLongs[column] ?: throw ChartParseException(
                                sourceLine,
                                "long end in column $column with no matching start",
                            )
                            add(NoteType.LONG, column, open.first, measureTime)
                            openLongs[column] = null
                        }
                        else -> throw ChartParseException(sourceLine, "unknown object character '$ch'")
                    }
                }
                measureTime += stepMs
            }
            timeMs = measureTime
            rows.clear()
            bpmCommands.clear()
        }

        while (cursor < lines.size) {
            val raw = lines[cursor]
            cursor++
            val line = raw.substringBefore("//").trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            when {
                line == MEASURE_END -> flushMeasure()

                line.contains('=') -> {
                    val key = line.substringBefore('=').trim().lowercase()
                    val value = line.substringAfter('=').trim()
                    when (key) {
                        "bpm" -> {
                            val v = value.toDoubleOrNull()
                                ?: throw ChartParseException(cursor, "bpm \"$value\" is not a number")
                            if (v <= 0.0) throw ChartParseException(cursor, "bpm must be positive")
                            bpmCommands += rows.size to v
                        }
                        else -> throw ChartParseException(cursor, "unknown command \"$key\"")
                    }
                }

                else -> rows += line to cursor
            }
        }
        if (rows.isNotEmpty()) flushMeasure()

        openLongs.forEachIndexed { column, open ->
            if (open != null) {
                throw ChartParseException(open.second, "long object in column $column is never closed")
            }
        }

        return Chart(meta, notes.sortedBy { it.timeMs })
    }

    private fun splitLines(text: String): List<String> =
        text
            // A UTF-8 byte order mark, which Windows editors add by default,
            // would otherwise become part of the first header key.
            .removePrefix("﻿")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')

    /** @return the metadata, and the line index the first measure starts at. */
    private fun readHeader(lines: List<String>): Pair<ChartMeta, Int> {
        val header = mutableMapOf<String, String>()
        var cursor = 0

        while (cursor < lines.size) {
            val raw = lines[cursor]
            cursor++
            val line = raw.substringBefore("//").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line == MEASURE_END) break
            val key = line.substringBefore('=', missingDelimiterValue = "").trim()
            if (key.isEmpty()) {
                throw ChartParseException(cursor, "expected 'key=value' or '--', got \"$raw\"")
            }
            header[key.lowercase()] = line.substringAfter('=').trim()
        }

        val meta = ChartMeta(
            title = header["title"] ?: "Untitled",
            artist = header["artist"] ?: "Unknown",
            audio = header["audio"] ?: "",
            bpm = header.requireDouble("bpm", cursor),
            offsetMs = header["offset"]?.toDoubleOrNull() ?: 0.0,
            columns = header["columns"]?.toIntOrNull() ?: 16,
            level = header["level"]?.toIntOrNull() ?: 1,
            difficulty = header["difficulty"] ?: "NORMAL",
            endMs = header["end"]?.toDoubleOrNull() ?: 0.0,
        )
        return meta to cursor
    }

    /** Column centres, so the outermost objects sit inside the bar rather than on its ends. */
    private fun columnToX(column: Int, columns: Int): Float =
        if (columns == 1) 0.5f else (column + 0.5f) / columns

    /**
     * Where an object's path begins on the far side.
     *
     * Scattered rather than mirrored: in the original the approach angles are all
     * over the field, and a strict mirror produces a tidy symmetry that makes a
     * chart look mechanical. The origin is drawn from the half opposite the
     * landing position, so every object still crosses the field at a readable
     * diagonal instead of dropping straight down.
     *
     * Hashed from the object's index rather than randomised at runtime, so a
     * chart looks the same every time it is played.
     */
    /**
     * Where an object lands on the bar.
     *
     * Scattered, because in this game only the tap points are fixed -- the bar is
     * one unbroken line and an object may cross it anywhere. Hashed from the
     * object's index rather than drawn at runtime, so a chart plays the same way
     * every time and two people playing the same chart face the same field.
     *
     * Objects landing at nearly the same moment are pushed apart. Two of them on
     * top of each other cannot be struck separately however well the player
     * reads them, so a chord written as two objects has to arrive as two.
     */
    private fun randomLandingX(index: Int, timeMs: Double, placed: List<Note>): Float {
        repeat(SEPARATION_ATTEMPTS) { attempt ->
            val candidate = hashedX(index, attempt)
            if (!clashes(candidate, timeMs, placed)) return candidate
        }
        // Every candidate was crowded; take the first rather than loop forever.
        return hashedX(index, 0)
    }

    private fun clashes(x: Float, timeMs: Double, placed: List<Note>): Boolean {
        for (note in placed.asReversed()) {
            // Time-ordered, so once one is far enough back so is everything before it.
            if (timeMs - note.timeMs > SIMULTANEOUS_MS) return false
            if (note.y != FieldGeometry.BAR_Y) continue
            if (abs(note.x - x) < MIN_LANDING_GAP) return true
        }
        return false
    }

    private fun hashedX(index: Int, salt: Int): Float {
        var h = index * -0x3361D2AF xor (salt * 0x27D4EB2F)
        h = h xor (h ushr 15)
        h *= -0x7A143595
        h = h xor (h ushr 13)
        val unit = ((h ushr 8) and 0xFFFF) / 65535f
        // Kept off the walls so an object is never half outside the field.
        return 0.08f + unit * 0.84f
    }

    private fun spawnXFor(x: Float, index: Int): Float {
        var h = index * -0x61C88647
        h = h xor (h ushr 16)
        h *= -0x7A143595
        h = h xor (h ushr 13)
        val unit = ((h ushr 8) and 0xFFFF) / 65535f

        val farHalfStart = if (x < 0.5f) 0.55f else 0.05f
        return (farHalfStart + unit * 0.40f).coerceIn(0.04f, 0.96f)
    }

    private fun msPerMeasure(bpm: Double) = 60_000.0 / bpm * BEATS_PER_MEASURE

    private fun Map<String, String>.requireDouble(key: String, line: Int): Double {
        val raw = this[key] ?: throw ChartParseException(line, "missing required header \"$key\"")
        return raw.toDoubleOrNull() ?: throw ChartParseException(line, "\"$key=$raw\" is not a number")
    }
}
