package dev.rebound.game

import android.opengl.GLSurfaceView
import dev.rebound.audio.AudioClock
import dev.rebound.core.chart.Chart
import dev.rebound.core.chart.Note
import dev.rebound.core.chart.NoteType
import dev.rebound.core.play.CpuPlayer
import dev.rebound.core.play.Judgment
import dev.rebound.core.play.PlayEngine
import dev.rebound.core.play.PlayEvent
import dev.rebound.core.play.ReflecShot
import dev.rebound.core.play.ScoreState
import dev.rebound.core.play.VisibleNote
import dev.rebound.render.Palette
import dev.rebound.render.Playfield
import dev.rebound.render.ShapeRenderer
import dev.rebound.render.Skin
import dev.rebound.render.Skins
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.min

/**
 * Draws the playfield and drives one run of a chart.
 *
 * A match always has two sides. Both play the same chart, mirrored: the far side
 * is the near side rotated half a turn, which is what the person sitting
 * opposite actually sees. What differs between one-player and two-player is only
 * who moves the far side -- a [CpuPlayer] or a second pair of hands.
 *
 * Everything gameplay-related happens on the GL thread: input is queued from the
 * UI thread and drained here, so the engines are only ever touched by one thread
 * and need no locking.
 */
class GameRenderer(
    private val chart: Chart,
    private val clock: AudioClock,
    private val onHitSound: () -> Unit,
    private val onHud: (HudState) -> Unit,
    private val opponentControl: OpponentControl = OpponentControl.CPU,
    cpuSkill: CpuPlayer.Skill = CpuPlayer.Skill.NORMAL,
    private val skin: Skin = Skins.CLASSIC,
    /** Player preference: how large objects are drawn, 1 being the default. */
    private val objectScale: Float = 1f,
    /** Player preference: whether tap points announce an approaching green. */
    private val topAlert: Boolean = true,
) : GLSurfaceView.Renderer {

    /** How long an object is on screen before it reaches the bar. Lower is faster. */
    var approachMs: Double = BASE_APPROACH_MS

    /** Plays the near side perfectly by itself. Useful for checking timing and art. */
    @Volatile
    var autoPlay: Boolean = false

    /**
     * Set while the pause menu is up.
     *
     * The song clock is already frozen by then, so gameplay would sit still
     * regardless; what this stops is input, which would otherwise let a player
     * pick off objects that are parked motionless on the bar.
     */
    @Volatile
    var paused: Boolean = false

    /** An object of this side's that is arriving as the far side's reflected shot. */
    private class Arrival(val shot: ReflecShot, val from: MatchSide, val note: Note)

    /** One side of the match: its engine, and the feedback drawn for it. */
    private class Side(val id: MatchSide, val engine: PlayEngine) {
        val flashes = ArrayDeque<Flash>()
        var lastJudgment: Judgment? = null
        var lastDeltaMs = 0.0
        var lastJudgmentAtNanos = 0L
        var lastReflecAtNanos = 0L

        /**
         * Verdicts still being shown, each over the object that earned it.
         *
         * Several at once, because objects arrive together: in a game with no
         * lanes a single verdict cannot say which of a chord it was for, and one
         * that vanished as the next object landed would say even less.
         */
        val popups = ArrayDeque<Popup>()

        /** This side's objects that are on their way in as a rally, by index. */
        val arriving = HashMap<Int, Arrival>()
    }

    private class Flash(val x: Float, val y: Float, val color: Int, val startNanos: Long)

    /** A verdict and the field position of the object it was given for. */
    private class Popup(
        val noteIndex: Int,
        val judgment: Judgment,
        val deltaMs: Double,
        val atNanos: Long,
        val fieldX: Float,
        val fieldY: Float,
    )

    private val player = Side(MatchSide.PLAYER, PlayEngine(chart))
    private val opponent = Side(MatchSide.OPPONENT, PlayEngine(chart))
    private val sides = listOf(player, opponent)

    /** For finding the link before a chain object, which may already be gone. */
    private val notesByIndex: Map<Int, Note> = chart.notes.associateBy { it.index }

    /**
     * Every link of a run, by the index of the link that heads it.
     *
     * When a rally lands on a chain's head the whole run comes in behind it, and
     * the links further back sit beyond the ordinary look-ahead -- so they have
     * to be found from the head rather than waited for.
     */
    private val chainFollowers: Map<Int, List<Note>> = buildMap<Int, MutableList<Note>> {
        val headOf = HashMap<Int, Int>()
        chart.notes.sortedBy { it.timeMs }.forEach { note ->
            if (note.chainPrevIndex < 0) return@forEach
            val head = headOf[note.chainPrevIndex] ?: note.chainPrevIndex
            headOf[note.index] = head
            getOrPut(head) { mutableListOf() }.add(note)
        }
    }

    private val cpu: CpuPlayer? =
        if (opponentControl == OpponentControl.CPU) {
            CpuPlayer(opponent.engine, cpuSkill)
        } else {
            null
        }

    private val shapes = ShapeRenderer()
    private val inputQueue = ConcurrentLinkedQueue<TouchAction>()

    /** Written on the GL thread, read from the UI thread when a touch arrives. */
    @Volatile
    private var field: Playfield? = null

    /** Which object each finger claimed, and on whose side. */
    private val claimedByPointer = HashMap<Int, Pair<MatchSide, Int>>()

    fun postTouch(action: TouchAction) {
        inputQueue.add(action)
    }

    /** Screen geometry, for translating touches into field coordinates. */
    fun playfield(): Playfield? = field

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        shapes.init()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        shapes.resize(width, height)
        field = Playfield(width.toFloat(), height.toFloat(), objectScale)
    }

    override fun onDrawFrame(gl: GL10?) {
        val field = this.field ?: return
        val songMs = clock.songTimeMs()

        if (paused) {
            // Touches that arrived on the way into the menu are discarded rather
            // than replayed on resume.
            inputQueue.clear()
            draw(field, songMs)
            publishHud(songMs)
            return
        }

        if (autoPlay) runAutoPlay(songMs) else drainInput()
        cpu?.update(songMs)

        // Input is applied before the clock advances, so a press that landed just
        // inside its window is not pre-empted by update() calling it a miss.
        sides.forEach { it.engine.update(songMs) }
        sides.forEach { retireArrivals(it, songMs) }
        sides.forEach { consumeEvents(it, songMs) }

        draw(field, songMs)
        publishHud(songMs)
    }

    // --- input ------------------------------------------------------------

    private fun sideOf(id: MatchSide): Side = if (id == MatchSide.PLAYER) player else opponent

    private fun otherSide(side: Side): Side = if (side === player) opponent else player

    /**
     * Brings a struck gold's rally object to life on the far side.
     *
     * The pairing is fixed in the chart and the striker's engine has already
     * aimed the shot, so all that is left is to wake the object on the receiving
     * side -- it has been dormant, drawn nowhere and judged not at all, until
     * this. Striking gold still creates nothing: it turns an object that was
     * waiting into one that is arriving. When the gold is let through instead
     * the object is forfeited, in [loseRally], and never appears.
     */
    private fun bindRally(from: Side, shot: ReflecShot, targetIndex: Int) {
        if (targetIndex < 0) return
        val to = otherSide(from)
        val target = to.engine.activateRally(targetIndex) ?: return
        to.arriving[target.index] = Arrival(shot, from.id, target)
    }

    /** The gold was let through, so its object never arrives on the far side. */
    private fun loseRally(from: Side, targetIndex: Int) {
        if (targetIndex < 0) return
        otherSide(from).engine.forfeitRally(targetIndex)
    }

    /** Drops rally links whose shot has landed; the object then draws normally. */
    private fun retireArrivals(side: Side, songMs: Double) {
        val landed = side.arriving.filterValues { it.shot.hasArrived(songMs) }
        landed.forEach { (noteIndex, _) -> side.arriving.remove(noteIndex) }
    }

    private fun drainInput() {
        while (true) {
            when (val action = inputQueue.poll() ?: break) {
                is TouchAction.Down -> {
                    val side = sideOf(action.side)
                    val result = side.engine.press(action.x, action.y, action.songTimeMs)
                    if (result != null) {
                        claimedByPointer[action.pointerId] = action.side to result.note.index
                        onHitSound()
                        flash(side, result.note, result.judgment)
                    }
                }

                is TouchAction.Flick -> {
                    val claim = claimedByPointer[action.pointerId] ?: continue
                    sideOf(claim.first).engine
                        .flick(claim.second, action.dirX, action.dirY, action.songTimeMs)
                }

                is TouchAction.Up -> {
                    val claim = claimedByPointer.remove(action.pointerId) ?: continue
                    sideOf(claim.first).engine.release(claim.second, action.songTimeMs)
                }
            }
        }
    }

    private fun runAutoPlay(songMs: Double) {
        inputQueue.clear()
        claimedByPointer.clear()
        for (visible in player.engine.visibleNotes(songMs, 0.0)) {
            val note = visible.note
            if (!visible.isHeld && note.timeMs <= songMs) {
                // If the frame took a long time -- a stall, or coming back from
                // the background -- objects that fell out of their window are
                // genuinely lost. Striking them at their nominal time would hand
                // out a perfect run for a stretch nobody played.
                if (songMs - note.timeMs > player.engine.windows.hitMs) continue
                val result = player.engine.press(note.x, note.y, note.timeMs) ?: continue
                onHitSound()
                flash(player, note, result.judgment)
                if (note.type == NoteType.GOLD) {
                    // Alternate the flick so the wall bounces are on show.
                    val dirX = if (note.index % 2 == 0) 0.55f else -0.55f
                    player.engine.flick(note.index, dirX, -1f, note.timeMs)
                }
            } else if (visible.isHeld && note.endTimeMs <= songMs) {
                player.engine.release(note.index, note.endTimeMs)
            }
        }
    }

    private fun consumeEvents(side: Side, songMs: Double) {
        for (event in side.engine.drainEvents()) {
            when (event) {
                // Bound here rather than next to the press, because a gold can be
                // struck by a finger, by autoplay or by the CPU, and only the CPU
                // never passes back through this renderer. The engine raises this
                // whoever struck it, so one place covers all three.
                is PlayEvent.Reflected -> bindRally(side, event.shot, event.targetIndex)

                // The gold was missed, so the object it would have sent is
                // dropped from the far side rather than left waiting.
                is PlayEvent.RallyLost -> loseRally(side, event.targetIndex)

                is PlayEvent.Judged -> {
                    val now = System.nanoTime()
                    side.lastJudgment = event.judgment
                    side.lastDeltaMs = event.deltaMs
                    side.lastJudgmentAtNanos = now

                    // A sustain tick replaces the previous tick *from the same
                    // hold* rather than stacking: that hold pays out several
                    // times a second and each tick says the same thing. Keyed by
                    // object, because two holds can be kept at once and clearing
                    // every KEEP would leave them trading one popup between them.
                    if (event.judgment == Judgment.KEEP) {
                        side.popups.removeAll {
                            it.judgment == Judgment.KEEP && it.noteIndex == event.note.index
                        }
                    }
                    side.popups.addLast(
                        Popup(
                            event.note.index, event.judgment, event.deltaMs, now,
                            event.note.x, event.note.y,
                        ),
                    )
                    while (side.popups.size > MAX_POPUPS) side.popups.removeFirst()

                    if (event.judgment == Judgment.MISS) flash(side, event.note, Judgment.MISS)
                }

                is PlayEvent.JustReflec -> side.lastReflecAtNanos = System.nanoTime()

                else -> Unit
            }
        }
    }

    private fun flash(side: Side, note: Note, judgment: Judgment) {
        side.flashes.addLast(
            Flash(note.x, note.y, Palette.judgment(judgment), System.nanoTime()),
        )
        while (side.flashes.size > MAX_FLASHES) side.flashes.removeFirst()
    }

    // --- drawing ----------------------------------------------------------

    /** Field x, flipped for the far side, in pixels. */
    private fun Playfield.sx(side: Side, x: Float): Float = px(side.id.map(x))

    /** Field y, flipped for the far side, in pixels. */
    private fun Playfield.sy(side: Side, t: Float): Float = py(side.id.map(t))

    private fun draw(field: Playfield, songMs: Double) {
        shapes.clear(skin.background)
        shapes.beginFrame()

        drawFieldFrame(field)
        drawTapPoints(field, songMs)
        drawGauges(field)
        drawBars(field)
        sides.forEach { drawObjects(field, it, songMs) }
        sides.forEach { drawFlashes(field, it) }

        shapes.endFrame()
    }

    private fun drawFieldFrame(field: Playfield) {
        shapes.rect(field.width / 2f, field.height / 2f, field.width, field.height, skin.field)
        // Lit rails down the sides, which is also where reflected shots bounce.
        val railWidth = field.width * 0.012f
        shapes.rect(railWidth / 2f, field.height / 2f, railWidth, field.height, skin.fieldEdge, 0.9f)
        shapes.rect(
            field.width - railWidth / 2f, field.height / 2f,
            railWidth, field.height, skin.fieldEdge, 0.9f,
        )
    }

    private fun drawBars(field: Playfield) {
        drawBar(field, field.opponentBarY, skin.opponentBar, skin.opponentBarGlow)
        drawBar(field, field.playerBarY, skin.playerBar, skin.playerBarGlow)
    }

    /** Two parallel lines with a soft glow, as in the original. */
    private fun drawBar(field: Playfield, y: Float, color: Int, glow: Int) {
        val separation = field.height * 0.012f
        shapes.rect(field.width / 2f, y, field.width, separation * 2.6f, glow, 0.55f)
        shapes.rect(field.width / 2f, y - separation, field.width, 3.5f, color)
        shapes.rect(field.width / 2f, y + separation, field.width, 5.5f, color)
    }

    private fun drawTapPoints(field: Playfield, songMs: Double) {
        for (x in field.tapPointXs) {
            drawTapPoint(
                field, field.px(x), field.opponentTapPointY,
                skin.tapPointOpponent, pointsDown = false,
                alert = alertAt(opponent, x, songMs),
            )
            drawTapPoint(
                field, field.px(x), field.playerTapPointY,
                skin.tapPointPlayer, pointsDown = true,
                alert = alertAt(player, x, songMs),
            )
        }
    }

    /**
     * How strongly a tap point should announce an approaching green object,
     * 0 when none is coming.
     *
     * A green is judged well up the field, away from the bar where the eye waits,
     * so the point it will arrive at says so in advance. It builds as the object
     * closes, which is what makes it a warning rather than a decoration.
     */
    private fun alertAt(side: Side, tapX: Float, songMs: Double): Float {
        if (!topAlert) return 0f
        var best = 0f
        for (visible in side.engine.visibleNotes(songMs, TOP_ALERT_LEAD_MS)) {
            val note = visible.note
            if (!note.isTapPoint || abs(note.x - tapX) > 0.01f) continue
            // A hold that is already being kept has arrived; it needs no warning.
            if (visible.isHeld) continue
            val remaining = note.timeMs - songMs
            if (remaining < 0.0 || remaining > TOP_ALERT_LEAD_MS) continue
            val closeness = (1.0 - remaining / TOP_ALERT_LEAD_MS).toFloat()
            if (closeness > best) best = closeness
        }
        return best
    }

    private fun drawTapPoint(
        field: Playfield,
        cx: Float,
        cy: Float,
        accent: Int,
        pointsDown: Boolean,
        alert: Float,
    ) {
        val size = field.tapPointSize
        shapes.circle(cx, cy, size, skin.field, 0.85f)

        // A halo closing in on the point, brightest just as the object lands.
        if (alert > 0f) {
            shapes.circle(cx, cy, size * (1.15f + 0.85f * (1f - alert)), skin.objectGreen, alert * 0.20f)
            shapes.ring(
                cx, cy,
                size * (1.05f + 0.75f * (1f - alert)),
                size * 0.06f,
                skin.objectGreen,
                alert * 0.85f,
            )
        }

        shapes.ring(cx, cy, size, size * 0.07f, skin.tapPointRing, 0.9f)

        // A triangle pointing toward the owner's bar, drawn as three strokes.
        val half = size * 0.26f
        val tipY = if (pointsDown) cy + half else cy - half
        val baseY = if (pointsDown) cy - half * 0.75f else cy + half * 0.75f
        val stroke = size * 0.055f
        shapes.line(cx - half, baseY, cx + half, baseY, stroke, accent, 0.95f)
        shapes.line(cx - half, baseY, cx, tipY, stroke, accent, 0.95f)
        shapes.line(cx + half, baseY, cx, tipY, stroke, accent, 0.95f)
    }

    private fun drawGauges(field: Playfield) {
        drawJustReflecGauge(
            field, field.playerGaugeY,
            player.engine.gauge.filledSegments, player.engine.gauge.partialFraction,
        )
        drawJustReflecGauge(
            field, field.opponentGaugeY,
            opponent.engine.gauge.filledSegments, opponent.engine.gauge.partialFraction,
        )
    }

    private fun drawJustReflecGauge(field: Playfield, cy: Float, filled: Int, partial: Float) {
        val segments = player.engine.gauge.segments
        val totalWidth = field.width * 0.60f
        val gap = field.width * 0.008f
        val segmentWidth = (totalWidth - gap * (segments - 1)) / segments
        val segmentHeight = field.height * 0.0135f
        val left = (field.width - totalWidth) / 2f
        val radius = segmentHeight * 0.35f

        for (i in 0 until segments) {
            val cx = left + segmentWidth * (i + 0.5f) + gap * i
            shapes.roundRect(cx, cy, segmentWidth, segmentHeight, radius, skin.gaugeFrame, 0.75f)
            shapes.roundRect(
                cx, cy, segmentWidth - 3f, segmentHeight - 3f,
                radius, skin.gaugeEmpty,
            )

            if (i < filled) {
                shapes.roundRect(
                    cx, cy, segmentWidth - 5f, segmentHeight - 5f,
                    radius, skin.gaugeFill,
                )
            } else if (i == filled && partial > 0f) {
                val innerWidth = (segmentWidth - 5f) * partial
                shapes.roundRect(
                    cx - (segmentWidth - 5f - innerWidth) / 2f, cy,
                    innerWidth, segmentHeight - 5f,
                    radius, skin.gaugePartial,
                )
            }
        }
    }

    private fun drawObjects(field: Playfield, side: Side, songMs: Double) {
        val size = field.objectSize

        // An object riding in on a rally can be struck a long way out -- further
        // than the look-ahead the approach uses -- and for that stretch it is
        // neither in the visible list nor drawn as a loose shot. Adding it here
        // is what stops a struck gold from simply vanishing on its way over.
        // A rally can be in the air far longer than an ordinary approach, and the
        // run chained behind it reaches further still, so the look-ahead is
        // stretched to cover whatever is currently riding. Objects that are early
        // for their own approach still fade in from nothing, so widening it costs
        // no clutter.
        val furthestRide = side.arriving.values.maxOfOrNull { arrival ->
            val last = chainFollowers[arrival.note.index]?.lastOrNull() ?: arrival.note
            last.timeMs - songMs
        } ?: 0.0
        val visibleNow = side.engine.visibleNotes(songMs, maxOf(approachMs, furthestRide + 1.0))
        val alreadyListed = visibleNow.mapTo(HashSet()) { it.note.index }
        val inFlight = side.arriving.values
            .filter { it.note.index !in alreadyListed }
            .map { VisibleNote(it.note, isHeld = false) }

        // What is on the field right now. A chain link is only joined back to a
        // neighbour that is still here: once a link has been struck it is gone,
        // and a line still reaching for it would tie the run to a ghost.
        val live = HashSet<Int>()
        visibleNow.forEach { live += it.note.index }
        side.arriving.keys.forEach { live += it }

        for (visible in visibleNow + inFlight) {
            val note = visible.note

            // Every object moves at the same speed, so one judged higher up the
            // field simply has less ground to cover and less time in flight.
            val approach = approachMs * note.y
            val progress = ((songMs - (note.timeMs - approach)) / approach).toFloat()

            // A rallied object, and anything chained behind it, rides in on the
            // shot rather than on its own approach.
            val ride = rideOf(side, note)
            val position = screenPositionOf(field, side, note, songMs, ride)
                // A link behind a rallied head has not been sent yet: the shot it
                // will ride does not exist at this moment, so neither does it.
                ?: continue

            // A short fade so objects do not pop in, but not so long that they
            // spend the first part of the approach too dim to read.
            // A riding object is already on screen as the shot, so it does not
            // fade in and must not be culled for being "too early".
            val alpha = if (ride != null) 1f else min(1f, progress * 9f).coerceAtLeast(0f)
            if (alpha <= 0f) continue

            val x = position.first
            val y = position.second

            // Objects rising away from the near player are cool, objects falling
            // towards them are warm. On a shared field that is the one thing a
            // glance has to answer.
            val rising = side.id.mirrored
            val tapRing = if (rising) skin.objectTapFar else skin.objectTap
            val tapCore = if (rising) skin.objectTapFarCore else skin.objectTapCore

            // A rallied object keeps its own face. It is the object the receiving
            // side has to strike, and dressing it as the gold that sent it would
            // advertise a reflec on something that cannot be reflected at all.
            // Only the trail behind it says it arrived by rally.
            // Only a reflec that actually spent a gauge segment leaves a trail.
            // A gold returned with a plain tap, or flicked on an empty gauge, is
            // just the object crossing over -- giving it the same flourish would
            // advertise a charge that was never spent.
            val flight = side.arriving[note.index]
            if (flight != null && flight.shot.powered) {
                val from = sideOf(flight.from)
                val trail = trailColourFor(note.type, tapRing)

                // Sampled from the path itself. The shot's position is a function
                // of the clock, so asking where it was a moment ago costs nothing
                // and needs no history to be kept.
                for (step in TRAIL_SAMPLES downTo 1) {
                    val back = songMs - step * TRAIL_STEP_MS
                    if (back <= flight.shot.startMs) continue
                    val fade = 1f - step / (TRAIL_SAMPLES + 1f)
                    shapes.circle(
                        field.sx(from, flight.shot.xAt(back)),
                        field.sy(from, flight.shot.yAt(back)),
                        size * (0.16f + 0.5f * fade),
                        trail,
                        alpha * 0.4f * fade * fade,
                    )
                }

                shapes.circle(x, y, size * 2.1f, skin.shotPowered, 0.24f)
            }

            // A chain is joined back to the link before it, but only while that
            // link is still on the field. A struck link is gone, and a line left
            // reaching for where it used to be would tether the run to a ghost
            // sitting on the bar.
            if (note.chainPrevIndex >= 0 && note.chainPrevIndex in live) {
                notesByIndex[note.chainPrevIndex]?.let { previous ->
                    screenPositionOf(field, side, previous, songMs)?.let { (linkX, linkY) ->
                        shapes.line(linkX, linkY, x, y, size * 0.26f, tapRing, alpha * 0.7f)
                    }
                }
            }

            when (note.type) {
                NoteType.TAP, NoteType.CHAIN ->
                    drawObject(x, y, size, tapRing, tapCore, alpha)

                // Same treatment as gold: the ring says which way it is going,
                // the inside says what kind of object it is. Whose tap point an
                // object belongs to is otherwise impossible to read.
                NoteType.GREEN ->
                    drawObject(x, y, size, tapRing, skin.objectGreen, alpha)

                NoteType.GOLD -> {
                    // A solid gold body, the direction's colour only on the rim.
                    // Drawing it as a ring-plus-core the way the other objects are
                    // left a dark band between the gold centre and the coloured
                    // ring; filling the body outright removes it while still
                    // saying, at the edge, which way the object is going.
                    shapes.circle(x, y, size * 1.24f, tapRing, alpha * 0.22f)
                    shapes.circle(x, y, size * 0.98f, skin.objectGold, alpha)
                    shapes.ring(x, y, size, size * 0.14f, tapRing, alpha)
                    shapes.circle(x, y, size * 0.42f, skin.objectGoldCore, alpha)
                }

                NoteType.LONG ->
                    drawLong(field, side, note, songMs, approach, progress, visible.isHeld, rising, tapCore, size, alpha)
            }
        }
    }

    /**
     * A shot an object is riding in on, and how far behind the shot's own object
     * it follows.
     *
     * [delayMs] is zero for the rallied object itself and grows for each link
     * chained behind it, which is what strings a run out along the shot's path
     * instead of leaving the head on the shot and its followers on their own
     * approach -- the two only met at the bar, which read as the run snapping
     * together at the last moment.
     */
    private class Ride(val shot: ReflecShot, val from: MatchSide, val delayMs: Double)

    private fun rideOf(side: Side, note: Note): Ride? {
        side.arriving[note.index]?.let { return Ride(it.shot, it.from, 0.0) }
        if (note.chainPrevIndex < 0) return null

        // Walk to the head of the run: if that arrived as a rally, everything
        // behind it came in the same way.
        var head = note
        while (head.chainPrevIndex >= 0) {
            head = notesByIndex[head.chainPrevIndex] ?: return null
        }
        val arrival = side.arriving[head.index] ?: return null
        return Ride(arrival.shot, arrival.from, note.timeMs - head.timeMs)
    }

    /**
     * Where an object sits right now, or null if it is not on the field yet.
     *
     * A riding object is drawn in the space of the side that struck it, at the
     * point of the shot's path its own arrival time puts it at.
     */
    private fun screenPositionOf(
        field: Playfield,
        side: Side,
        note: Note,
        songMs: Double,
        ride: Ride? = rideOf(side, note),
    ): Pair<Float, Float>? {
        if (ride != null) {
            val at = songMs - ride.delayMs
            if (at < ride.shot.startMs) return null
            val from = sideOf(ride.from)
            return field.sx(from, ride.shot.xAt(at)) to field.sy(from, ride.shot.yAt(at))
        }
        val approach = approachMs * note.y
        val progress = ((songMs - (note.timeMs - approach)) / approach)
            .toFloat()
            .coerceIn(0f, 1f)
        return field.sx(side, note.approachX(progress)) to field.sy(side, note.y * progress)
    }

    /**
     * A long object, drawn stretching out of the wall it comes in on.
     *
     * It reads as one object -- a plain head -- until its path meets the side
     * wall, and only from there does the body pay out behind the head: the tail
     * sits at the bounce point and the head carries on to the bar. A long that
     * caroms mid-approach shows the crook where it turned; one that does not was
     * spawned hard against a wall and simply stretches straight off it. Either
     * way what you see is a streak coming off a wall, not a bar that dropped in
     * at full length.
     */
    private fun drawLong(
        field: Playfield,
        side: Side,
        note: Note,
        songMs: Double,
        approach: Double,
        progress: Float,
        isHeld: Boolean,
        rising: Boolean,
        core: Int,
        size: Float,
        alpha: Float,
    ) {
        val headProgress = if (isHeld) 1f else progress.coerceIn(0f, 1f)
        val tailProgress =
            ((songMs - (note.endTimeMs - approach)) / approach).toFloat().coerceIn(0f, 1f)

        // A green long is a hold up at a tap point: it wears the green skin and,
        // being short, never caroms, so it simply stretches from its tail.
        val green = note.isGreenLong
        val headRing = when {
            green -> skin.objectGreen
            rising -> skin.objectLongFar
            else -> skin.objectLong
        }
        val headCore = if (green) skin.objectGreenCore else core

        // The body only exists from the bounce onward. Before the head reaches
        // that point there is nothing to stretch, and the object is just a head
        // coming in. bounceProgress is 0 for a path that meets no wall, which
        // for a non-bouncing long means "from the wall it started on" -- the same
        // branch, since it was spawned against one.
        val bodyStart = maxOf(note.bounceProgress, tailProgress)
        val bodyColor = when {
            green -> skin.objectGreen
            rising -> skin.objectLongFarBody
            else -> skin.objectLongBody
        }
        val bodyAlpha = alpha * if (isHeld) 1f else 0.8f

        // Once past the last wall the path runs straight to the bar, and the body
        // only ever shows from that wall onward, so it is a single clean capsule
        // -- no more the string of overlapping beads that step-sampling produced.
        if (headProgress > bodyStart) {
            shapes.line(
                field.sx(side, note.approachX(bodyStart)), field.sy(side, note.y * bodyStart),
                field.sx(side, note.approachX(headProgress)), field.sy(side, note.y * headProgress),
                size * 0.62f, bodyColor, bodyAlpha,
            )
        }

        val headX = field.sx(side, note.approachX(headProgress))
        val headY = field.sy(side, note.y * headProgress)
        drawObject(headX, headY, size, headRing, headCore, alpha)
    }

    /** What a rallied object's trail is coloured by: the object, not the sender. */
    private fun trailColourFor(type: NoteType, directionRing: Int): Int = when (type) {
        NoteType.GOLD -> skin.objectGold
        NoteType.GREEN -> skin.objectGreen
        NoteType.LONG -> skin.objectLong
        NoteType.TAP, NoteType.CHAIN -> directionRing
    }

    /** A bright ring with a pale core, the shape every object shares. */
    private fun drawObject(x: Float, y: Float, size: Float, ring: Int, core: Int, alpha: Float) {
        shapes.circle(x, y, size * 1.22f, ring, alpha * 0.22f)
        shapes.ring(x, y, size, size * 0.13f, ring, alpha)
        // The core nearly meets the ring; a visible gap reads as a hole rather
        // than as a solid object.
        shapes.circle(x, y, size * 0.70f, core, alpha * 0.95f)
    }

    private fun drawFlashes(field: Playfield, side: Side) {
        val now = System.nanoTime()
        val iterator = side.flashes.iterator()
        while (iterator.hasNext()) {
            val flash = iterator.next()
            val ageSeconds = (now - flash.startNanos) / 1_000_000_000.0
            if (ageSeconds > FLASH_SECONDS) {
                iterator.remove()
                continue
            }
            val t = (ageSeconds / FLASH_SECONDS).toFloat()
            val cx = field.sx(side, flash.x)
            // Bursts land wherever the object was judged, which for a green
            // object is its tap point rather than the bar.
            val cy = field.sy(side, flash.y)
            val fade = 1f - t
            // A soft burst under a thin expanding ring. A ring on its own reads as
            // a stray outline rather than as an impact.
            shapes.circle(
                cx, cy,
                field.objectSize * (1f + t * 0.5f),
                flash.color, fade * fade * 0.40f,
            )
            shapes.ring(
                cx, cy,
                field.objectSize * (0.85f + t * 0.8f),
                field.objectSize * 0.13f * fade,
                flash.color, fade * 0.8f,
            )
        }
    }

    /**
     * A side's live verdicts, in screen fractions, oldest first.
     *
     * Expired ones are dropped here rather than on a timer, so the list the HUD
     * receives is exactly what it should draw.
     */
    private fun popupsFor(side: Side): List<JudgmentPopup> {
        val f = field ?: return emptyList()
        val now = System.nanoTime()
        side.popups.removeAll { (now - it.atNanos) / 1_000_000_000.0 > POPUP_SECONDS }
        return side.popups.map {
            JudgmentPopup(
                judgment = it.judgment,
                deltaMs = it.deltaMs,
                atNanos = it.atNanos,
                x = side.id.map(it.fieldX),
                y = f.py(side.id.map(it.fieldY)) / f.height,
            )
        }
    }

    private fun publishHud(songMs: Double) {
        val score = player.engine.score
        onHud(
            HudState(
                title = chart.meta.title,
                difficulty = chart.meta.difficulty,
                level = chart.meta.level,
                score = score.score,
                combo = score.combo,
                bestCombo = score.bestCombo,
                clearGauge = score.gauge,
                clearThreshold = ScoreState.CLEAR_THRESHOLD,
                just = score.count(Judgment.JUST),
                great = score.count(Judgment.GREAT),
                good = score.count(Judgment.GOOD),
                miss = score.count(Judgment.MISS),
                keep = score.count(Judgment.KEEP),
                maxCombo = chart.maxCombo,
                lastJudgment = player.lastJudgment,
                popups = popupsFor(player),
                opponentPopups = if (opponentControl == OpponentControl.CPU) {
                    emptyList()
                } else {
                    popupsFor(opponent)
                },
                lastDeltaMs = player.lastDeltaMs,
                lastJudgmentAtNanos = player.lastJudgmentAtNanos,
                lastReflecAtNanos = player.lastReflecAtNanos,
                opponentScore = opponent.engine.score.score,
                opponentCombo = opponent.engine.score.combo,
                opponentJust = opponent.engine.score.count(Judgment.JUST),
                opponentGreat = opponent.engine.score.count(Judgment.GREAT),
                opponentGood = opponent.engine.score.count(Judgment.GOOD),
                opponentMiss = opponent.engine.score.count(Judgment.MISS),
                opponentLastJudgment = opponent.lastJudgment,
                opponentLastDeltaMs = opponent.lastDeltaMs,
                opponentLastJudgmentAtNanos = opponent.lastJudgmentAtNanos,
                opponentIsCpu = opponentControl == OpponentControl.CPU,
                autoPlay = autoPlay,
                finished = player.engine.isFinished(songMs),
                cleared = score.isCleared,
                songTimeMs = songMs,
                leadInRemainingMs = clock.leadInRemainingMs(),
                leadInTotalMs = clock.leadInDurationMs,
                paused = paused,
            ),
        )
    }

    companion object {
        /**
         * Approach time at 1x. Objects want to be readable well before the bar:
         * the player is picking a position along a line, not just a moment, and
         * that takes longer to judge than a lane does.
         */
        const val BASE_APPROACH_MS = 1800.0

        const val FLASH_SECONDS = 0.22
        const val MAX_FLASHES = 24

        /**
         * How long a verdict stays up, and how many may share the screen.
         *
         * Roomy enough for a chord landing while two holds are still ticking, so
         * a live KEEP is never pushed out by the objects around it.
         */
        const val POPUP_SECONDS = 0.45
        const val MAX_POPUPS = 10

        /**
         * How far ahead a tap point starts announcing a green object.
         *
         * A shade longer than the default approach, so the warning is up before
         * the object itself becomes easy to pick out of the field.
         */
        const val TOP_ALERT_LEAD_MS = 2000.0

        /** Samples in a rallied object's trail, and how far apart they are. */
        const val TRAIL_SAMPLES = 7
        const val TRAIL_STEP_MS = 34.0
    }
}
