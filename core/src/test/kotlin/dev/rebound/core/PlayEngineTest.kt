package dev.rebound.core

import dev.rebound.core.chart.Chart
import dev.rebound.core.chart.ChartMeta
import dev.rebound.core.chart.Note
import dev.rebound.core.chart.NoteType
import dev.rebound.core.play.JudgeWindows
import dev.rebound.core.play.Judgment
import dev.rebound.core.play.PlayEngine
import dev.rebound.core.play.PlayEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlayEngineTest {

    private val windows = JudgeWindows(justMs = 30.0, greatMs = 65.0, goodMs = 110.0)

    private fun engineOf(vararg notes: Note) =
        PlayEngine(Chart(ChartMeta(columns = 8, bpm = 150.0), notes.toList()), windows)

    private fun tap(index: Int, x: Float, timeMs: Double) =
        Note(index, NoteType.TAP, x, 1f - x, timeMs)

    private fun gold(index: Int, x: Float, timeMs: Double) =
        Note(index, NoteType.GOLD, x, 1f - x, timeMs)

    /** A gold paired with the object it becomes when struck. */
    private fun goldTo(index: Int, x: Float, timeMs: Double, targetIndex: Int) =
        Note(index, NoteType.GOLD, x, 1f - x, timeMs, rallyTargetIndex = targetIndex)

    /** A tap that only arrives if [sourceIndex]'s gold is struck for it. */
    private fun rallyTarget(index: Int, x: Float, timeMs: Double, sourceIndex: Int) =
        Note(index, NoteType.TAP, x, 1f - x, timeMs, rallySourceIndex = sourceIndex)

    private fun long(index: Int, x: Float, startMs: Double, endMs: Double) =
        Note(index, NoteType.LONG, x, 1f - x, startMs, endMs)

    /** Plays enough perfectly timed objects to fill exactly one gauge segment. */
    private fun chargeOneSegment(): PlayEngine {
        val notes = (0 until JUSTS_PER_SEGMENT).map { tap(it, 0.5f, 100.0 * (it + 1)) }
        val engine = engineOf(*notes.toTypedArray())
        notes.forEach {
            engine.update(it.timeMs)
            engine.press(0.5f, it.timeMs)
        }
        return engine
    }

    // --- judgment ---------------------------------------------------------

    @Test
    fun `dead on press is JUST`() {
        val e = engineOf(tap(0, 0.5f, 1000.0))
        e.update(1000.0)
        assertEquals(Judgment.JUST, e.press(0.5f, 1000.0)?.judgment)
        assertEquals(1, e.score.combo)
    }

    @Test
    fun `windows widen through great and good`() {
        assertEquals(Judgment.JUST, engineOf(tap(0, 0.5f, 1000.0)).press(0.5f, 1029.0)?.judgment)
        assertEquals(Judgment.GREAT, engineOf(tap(0, 0.5f, 1000.0)).press(0.5f, 1060.0)?.judgment)
        assertEquals(Judgment.GOOD, engineOf(tap(0, 0.5f, 1000.0)).press(0.5f, 1100.0)?.judgment)
    }

    @Test
    fun `press far too early does not consume the object`() {
        val e = engineOf(tap(0, 0.5f, 1000.0))
        e.update(500.0)
        assertNull(e.press(0.5f, 500.0))
        assertEquals(0, e.score.judgedCount)
        e.update(1000.0)
        assertEquals(Judgment.JUST, e.press(0.5f, 1000.0)?.judgment)
    }

    @Test
    fun `object passing its window is missed automatically`() {
        val e = engineOf(tap(0, 0.5f, 1000.0))
        e.update(1000.0 + windows.hitMs + 1.0)
        assertEquals(1, e.score.count(Judgment.MISS))
        assertEquals(0, e.score.combo)
    }

    // --- position -----------------------------------------------------------

    @Test
    fun `press beyond the hit radius claims nothing`() {
        val e = engineOf(tap(0, 0.1f, 1000.0))
        e.update(1000.0)
        assertNull(e.press(0.9f, 1000.0))
        assertEquals(0, e.score.judgedCount)
    }

    @Test
    fun `press within the hit radius claims the object`() {
        val e = engineOf(tap(0, 0.5f, 1000.0))
        e.hitRadiusX = 0.13f
        e.update(1000.0)
        assertNotNull(e.press(0.58f, 1000.0))
    }

    @Test
    fun `two fingers on simultaneous objects each take the nearer one`() {
        val left = tap(0, 0.2f, 1000.0)
        val right = tap(1, 0.8f, 1000.0)
        val e = engineOf(left, right)
        e.update(1000.0)

        assertEquals(0, e.press(0.22f, 1000.0)?.note?.index)
        assertEquals(1, e.press(0.78f, 1000.0)?.note?.index)
        assertEquals(2, e.score.combo)
    }

    @Test
    fun `nearest along the bar wins over nearer timing`() {
        // The far object is better timed, but the near one is what the finger is on.
        val near = tap(0, 0.50f, 1000.0)
        val far = tap(1, 0.62f, 1005.0)
        val e = engineOf(near, far)
        e.hitRadiusX = 0.2f
        e.update(1005.0)
        assertEquals(0, e.press(0.50f, 1005.0)?.note?.index)
    }

    // --- green objects ------------------------------------------------------

    private fun green(index: Int, x: Float, timeMs: Double) =
        Note(index, NoteType.GREEN, x, 1f - x, timeMs, timeMs, FieldGeometry.TAP_POINT_Y)

    @Test
    fun `a green object is claimed by a press on its tap point`() {
        val e = engineOf(green(0, 0.5f, 1000.0))
        e.update(1000.0)
        assertEquals(
            Judgment.JUST,
            e.press(0.5f, FieldGeometry.TAP_POINT_Y, 1000.0)?.judgment,
        )
    }

    @Test
    fun `a press on the bar does not reach a green object`() {
        val e = engineOf(green(0, 0.5f, 1000.0))
        e.update(1000.0)
        assertNull("green objects live too far up the field", e.press(0.5f, 1000.0))
        assertEquals(0, e.score.judgedCount)
    }

    @Test
    fun `a press on a tap point does not steal the object below it`() {
        val e = engineOf(tap(0, 0.5f, 1000.0))
        e.update(1000.0)
        assertNull(e.press(0.5f, FieldGeometry.TAP_POINT_Y, 1000.0))
    }

    @Test
    fun `stacked green and bar objects are claimed independently`() {
        // Same position along the width, same instant, different rows.
        val e = engineOf(green(0, 0.5f, 1000.0), tap(1, 0.5f, 1000.0))
        e.update(1000.0)

        assertEquals(0, e.press(0.5f, FieldGeometry.TAP_POINT_Y, 1000.0)?.note?.index)
        assertEquals(1, e.press(0.5f, FieldGeometry.BAR_Y, 1000.0)?.note?.index)
        assertEquals(2, e.score.combo)
    }

    @Test
    fun `a green object missed entirely still costs a judgment`() {
        val e = engineOf(green(0, 0.5f, 1000.0))
        e.update(1000.0 + windows.hitMs + 1.0)
        assertEquals(1, e.score.count(Judgment.MISS))
    }

    @Test
    fun `green objects are not reflectable`() {
        val e = engineOf(green(0, 0.5f, 1000.0))
        repeat(JUSTS_PER_SEGMENT) { e.gauge.onJudgment(Judgment.JUST) }
        e.update(1000.0)
        val result = e.press(0.5f, FieldGeometry.TAP_POINT_Y, 1000.0)
        assertNull("nothing goes back over", result?.shot)
        assertNull(e.flick(0, 0f, -1f, 1010.0))
        assertEquals("gauge untouched", 1, e.gauge.filledSegments)
    }

    // --- gauge --------------------------------------------------------------

    @Test
    fun `only JUST charges the gauge`() {
        val e = engineOf(tap(0, 0.5f, 1000.0), tap(1, 0.5f, 2000.0))
        e.update(1000.0)
        e.press(0.5f, 1000.0) // JUST
        val afterJust = e.gauge.fillFraction

        e.update(2000.0)
        e.press(0.5f, 2060.0) // GREAT
        assertEquals(afterJust, e.gauge.fillFraction, 1e-6f)
    }

    @Test
    fun `a run of JUSTs fills exactly one segment`() {
        val e = chargeOneSegment()
        assertEquals(JUSTS_PER_SEGMENT, e.score.count(Judgment.JUST))
        assertEquals(1, e.gauge.filledSegments)
    }

    @Test
    fun `a partial segment is reported but cannot be spent`() {
        val e = engineOf(gold(0, 0.5f, 1000.0))
        // Two short of a segment: striking the gold object itself contributes the
        // next JUST, which must still leave the segment incomplete.
        repeat(JUSTS_PER_SEGMENT - 2) { e.gauge.onJudgment(Judgment.JUST) }

        e.update(1000.0)
        e.press(0.5f, 1000.0)

        assertEquals(0, e.gauge.filledSegments)
        assertTrue("partial fill should show", e.gauge.partialFraction > 0f)
        assertNull("cannot reflec on a partial segment", e.flick(0, 0f, -1f, 1010.0))
    }

    @Test
    fun `misses do not drain the gauge`() {
        val e = chargeOneSegment()
        val before = e.gauge.filledSegments
        e.update(100_000.0)
        assertEquals(before, e.gauge.filledSegments)
    }

    // --- gold objects and reflecs ------------------------------------------

    @Test
    fun `striking a gold object always sends something back`() {
        val e = engineOf(gold(0, 0.5f, 1000.0))
        e.update(1000.0)
        val result = e.press(0.5f, 1000.0)
        assertNotNull("gold objects return to the opponent", result?.shot)
        assertFalse("a plain tap is not powered", result!!.shot!!.powered)
        assertEquals(1, e.shots().size)
    }

    @Test
    fun `striking a plain object sends nothing back`() {
        val e = engineOf(tap(0, 0.5f, 1000.0))
        e.update(1000.0)
        assertNull(e.press(0.5f, 1000.0)?.shot)
        assertTrue(e.shots().isEmpty())
    }

    @Test
    fun `a shot travels from the striker's bar to the far one`() {
        val e = engineOf(gold(0, 0.5f, 1000.0))
        e.update(1000.0)
        val shot = e.press(0.5f, 1000.0)!!.shot!!

        assertFalse(shot.powered)
        assertEquals("starts at the striker's bar", 1f, shot.yAt(1000.0), 1e-6f)
        assertEquals("arrives at the far bar", 0f, shot.yAt(shot.endMs), 1e-6f)
    }

    @Test
    fun `a shot's flight fills the gap to the object it becomes`() {
        // The object it becomes is due much later and lands at 0.75, whose flip
        // in the striker's space is 0.25; the flight stretches to suit.
        val e = engineOf(goldTo(0, 0.5f, 1000.0, 1), rallyTarget(1, 0.75f, 4000.0, 0))
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        val shot = e.shots().single()

        assertEquals(4000.0, shot.endMs, 1e-9)
        assertEquals(0.25f, shot.xAt(4000.0), 1e-5f)
        assertEquals("half way across at half the time", 0.5f, shot.yAt(2500.0), 1e-5f)
    }

    @Test
    fun `a shot is retired once it lands`() {
        val e = engineOf(goldTo(0, 0.5f, 1000.0, 1), rallyTarget(1, 0.5f, 2000.0, 0))
        e.update(1000.0)
        e.press(0.5f, 1000.0)

        e.update(1999.0)
        assertEquals(1, e.shots().size)
        e.update(2001.0)
        assertTrue("landed shots stop being drawn", e.shots().isEmpty())
    }

    @Test
    fun `flicking with a charged gauge powers the shot and spends a segment`() {
        val e = engineOf(gold(0, 0.5f, 1000.0))
        repeat(JUSTS_PER_SEGMENT) { e.gauge.onJudgment(Judgment.JUST) }
        assertEquals(1, e.gauge.filledSegments)

        e.update(1000.0)
        e.press(0.5f, 1000.0)
        val shot = e.flick(0, dirX = 0.9f, dirY = -0.4f, songTimeMs = 1050.0)

        assertNotNull("flick should power the shot", shot)
        assertTrue(shot!!.powered)
        assertEquals("one segment spent", 0, e.gauge.filledSegments)
        assertTrue("swung into a wall", shot.bounces >= 1)
        assertEquals("but it still lands where it must", 0.5f, shot.xAt(2000.0), 1e-5f)
    }

    @Test
    fun `a successful reflec pays a bonus on top of the verdict`() {
        val e = engineOf(gold(0, 0.5f, 1000.0))
        repeat(JUSTS_PER_SEGMENT) { e.gauge.onJudgment(Judgment.JUST) }
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        val struck = e.score.score

        e.flick(0, 0.5f, -0.8f, 1050.0)
        assertEquals(struck + 10, e.score.score)
    }

    @Test
    fun `a failed reflec pays nothing extra`() {
        val e = engineOf(gold(0, 0.5f, 1000.0))
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        val struck = e.score.score

        e.flick(0, 0.5f, -0.8f, 1050.0)
        assertEquals(struck, e.score.score)
    }

    @Test
    fun `a gold object can only be reflected once`() {
        val e = engineOf(gold(0, 0.5f, 1000.0))
        repeat(JUSTS_PER_SEGMENT * 2) { e.gauge.onJudgment(Judgment.JUST) }
        assertEquals(2, e.gauge.filledSegments)

        e.update(1000.0)
        e.press(0.5f, 1000.0)
        assertNotNull(e.flick(0, 0f, -1f, 1010.0))
        assertNull("second flick is ignored", e.flick(0, 0f, -1f, 1020.0))
        assertEquals("only one segment spent", 1, e.gauge.filledSegments)
    }

    @Test
    fun `flicking on an empty gauge costs nothing and leaves the shot unpowered`() {
        val e = engineOf(gold(0, 0.5f, 1000.0))
        e.update(1000.0)
        val result = e.press(0.5f, 1000.0)
        assertEquals(0, e.gauge.filledSegments)

        assertNull(e.flick(0, 0.3f, -0.9f, 1050.0))
        assertFalse(result!!.shot!!.powered)
        assertEquals("nothing deducted", 0, e.gauge.filledSegments)
    }

    @Test
    fun `flicking a plain object does nothing`() {
        val e = engineOf(tap(0, 0.5f, 1000.0))
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        assertNull(e.flick(0, 0f, -1f, 1010.0))
    }

    @Test
    fun `a flick that arrives too late is ignored`() {
        val e = engineOf(gold(0, 0.5f, 1000.0))
        repeat(JUSTS_PER_SEGMENT) { e.gauge.onJudgment(Judgment.JUST) }
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        assertNull(e.flick(0, 0f, -1f, 1000.0 + e.flickWindowMs + 50.0))
        assertEquals("gauge untouched", 1, e.gauge.filledSegments)
    }

    @Test
    fun `a rally target is dormant until its gold is struck`() {
        val e = engineOf(goldTo(0, 0.5f, 1000.0, 1), rallyTarget(1, 0.3f, 2000.0, 0))
        e.update(1000.0)

        // Not on the field: it cannot be seen and so cannot be struck.
        assertTrue(
            "dormant object is not drawn",
            e.visibleNotes(1000.0, 5000.0).none { it.note.index == 1 },
        )
        assertNull("and cannot be pressed at its own time", run { e.update(2000.0); e.press(0.3f, 2000.0) })
    }

    @Test
    fun `striking the gold wakes the object it becomes`() {
        val e = engineOf(goldTo(0, 0.5f, 1000.0, 1), rallyTarget(1, 0.3f, 2000.0, 0))
        e.update(1000.0)
        e.press(0.5f, 1000.0)

        assertEquals(1, e.activateRally(1)?.index)
        assertTrue(
            "woken object is now drawn",
            e.visibleNotes(1000.0, 5000.0).any { it.note.index == 1 },
        )
    }

    @Test
    fun `letting a gold through forfeits the object it would have sent`() {
        val e = engineOf(goldTo(0, 0.5f, 1000.0, 1), rallyTarget(1, 0.3f, 2000.0, 0))

        // The gold goes by unstruck.
        e.update(1000.0 + windows.hitMs + 1.0)
        assertTrue(
            "the far side is told its object is not coming",
            e.drainEvents().any { it is PlayEvent.RallyLost && it.targetIndex == 1 },
        )

        // The forfeited object is accounted for, so the run can still end rather
        // than waiting forever on something that will never arrive.
        e.forfeitRally(1)
        e.update(10_000.0)
        assertTrue(e.isFinished(10_000.0))
    }

    @Test
    fun `reflec events are emitted`() {
        val e = engineOf(gold(0, 0.5f, 1000.0))
        repeat(JUSTS_PER_SEGMENT) { e.gauge.onJudgment(Judgment.JUST) }
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        assertTrue(e.drainEvents().any { it is PlayEvent.Reflected })
        e.flick(0, 0f, -1f, 1010.0)
        assertTrue(e.drainEvents().any { it is PlayEvent.JustReflec })
    }

    // --- long objects -------------------------------------------------------

    @Test
    fun `long object is judged on press and again on release`() {
        val e = engineOf(long(0, 0.5f, 1000.0, 2000.0))
        e.update(1000.0)
        assertEquals(Judgment.JUST, e.press(0.5f, 1000.0)?.judgment)
        e.update(2000.0)
        assertEquals(Judgment.JUST, e.release(0, 2000.0))
        assertEquals(2, e.score.combo)
    }

    @Test
    fun `releasing a long object early breaks it`() {
        val e = engineOf(long(0, 0.5f, 1000.0, 2000.0))
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        assertEquals(Judgment.MISS, e.release(0, 1500.0))
        assertEquals(0, e.score.combo)
    }

    @Test
    fun `holding past the tail still awards the release`() {
        val e = engineOf(long(0, 0.5f, 1000.0, 2000.0))
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        e.update(2000.0 + windows.hitMs + 1.0)
        assertEquals(2, e.score.judgedCount)
        assertEquals(0, e.score.count(Judgment.MISS))
    }

    @Test
    fun `long object never pressed loses both judgments`() {
        val e = engineOf(long(0, 0.5f, 1000.0, 2000.0))
        e.update(1000.0 + windows.hitMs + 1.0)
        assertEquals(2, e.score.count(Judgment.MISS))
    }

    // --- scoring ------------------------------------------------------------

    @Test
    fun `each verdict is worth its fixed points`() {
        val e = engineOf(tap(0, 0.2f, 1000.0), tap(1, 0.5f, 2000.0), tap(2, 0.8f, 3000.0))
        e.update(1000.0); e.press(0.2f, 1000.0)          // JUST, 6
        e.update(2000.0); e.press(0.5f, 2060.0)          // GREAT, 4
        e.update(3000.0); e.press(0.8f, 3100.0)          // GOOD, 2

        assertEquals(12, e.score.score)
    }

    @Test
    fun `a clean run scores six a hit`() {
        val e = engineOf(tap(0, 0.2f, 100.0), tap(1, 0.5f, 200.0), tap(2, 0.8f, 300.0))
        e.update(100.0); e.press(0.2f, 100.0)
        e.update(200.0); e.press(0.5f, 200.0)
        e.update(300.0); e.press(0.8f, 300.0)
        assertEquals(18, e.score.score)
        assertTrue(e.score.isPerfect)
    }

    // --- sustain ------------------------------------------------------------

    @Test
    fun `holding a long object earns keeps as it goes`() {
        val e = engineOf(long(0, 0.5f, 1000.0, 2000.0))
        e.keepIntervalMs = 200.0

        e.update(1000.0)
        e.press(0.5f, 1000.0)
        assertEquals(0, e.score.count(Judgment.KEEP))

        e.update(1500.0)
        assertEquals("two ticks by half way", 2, e.score.count(Judgment.KEEP))

        e.update(2000.0)
        assertEquals("five over the whole hold", 5, e.score.count(Judgment.KEEP))
    }

    @Test
    fun `keeps stop at the tail rather than running on`() {
        val e = engineOf(long(0, 0.5f, 1000.0, 2000.0))
        e.keepIntervalMs = 200.0
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        e.update(9000.0)

        // Holding past the end of an object should not keep earning.
        assertEquals(5, e.score.count(Judgment.KEEP))
    }

    @Test
    fun `keeps do not inflate combo`() {
        val e = engineOf(long(0, 0.5f, 1000.0, 2000.0))
        e.keepIntervalMs = 200.0
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        e.update(1900.0)

        assertTrue("ticks are arriving", e.score.count(Judgment.KEEP) > 0)
        assertEquals("but the combo is still the one press", 1, e.score.combo)
    }

    @Test
    fun `keeps do not fill the clear gauge`() {
        val e = engineOf(long(0, 0.5f, 1000.0, 2000.0))
        e.keepIntervalMs = 200.0
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        e.update(2000.0)

        // One of the long object's two verdicts is in; the ticks add nothing.
        assertEquals(50.0, e.score.gauge, 1e-9)
    }

    @Test
    fun `a sustained long object is worth more than a tap`() {
        val e = engineOf(long(0, 0.5f, 1000.0, 2000.0))
        e.keepIntervalMs = 200.0
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        e.update(2000.0)
        e.release(0, 2000.0)

        // Press 6 + release 6 + five ticks.
        assertEquals(17, e.score.score)
    }

    // --- clear gauge --------------------------------------------------------

    @Test
    fun `the clear gauge starts empty`() {
        val e = engineOf(tap(0, 0.5f, 1000.0))
        assertEquals(0.0, e.score.gauge, 1e-9)
        assertFalse(e.score.isCleared)
    }

    @Test
    fun `a clean run fills the gauge completely`() {
        val notes = (0 until 4).map { tap(it, 0.5f, 1000.0 * (it + 1)) }
        val e = engineOf(*notes.toTypedArray())
        notes.forEach {
            e.update(it.timeMs)
            e.press(0.5f, it.timeMs)
        }
        assertEquals(100.0, e.score.gauge, 1e-9)
        assertTrue(e.score.isCleared)
    }

    @Test
    fun `scraping every object through only fills half the gauge`() {
        // All GOODs: every object struck, none of them well.
        val notes = (0 until 4).map { tap(it, 0.5f, 1000.0 * (it + 1)) }
        val e = engineOf(*notes.toTypedArray())
        notes.forEach {
            e.update(it.timeMs)
            e.press(0.5f, it.timeMs + 100.0)
        }
        assertEquals(4, e.score.count(Judgment.GOOD))
        assertEquals(50.0, e.score.gauge, 1e-9)
        assertFalse("half a gauge is not a clear", e.score.isCleared)
    }

    @Test
    fun `missed objects contribute nothing to the gauge`() {
        val e = engineOf(tap(0, 0.5f, 1000.0), tap(1, 0.5f, 2000.0))
        e.update(1000.0)
        e.press(0.5f, 1000.0)
        e.update(100_000.0)

        assertEquals(1, e.score.count(Judgment.MISS))
        assertEquals("one of two objects struck", 50.0, e.score.gauge, 1e-9)
    }

    @Test
    fun `the gauge never reads above full`() {
        val notes = (0 until 4).map { tap(it, 0.5f, 1000.0 * (it + 1)) }
        val e = engineOf(*notes.toTypedArray())
        notes.forEach {
            e.update(it.timeMs)
            e.press(0.5f, it.timeMs)
        }
        // A perfect run lands exactly on full and must not go past it.
        assertEquals(100.0, e.score.gauge, 1e-9)
    }

    @Test
    fun `the clear line sits exactly at seventy`() {
        // Seven of ten struck cleanly is exactly 70.0 and clears; six does not.
        fun clearedWith(hits: Int): Boolean {
            val notes = (0 until 10).map { tap(it, 0.5f, 1000.0 * (it + 1)) }
            val e = engineOf(*notes.toTypedArray())
            notes.take(hits).forEach {
                e.update(it.timeMs)
                e.press(0.5f, it.timeMs)
            }
            e.update(100_000.0)
            return e.score.isCleared
        }

        assertTrue("70.0 % clears", clearedWith(7))
        assertFalse("below the line fails", clearedWith(6))
    }

    @Test
    fun `clearing needs the threshold, not merely most of the chart`() {
        // Nine of ten struck cleanly clears; six of ten does not.
        fun gaugeAfter(hits: Int): Double {
            val notes = (0 until 10).map { tap(it, 0.5f, 1000.0 * (it + 1)) }
            val e = engineOf(*notes.toTypedArray())
            notes.take(hits).forEach {
                e.update(it.timeMs)
                e.press(0.5f, it.timeMs)
            }
            e.update(100_000.0)
            return e.score.gauge
        }

        assertTrue(gaugeAfter(9) >= 70.0)
        assertTrue(gaugeAfter(6) < 70.0)
    }

    @Test
    fun `visibleNotes respects the look-ahead horizon`() {
        val e = engineOf(tap(0, 0.2f, 1000.0), tap(1, 0.8f, 5000.0))
        assertEquals(1, e.visibleNotes(500.0, 1000.0).size)
        assertEquals(2, e.visibleNotes(500.0, 5000.0).size)
    }

    private companion object {
        /** Mirrors JustReflecGauge's default, so the tests move with it. */
        const val JUSTS_PER_SEGMENT = 8
    }
}
