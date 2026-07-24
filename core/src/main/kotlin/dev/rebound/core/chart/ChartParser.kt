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
 * | `6`  | chain link (middle)  |
 * | `7`  | tap, rallied         |
 * | `8`  | gold, rallied        |
 * | `9`  | chain, rallied       |
 * | `G`  | green, rallied       |
 * | `[`  | chain start          |
 * | `]`  | chain stop           |
 * | `{`  | green long start     |
 * | `}`  | green long end       |
 *
 * The rallied forms are the same objects as `1`, `2`, `5` and `6`, marked as the
 * thing a gold object becomes: written this way, an object arrives on the shot
 * from the nearest earlier gold that has not been spoken for, and does not
 * arrive at all if that gold is let through. Golds with nothing marked for them
 * pick the next eligible object by themselves, so a chart need never use these.
 *
 * A gold may become a tap, a gold, a green (a TOP object, arriving up at its tap
 * point) or the *first* link of a chain. A long object cannot be one -- its
 * length is more than a flight can carry -- nor can a chain's later links, since
 * a shot dropped into the middle of a run would tear it apart.
 *
 * A chain is spelled out by hand rather than guessed from spacing: `[` opens a
 * run and `]` closes it, both in the same column. **Every object between them
 * joins** -- a tap `1`, a gold `2`, a plain link `6`, all become links of the run
 * and keep their own judgment; only a long or a green cannot. Two columns hold
 * two separate chains however they interleave, and an object outside any bracket
 * joins nothing.
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
    private const val CH_TAP_RALLIED = '7'
    private const val CH_GOLD_RALLIED = '8'
    private const val CH_CHAIN_RALLIED = '9'
    private const val CH_GREEN_RALLIED = 'G'

    // A chain is delimited by hand, not guessed from spacing: a chain-start link
    // opens a run and a chain-stop link closes it. The plain link `6` sits in the
    // middle, and a gold between the two joins the run as an ordinary member.
    private const val CH_CHAIN_START = '['
    private const val CH_CHAIN_STOP = ']'

    // A long object judged up at a tap point rather than at the bar -- a green
    // hold. Its column picks which tap point, exactly as a green `5` does.
    private const val CH_GREEN_LONG_START = '{'
    private const val CH_GREEN_LONG_END = '}'

    /**
     * A long object still waiting for its end.
     *
     * Its landing is fixed the moment it starts, not when it ends, so that every
     * object dropped *during* the hold can be kept off the same spot -- a bar long
     * sits on its landing for its whole length, and a tap sharing that spot would
     * be unreadable and unstrikeable while the long is held.
     */
    private data class OpenLong(
        val startMs: Double,
        val line: Int,
        val green: Boolean,
        val index: Int,
        val x: Float,
    )

    /**
     * How much room a gold wants before the object it becomes.
     *
     * Generous on purpose. An object that would already be part-way down its own
     * approach cannot be handed to a shot without appearing to jump, so a gold
     * skips ahead to one that has not set off yet. This is the default approach
     * time, which is the honest measure of "not yet on its way".
     */
    private const val RALLY_LEAD_MS = 1800.0

    /**
     * The shortest flight an auto-picked rally may make.
     *
     * An object only a beat or so ahead makes the shot cross the whole field in
     * a blink, which reads as a glitch rather than a rally. So a gold skips a
     * target this close and takes the next one out -- or the one after that.
     * Roughly two beats at a typical tempo.
     */
    private const val MIN_RALLY_LEAD_MS = 900.0

    /** How far out a gold will look for something to become. */
    private const val MAX_RALLY_LEAD_MS = 6000.0

    /** Objects that carom off a side wall on the way in, roughly. */
    private const val BOUNCE_SHARE = 0.34f

    /**
     * Reads only the header.
     *
     * A song list needs each difficulty's name and level but none of its
     * objects, and there is no reason to build the whole note list to show a row.
     */
    fun parseMeta(text: String): ChartMeta = readHeader(splitLines(text)).first

    /**
     * @param positionSeed mixes into the landing and approach positions. In this
     *   game where objects land at random points along the bar, a fresh seed each
     *   play scatters them somewhere new; the default 0 keeps a chart identical
     *   every time, which is what the editor's preview and the tests rely on.
     *   The chain shape and the tap-point snapping are unaffected -- only where a
     *   run comes to rest and how objects approach move with the seed.
     */
    fun parse(text: String, positionSeed: Int = 0): Chart {
        val lines = splitLines(text)
        val (meta, headerEnd) = readHeader(lines)
        var cursor = headerEnd

        val notes = mutableListOf<Note>()
        // A long object occupies its column until its end arrives. The flag says
        // whether it is a green (tap-point) hold, so the end knows which it was.
        val openLongs = arrayOfNulls<OpenLong>(meta.columns)

        var bpm = meta.bpm
        var timeMs = meta.offsetMs
        var nextIndex = 0

        val rows = mutableListOf<Pair<String, Int>>()
        val bpmCommands = mutableListOf<Pair<Int, Double>>()

        // Objects the author marked as a gold's landing (the 7/8/9/G forms), and
        // the chain-bracket flags. Both feed the post-passes below, which fix up
        // chain links and rally pairings once every object is known -- neither can
        // be settled object by object, because a chain link and the gold that
        // becomes it both depend on what comes after them.
        val ralliedFlag = HashMap<Int, Boolean>()
        val chainStart = HashSet<Int>()
        val chainStop = HashSet<Int>()

        // The landings of every long currently being held, so a bar object dropped
        // during the hold is kept off them.
        fun heldLongXs(): List<Float> =
            openLongs.filterNotNull().filter { !it.green }.map { it.x }

        fun add(
            type: NoteType,
            column: Int,
            startMs: Double,
            endMs: Double,
            rallied: Boolean = false,
            /** A tap-point object: a green, or a green (tap-point) long. */
            atTapPoint: Boolean = false,
        ): Int {
            // Only the three tap points are fixed positions. Everything that lands
            // on the bar lands somewhere random along it, so the column an author
            // writes in decides *which* tap point a tap-point object takes and
            // nothing at all for the rest -- including a chain link, which gets its
            // own landing until the post-pass gives it its run's shared one.
            val x = if (atTapPoint) {
                FieldGeometry.nearestTapPointX(columnToX(column, meta.columns))
            } else {
                randomLandingX(nextIndex, startMs, notes, positionSeed, heldLongXs())
            }
            val y = if (atTapPoint) FieldGeometry.TAP_POINT_Y else FieldGeometry.BAR_Y

            // A green object is short and never caroms; everything else gets its own
            // spawn, caroming or not by the hash.
            val spawn = if (atTapPoint) {
                plainSpawnX(x, unitFor(nextIndex, SPAWN_SALT, positionSeed))
            } else {
                spawnXFor(x, nextIndex, type, positionSeed)
            }

            // Chain links are set later, in the post-pass, once the whole run is
            // known; here every object starts unjoined.
            notes += Note(nextIndex, type, x, spawn, startMs, endMs, y, column)
            if (rallied) ralliedFlag[nextIndex] = true
            return nextIndex++
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
                        CH_GREEN ->
                            add(NoteType.GREEN, column, measureTime, measureTime, atTapPoint = true)
                        CH_CHAIN -> add(NoteType.CHAIN, column, measureTime, measureTime)
                        CH_TAP_RALLIED ->
                            add(NoteType.TAP, column, measureTime, measureTime, rallied = true)
                        CH_GOLD_RALLIED ->
                            add(NoteType.GOLD, column, measureTime, measureTime, rallied = true)
                        CH_CHAIN_RALLIED ->
                            add(NoteType.CHAIN, column, measureTime, measureTime, rallied = true)
                        CH_GREEN_RALLIED ->
                            add(NoteType.GREEN, column, measureTime, measureTime, rallied = true, atTapPoint = true)
                        CH_CHAIN_START ->
                            chainStart += add(NoteType.CHAIN, column, measureTime, measureTime)
                        CH_CHAIN_STOP ->
                            chainStop += add(NoteType.CHAIN, column, measureTime, measureTime)
                        CH_LONG_START, CH_GREEN_LONG_START -> {
                            if (openLongs[column] != null) {
                                throw ChartParseException(
                                    sourceLine,
                                    "column $column already has an open long object",
                                )
                            }
                            // Fix the landing now, so held longs keep later objects
                            // off their spot for the whole length of the hold.
                            val green = ch == CH_GREEN_LONG_START
                            val idx = nextIndex++
                            val lx = if (green) {
                                FieldGeometry.nearestTapPointX(columnToX(column, meta.columns))
                            } else {
                                randomLandingX(idx, measureTime, notes, positionSeed, heldLongXs())
                            }
                            openLongs[column] = OpenLong(measureTime, sourceLine, green, idx, lx)
                        }
                        CH_LONG_END, CH_GREEN_LONG_END -> {
                            val open = openLongs[column] ?: throw ChartParseException(
                                sourceLine,
                                "long end in column $column with no matching start",
                            )
                            val greenEnd = ch == CH_GREEN_LONG_END
                            if (greenEnd != open.green) {
                                throw ChartParseException(
                                    sourceLine,
                                    "long end in column $column does not match its start",
                                )
                            }
                            val y = if (open.green) FieldGeometry.TAP_POINT_Y else FieldGeometry.BAR_Y
                            val spawn = if (open.green) {
                                plainSpawnX(open.x, unitFor(open.index, SPAWN_SALT, positionSeed))
                            } else {
                                longSpawnXFor(open.x, open.index, positionSeed)
                            }
                            notes += Note(
                                open.index, NoteType.LONG, open.x, spawn,
                                open.startMs, measureTime, y, column,
                            )
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
                throw ChartParseException(open.line, "long object in column $column is never closed")
            }
        }

        val chainPrevOf = linkChains(notes, chainStart, chainStop)
        val headOf = chainHeads(chainPrevOf)
        val byIndex = notes.associateBy { it.index }
        val (targetOfGold, goldOfTarget, explicitTargets) =
            pairRallies(notes, ralliedFlag, chainPrevOf)

        val paired = notes.map { note ->
            // Every link of one chain shares its head's path -- the same spawn
            // and the same landing -- so a run strings its beads along a single
            // line and all of them come to rest on one spot. Separate chains
            // have separate heads, each with its own random landing, so two runs
            // may sit anywhere along the bar relative to each other. The shared
            // spawn never caroms, so the line joining the beads never kinks.
            val head = headOf[note.index]?.let { byIndex[it] }
            val x = head?.x ?: note.x
            val spawn =
                if (head != null) plainSpawnX(head.x, unitFor(head.index, SPAWN_SALT, positionSeed))
                else note.spawnX
            note.copy(
                x = x,
                spawnX = spawn,
                chainPrevIndex = chainPrevOf[note.index] ?: -1,
                rallyTargetIndex = targetOfGold[note.index] ?: -1,
                rallySourceIndex = goldOfTarget[note.index] ?: -1,
                rallyExplicit = note.index in explicitTargets,
                chainStart = note.index in chainStart,
                chainStop = note.index in chainStop,
            )
        }
        return Chart(meta, paired.sortedBy { it.timeMs })
    }

    /**
     * Traces every chain member back to the link that begins its run.
     *
     * Members of the same chain resolve to the same head, which is how a run
     * comes to share one landing; a member of a different chain resolves to a
     * different head, which is how two runs land apart.
     *
     * @return each chain member's head index; non-members are absent.
     */
    private fun chainHeads(chainPrevOf: Map<Int, Int>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (member in chainPrevOf.keys + chainPrevOf.values) {
            var head = member
            while (chainPrevOf[head] != null) head = chainPrevOf.getValue(head)
            out[member] = head
        }
        return out
    }

    /**
     * Joins a chain's links, using the author's own brackets rather than spacing.
     *
     * A run opens at an object in [chainStart] and closes at the next one in
     * [chainStop] in the same column; **every object between them joins**, in time
     * order, whatever its type -- a tap, a gold or a chain link all become links
     * of the run and keep their own judgment. The brackets alone decide what is
     * chained, which is exactly what the author drew; nothing is guessed from how
     * near two objects happen to fall.
     *
     * A long cannot be a link (it has a length a bead cannot), and a green lives
     * on a tap-point column a bar chain never touches, so both are passed over.
     * A column is walked on its own, so two columns hold two separate chains
     * however they interleave. An object outside any bracket joins nothing.
     *
     * @return each linked object's predecessor, by index.
     */
    private fun linkChains(
        notes: List<Note>,
        chainStart: Set<Int>,
        chainStop: Set<Int>,
    ): Map<Int, Int> {
        val prevOf = HashMap<Int, Int>()
        // The open run's last link per column, or absent when no run is open there.
        val openPrev = HashMap<Int, Int>()

        for (note in notes.sortedWith(compareBy({ it.timeMs }, { it.index }))) {
            if (note.type == NoteType.LONG || note.type == NoteType.GREEN) continue
            val column = note.column

            when {
                note.index in chainStart -> openPrev[column] = note.index
                openPrev[column] != null -> {
                    prevOf[note.index] = openPrev.getValue(column)
                    if (note.index in chainStop) openPrev.remove(column) else openPrev[column] = note.index
                }
                // Not in a run and not a start: a lone object, joined to nothing.
            }
            // A start that is also a stop is a one-link run: close it at once.
            if (note.index in chainStop && note.index in chainStart) openPrev.remove(column)
        }
        return prevOf
    }

    private data class Rallies(
        val targetOfGold: Map<Int, Int>,
        val goldOfTarget: Map<Int, Int>,
        val explicit: Set<Int>,
    )

    /**
     * Works out, for every gold, the object it becomes when struck.
     *
     * What a gold may become is a tap, another gold, a green (a TOP object,
     * caught up at its tap point) or the *first* link of a chain. A long object
     * cannot be one -- its length is more than a flight can carry -- nor can a
     * chain's later links, since a shot dropped into a run would split it.
     *
     * Two ways in. An object the author marked (the 7/8/9/G forms) is pinned to
     * the nearest earlier gold that has not been claimed. Every gold still left
     * over then picks the next eligible object by itself, preferring one that has
     * not started its own approach so the rally reads as a proper flight rather
     * than a jump. A gold with nothing in reach simply sends its shot away empty.
     */
    private fun pairRallies(
        notes: List<Note>,
        ralliedFlag: Map<Int, Boolean>,
        chainPrevOf: Map<Int, Int>,
    ): Rallies {
        val targetOfGold = HashMap<Int, Int>()
        val goldOfTarget = HashMap<Int, Int>()
        val explicit = HashSet<Int>()
        val byTime = notes.sortedWith(compareBy({ it.timeMs }, { it.index }))

        fun eligible(note: Note): Boolean =
            (chainPrevOf[note.index] ?: -1) < 0 &&
                note.index !in goldOfTarget &&
                when (note.type) {
                    NoteType.TAP, NoteType.GOLD, NoteType.GREEN, NoteType.CHAIN -> true
                    NoteType.LONG -> false
                }

        // Authored pairings first, so an explicit choice is never stolen by the
        // automatic one that follows.
        for (note in byTime) {
            if (ralliedFlag[note.index] != true || !eligible(note)) continue
            val gold = byTime.lastOrNull {
                it.type == NoteType.GOLD &&
                    it.index != note.index &&
                    it.timeMs <= note.timeMs &&
                    it.index !in targetOfGold
            } ?: continue
            targetOfGold[gold.index] = note.index
            goldOfTarget[note.index] = gold.index
            explicit += note.index
        }

        for (gold in byTime) {
            if (gold.type != NoteType.GOLD || gold.index in targetOfGold) continue

            val reachable = byTime.filter {
                it.index != gold.index &&
                    it.timeMs - gold.timeMs in MIN_RALLY_LEAD_MS..MAX_RALLY_LEAD_MS &&
                    eligible(it)
            }
            val target = reachable.firstOrNull { it.timeMs - gold.timeMs >= RALLY_LEAD_MS }
                ?: reachable.firstOrNull()
                ?: continue

            targetOfGold[gold.index] = target.index
            goldOfTarget[target.index] = gold.index
        }

        return Rallies(targetOfGold, goldOfTarget, explicit)
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
     * Where an object lands on the bar.
     *
     * Scattered, because in this game only the tap points are fixed -- the bar is
     * one unbroken line and an object may cross it anywhere. Hashed from the
     * object's index and the run's [seed], so within one play both sides face the
     * same field, while a fresh seed each play lands everything somewhere new.
     *
     * Objects landing at nearly the same moment are pushed apart. Two of them on
     * top of each other cannot be struck separately however well the player
     * reads them, so a chord written as two objects has to arrive as two.
     */
    private fun randomLandingX(
        index: Int,
        timeMs: Double,
        placed: List<Note>,
        seed: Int,
        blocked: List<Float>,
    ): Float {
        repeat(SEPARATION_ATTEMPTS) { attempt ->
            val candidate = hashedX(index, attempt, seed)
            if (!clashes(candidate, timeMs, placed, blocked)) return candidate
        }
        // Every candidate was crowded; take the first rather than loop forever.
        return hashedX(index, 0, seed)
    }

    /**
     * Whether landing at [x] at [timeMs] would collide with something already on
     * the bar there.
     *
     * [blocked] holds the landings of longs *currently being held*, whose ends
     * have not been read yet, so they are not in [placed]. A long that has already
     * finished is in [placed] and is judged by its whole span, since its head sits
     * on its landing from head to tail -- not just for the instant it arrives.
     */
    private fun clashes(x: Float, timeMs: Double, placed: List<Note>, blocked: List<Float>): Boolean {
        if (blocked.any { abs(it - x) < MIN_LANDING_GAP }) return true
        for (note in placed) {
            if (note.y != FieldGeometry.BAR_Y) continue
            val overlaps = if (note.isLong) {
                timeMs in (note.timeMs - SIMULTANEOUS_MS)..(note.endTimeMs + SIMULTANEOUS_MS)
            } else {
                abs(timeMs - note.timeMs) < SIMULTANEOUS_MS
            }
            if (overlaps && abs(note.x - x) < MIN_LANDING_GAP) return true
        }
        return false
    }

    private fun hashedX(index: Int, salt: Int, seed: Int): Float {
        var h = index * -0x3361D2AF xor (salt * 0x27D4EB2F) xor (seed * 0x9E3779B1.toInt())
        h = h xor (h ushr 15)
        h *= -0x7A143595
        h = h xor (h ushr 13)
        val unit = ((h ushr 8) and 0xFFFF) / 65535f
        // Kept off the walls so an object is never half outside the field.
        return 0.08f + unit * 0.84f
    }

    /**
     * Where an object's path begins, in unfolded space.
     *
     * Most objects cross the field on a clean diagonal. Some are started beyond
     * a side wall, which folding turns into an approach that comes in off it --
     * the field is not a set of parallel lanes, and a chart where every object
     * travelled the same way would say otherwise.
     *
     * Green and chain objects never carom. A green is judged up at the tap
     * points, too short a drop for a bounce to be anything but a twitch; a chain
     * link is one bead on a joined path, and a link that caroms while its
     * neighbours do not would kink the line between them.
     *
     * Hashed from the object's index rather than drawn at runtime, so a chart
     * plays the same way every time.
     */
    private fun spawnXFor(x: Float, index: Int, type: NoteType, seed: Int): Float {
        val unit = unitFor(index, SPAWN_SALT, seed)
        val plain = plainSpawnX(x, unit)

        if (type == NoteType.GREEN || type == NoteType.CHAIN) return plain
        if (unitFor(index, BOUNCE_SALT, seed) >= BOUNCE_SHARE) return plain

        // Started past the wall on the side opposite the landing, so the fold
        // brings it back across the field with exactly one carom in it.
        val offset = 0.06f + unit * 0.42f
        return if (x < 0.5f) 2f - offset else -offset
    }

    /**
     * Where a long object's path begins.
     *
     * One that caroms is spawned like anything else and pays out its length off
     * the wall. One that does not is put hard against a wall instead, so it can
     * begin stretching from there as though it had already met one -- a long
     * object is a streak coming off a wall, and one that simply appeared at full
     * length in open field would not read as the same object.
     */
    private fun longSpawnXFor(x: Float, index: Int, seed: Int): Float {
        val unit = unitFor(index, SPAWN_SALT, seed)
        if (unitFor(index, BOUNCE_SALT, seed) < BOUNCE_SHARE) {
            val offset = 0.06f + unit * 0.42f
            return if (x < 0.5f) 2f - offset else -offset
        }
        // Against the far wall, on the side opposite where it lands, so the path
        // across cannot meet a wall of its own.
        return if (x < 0.5f) 1f - WALL_MARGIN else WALL_MARGIN
    }

    private fun plainSpawnX(x: Float, unit: Float): Float {
        val farHalfStart = if (x < 0.5f) 0.55f else 0.05f
        return (farHalfStart + unit * 0.40f).coerceIn(0.04f, 0.96f)
    }

    /** A stable 0..1 value per object, purpose, and run seed. */
    private fun unitFor(index: Int, salt: Int, seed: Int): Float {
        var h = index * -0x61C88647 xor salt xor (seed * -0x3361D2AF)
        h = h xor (h ushr 16)
        h *= -0x7A143595
        h = h xor (h ushr 13)
        return ((h ushr 8) and 0xFFFF) / 65535f
    }

    /** How close to the wall a non-bouncing long object is spawned. */
    private const val WALL_MARGIN = 0.04f

    private const val SPAWN_SALT = 0
    private const val BOUNCE_SALT = 0x51ED2701

    private fun msPerMeasure(bpm: Double) = 60_000.0 / bpm * BEATS_PER_MEASURE

    private fun Map<String, String>.requireDouble(key: String, line: Int): Double {
        val raw = this[key] ?: throw ChartParseException(line, "missing required header \"$key\"")
        return raw.toDoubleOrNull() ?: throw ChartParseException(line, "\"$key=$raw\" is not a number")
    }
}
