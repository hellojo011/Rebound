package dev.rebound.core.play

import dev.rebound.core.FieldGeometry
import dev.rebound.core.chart.Chart
import dev.rebound.core.chart.Note
import dev.rebound.core.chart.NoteType
import kotlin.math.abs
import kotlin.math.hypot

sealed interface PlayEvent {
    /**
     * @param deltaMs negative when the player was early, positive when late.
     * @param isRelease true for the second judgment of a long object.
     */
    data class Judged(
        val note: Note,
        val judgment: Judgment,
        val deltaMs: Double,
        val isRelease: Boolean,
    ) : PlayEvent

    data class LongStarted(val note: Note) : PlayEvent

    data class LongBroken(val note: Note) : PlayEvent

    /**
     * A gold object was struck and is on its way back to the opponent.
     *
     * @param targetIndex the far side's object this shot *is*, or -1 when the
     *   gold has nothing to become and the shot simply leaves the field.
     */
    data class Reflected(
        val note: Note,
        val shot: ReflecShot,
        val targetIndex: Int,
    ) : PlayEvent

    /**
     * A gold was let through, so the object it would have become never happens.
     *
     * The far side has to be told: the object is on its chart but must not be
     * drawn, pressed or missed there.
     */
    data class RallyLost(val note: Note, val targetIndex: Int) : PlayEvent

    /** A flick landed on a gold object while a gauge segment was available. */
    data class JustReflec(val note: Note, val shot: ReflecShot) : PlayEvent
}

/** A note the renderer should draw, plus the bit of state that changes its look. */
data class VisibleNote(val note: Note, val isHeld: Boolean)

data class PressResult(
    val note: Note,
    val judgment: Judgment,
    val deltaMs: Double,
    /** Non-null when this press put a gold object back in play. */
    val shot: ReflecShot?,
)

/**
 * Owns all gameplay state for one chart run.
 *
 * Everything is driven by an explicit song time in milliseconds rather than
 * wall-clock or frame deltas, which is what lets the whole engine be unit tested
 * and, on device, makes the audio stream the clock instead of the render loop.
 *
 * Objects are claimed by *position along the bar*, not by lane. A press picks
 * the nearest unclaimed object within [hitRadiusX], so two fingers landing on
 * two simultaneous objects each take the one they are actually over.
 */
class PlayEngine(
    val chart: Chart,
    val windows: JudgeWindows = JudgeWindows(),
) {
    /** How far along the bar a touch may be from an object and still claim it. */
    var hitRadiusX: Float = 0.13f

    /**
     * How far up or down the field a touch may be from an object.
     *
     * Deliberately loose. Its job is only to separate the bar from the row of
     * tap points, so that a press aimed at a green object cannot be stolen by an
     * ordinary object directly below it -- not to demand vertical precision from
     * a thumb.
     */
    var hitRadiusY: Float = 0.20f

    /**
     * How long a reflected shot stays in the air when it has nothing to be
     * connected to -- the far side has no object left for it to become.
     */
    var looseFlightMs: Double = 700.0

    /** How long after a press a flick still counts as reflecting that object. */
    var flickWindowMs: Double = 220.0

    /**
     * How often a held long object pays out a [Judgment.KEEP].
     *
     * Derived from the chart's tempo rather than fixed in milliseconds: a hold
     * is worth what it is worth musically, so the same written hold pays the
     * same at any BPM.
     */
    var keepIntervalMs: Double = 60_000.0 / chart.meta.bpm / KEEP_SUBDIVISION

    private enum class State {
        /**
         * Waiting on a gold that has not been struck yet.
         *
         * Not on the field in any sense: not drawn, not pressable, and never
         * missed. It becomes [PENDING] the moment the gold is struck for it, or
         * goes straight to [RESOLVED] if that never happens.
         */
        DORMANT,
        PENDING,
        HOLDING,
        RESOLVED,
    }

    private class LiveNote(val note: Note) {
        var state: State = if (note.isRallyTarget) State.DORMANT else State.PENDING
        var pressedAtMs: Double = Double.NaN
        var shot: ReflecShot? = null
        var reflected: Boolean = false

        /** When this hold next pays out, while it is being held. */
        var nextKeepMs: Double = Double.NaN
    }

    private val live: List<LiveNote> =
        chart.notes.sortedBy { it.timeMs }.map { LiveNote(it) }

    private val byIndex: Map<Int, LiveNote> = live.associateBy { it.note.index }

    /** Index of the earliest object that is not yet fully resolved. */
    private var head = 0

    private val events = ArrayDeque<PlayEvent>()
    private val shots = mutableListOf<ReflecShot>()
    private var nextShotId = 0

    val score = ScoreState(chart.maxCombo)
    val gauge = JustReflecGauge()

    /**
     * Objects that never happened, because the gold that would have sent them
     * was let through.
     *
     * Counted rather than scored: they are not misses, and the player is not
     * charged for them. The tally exists so the run can still know when it is
     * over, which it measures against the chart's full complement.
     */
    var forfeitedCount: Int = 0
        private set

    fun shots(): List<ReflecShot> = shots

    /**
     * Advances time: retires objects the player has definitively missed, finishes
     * long objects that were sustained past their tail, and moves shots along.
     *
     * Must be called every frame, after feeding in that frame's input.
     */
    fun update(songTimeMs: Double) {
        var i = head
        while (i < live.size) {
            val n = live[i]
            // Everything from here on is still inside its press window -- or, for
            // a dormant object, still inside the stretch where its gold could
            // yet be struck for it.
            val waiting = n.state == State.PENDING || n.state == State.DORMANT
            if (waiting && songTimeMs <= n.note.timeMs + windows.hitMs) break

            when (n.state) {
                // Its gold was never struck, so the object never arrived. Nothing
                // to miss -- but it is retired here so it cannot hold up the head
                // for the rest of the run.
                State.DORMANT -> forfeit(n)

                State.PENDING -> {
                    n.state = State.RESOLVED
                    judge(n.note, Judgment.MISS, windows.hitMs, isRelease = false)
                    if (n.note.isLong) {
                        // Never pressed, so the release is forfeit too.
                        judge(n.note, Judgment.MISS, windows.hitMs, isRelease = true)
                    }
                    // A gold let through sends nothing, so the object waiting on
                    // it on the far side never arrives.
                    if (n.note.rallyTargetIndex >= 0) {
                        events += PlayEvent.RallyLost(n.note, n.note.rallyTargetIndex)
                    }
                }

                State.HOLDING -> {
                    awardKeeps(n, songTimeMs)
                    if (songTimeMs >= n.note.endTimeMs + windows.hitMs) {
                        // Sustained all the way through: that is correct play.
                        n.state = State.RESOLVED
                        judge(n.note, Judgment.JUST, 0.0, isRelease = true)
                    }
                }

                State.RESOLVED -> Unit
            }
            i++
        }
        while (head < live.size && live[head].state == State.RESOLVED) head++

        updateShots(songTimeMs)
    }

    /** Convenience for a press on the bar, where every object but green is judged. */
    fun press(x: Float, songTimeMs: Double): PressResult? =
        press(x, FieldGeometry.BAR_Y, songTimeMs)

    /**
     * A finger went down at ([x], [y]) in field space.
     *
     * @return the object claimed, or null if none was in reach -- an early or
     *   stray stab deliberately consumes nothing, so the player can try again.
     */
    fun press(x: Float, y: Float, songTimeMs: Double): PressResult? {
        var best: LiveNote? = null
        var bestDistance = Float.MAX_VALUE
        var bestDelta = 0.0

        var i = head
        while (i < live.size) {
            val n = live[i]
            i++
            if (n.state != State.PENDING) continue

            val delta = songTimeMs - n.note.timeMs
            // Time-sorted, so once an object is too far ahead so is everything after it.
            if (delta < -windows.hitMs) break
            if (delta > windows.hitMs) continue

            // Each axis is scaled by its own tolerance before being combined, so
            // the two are weighed on equal terms despite being very different
            // distances on screen.
            val dx = (n.note.x - x) / hitRadiusX
            val dy = (n.note.y - y) / hitRadiusY
            val distance = hypot(dx, dy)
            if (distance > 1f) continue

            // Nearest wins; timing only breaks ties. Two fingers on two
            // simultaneous objects must not fight over the same one.
            if (distance < bestDistance ||
                (distance == bestDistance && abs(delta) < abs(bestDelta))
            ) {
                best = n
                bestDistance = distance
                bestDelta = delta
            }
        }

        val target = best ?: return null
        val judgment = windows.judge(bestDelta)
        target.pressedAtMs = songTimeMs

        if (target.note.isLong) {
            target.state = State.HOLDING
            target.nextKeepMs = songTimeMs + keepIntervalMs
            events += PlayEvent.LongStarted(target.note)
        } else {
            target.state = State.RESOLVED
        }

        judge(target.note, judgment, bestDelta, isRelease = false)

        // A gold object always goes back over, powered or not.
        var shot: ReflecShot? = null
        if (target.note.type == NoteType.GOLD) {
            shot = launchShot(target, songTimeMs)
        }

        return PressResult(target.note, judgment, bestDelta, shot)
    }

    /**
     * The finger that claimed [noteIndex] was flicked in direction ([dirX], [dirY]),
     * in field space where +y points down toward the player.
     *
     * Spends a gauge segment to send the object over as a JUST REFLEC. With an
     * empty gauge nothing is deducted and the shot carries on unpowered -- a
     * failed reflec is free, and the object still counts as struck.
     *
     * @return the upgraded shot, or null if this was not a reflectable object.
     */
    fun flick(noteIndex: Int, dirX: Float, dirY: Float, songTimeMs: Double): ReflecShot? {
        val n = byIndex[noteIndex] ?: return null
        if (n.note.type != NoteType.GOLD) return null
        if (n.reflected) return null
        if (n.pressedAtMs.isNaN() || songTimeMs - n.pressedAtMs > flickWindowMs) return null

        val shot = n.shot ?: return null
        n.reflected = true

        if (!gauge.consumeSegment()) return null

        // Normalised first: the caller reports a direction scaled by the screen,
        // so its magnitude is tiny and only the ratio between the axes means
        // anything. Comparing the raw values against thresholds never fired.
        val length = hypot(dirX, dirY)
        shot.powered = true
        if (length < 1e-4f) shot.swing(0f, 0f) else shot.swing(dirX / length, dirY / length)
        score.addBonus(ScoreState.JUST_REFLEC_BONUS)
        events += PlayEvent.JustReflec(n.note, shot)
        return shot
    }

    /**
     * Brings a dormant object into play, its gold having been struck for it.
     *
     * Called on the side that *receives* the rally, by whatever is holding the
     * two sides together -- an engine only ever sees its own presses.
     *
     * @return the object, or null if it was not waiting on anything.
     */
    fun activateRally(noteIndex: Int): Note? {
        val n = byIndex[noteIndex] ?: return null
        if (n.state != State.DORMANT) return null
        n.state = State.PENDING
        return n.note
    }

    /** Retires a dormant object whose gold was let through. */
    fun forfeitRally(noteIndex: Int) {
        val n = byIndex[noteIndex] ?: return
        if (n.state != State.DORMANT) return
        forfeit(n)
    }

    /**
     * Launches a struck gold's shot, aimed at the object it becomes.
     *
     * The pairing comes from the chart, so the flight time follows from it too:
     * the shot has to be in the air for exactly the gap between the two, which
     * is why its speed is not a constant. Both sides play the same chart
     * mirrored, so the far side's judgment point is its own flip in the
     * striker's space -- including how far up the field it stops, since an
     * object judged over a tap point must not be flown all the way to the bar.
     */
    private fun launchShot(source: LiveNote, songTimeMs: Double): ReflecShot {
        val target = chart.noteAt(source.note.rallyTargetIndex)
        val shot = ReflecShot(
            id = nextShotId++,
            startX = source.note.x,
            startMs = songTimeMs,
            landingX = target?.let { 1f - it.x } ?: (1f - source.note.x),
            endMs = target?.timeMs ?: (songTimeMs + looseFlightMs),
        )
        if (target != null) shot.aimAt(1f - target.x, target.timeMs, 1f - target.y)

        shots += shot
        source.shot = shot
        events += PlayEvent.Reflected(source.note, shot, target?.index ?: -1)
        return shot
    }

    private fun forfeit(n: LiveNote) {
        n.state = State.RESOLVED
        forfeitedCount += n.note.judgmentCount
    }

    /** The finger holding [noteIndex] came up. Only meaningful for long objects. */
    fun release(noteIndex: Int, songTimeMs: Double): Judgment? {
        val n = byIndex[noteIndex] ?: return null
        if (n.state != State.HOLDING) return null

        val delta = songTimeMs - n.note.endTimeMs
        n.state = State.RESOLVED

        // Letting go well before the tail is a broken hold, not a late release.
        if (delta < -windows.hitMs) {
            events += PlayEvent.LongBroken(n.note)
            judge(n.note, Judgment.MISS, delta, isRelease = true)
            return Judgment.MISS
        }

        val judgment = windows.judge(delta)
        judge(n.note, judgment, delta, isRelease = true)
        return judgment
    }

    /** Objects to draw: everything approaching within [lookAheadMs], plus live holds. */
    fun visibleNotes(songTimeMs: Double, lookAheadMs: Double): List<VisibleNote> {
        val result = mutableListOf<VisibleNote>()
        var i = head
        while (i < live.size) {
            val n = live[i]
            if (n.note.timeMs > songTimeMs + lookAheadMs) break
            // A dormant object is not on the field at all until its gold is
            // struck: drawing it and then taking it away is what made a rally
            // look like a glitch rather than like one object arriving.
            if (n.state != State.RESOLVED && n.state != State.DORMANT) {
                result += VisibleNote(n.note, isHeld = n.state == State.HOLDING)
            }
            i++
        }
        return result
    }

    /** Drains queued events. The caller is expected to do this once per frame. */
    fun drainEvents(): List<PlayEvent> {
        if (events.isEmpty()) return emptyList()
        val out = events.toList()
        events.clear()
        return out
    }

    /**
     * True once every object has been accounted for and the last one has
     * finished sounding.
     *
     * Objects that never arrived count towards that as much as judged ones do --
     * otherwise letting a single gold through would leave the run waiting
     * forever on an object that was never going to come.
     */
    fun isFinished(songTimeMs: Double): Boolean =
        score.judgedCount + forfeitedCount >= chart.maxCombo && songTimeMs > chart.durationMs

    /**
     * Pays out sustain ticks for a hold that is still down.
     *
     * Ticks stop at the tail rather than running until the finger lifts: holding
     * past the end of an object should not keep earning, and the release verdict
     * already covers the finish.
     */
    private fun awardKeeps(n: LiveNote, songTimeMs: Double) {
        if (n.nextKeepMs.isNaN()) return
        while (n.nextKeepMs <= songTimeMs && n.nextKeepMs <= n.note.endTimeMs) {
            judge(n.note, Judgment.KEEP, 0.0, isRelease = false)
            n.nextKeepMs += keepIntervalMs
        }
    }

    private fun updateShots(songTimeMs: Double) {
        if (shots.isEmpty()) return
        // A shot has no state to advance -- its position is a function of the
        // clock -- so this only retires the ones that have landed.
        shots.removeAll { it.hasArrived(songTimeMs) }
    }

    private fun judge(note: Note, judgment: Judgment, deltaMs: Double, isRelease: Boolean) {
        score.apply(judgment)
        gauge.onJudgment(judgment)
        events += PlayEvent.Judged(note, judgment, deltaMs, isRelease)
    }

    private companion object {
        /**
         * Sustain ticks per beat. Two makes a KEEP an eighth note, which is what
         * ties the payout to the music rather than to the wall clock -- the same
         * hold is worth the same number of ticks at any tempo it is written at.
         */
        const val KEEP_SUBDIVISION = 2.0
    }
}
