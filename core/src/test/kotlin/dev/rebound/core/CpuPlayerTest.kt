package dev.rebound.core

import dev.rebound.core.chart.Chart
import dev.rebound.core.chart.ChartMeta
import dev.rebound.core.chart.Note
import dev.rebound.core.chart.NoteType
import dev.rebound.core.play.CpuPlayer
import dev.rebound.core.play.Judgment
import dev.rebound.core.play.PlayEngine
import dev.rebound.core.play.ScoreState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuPlayerTest {

    private val objectCount = 60
    private val spacingMs = 400.0

    private fun chart(): Chart {
        val notes = (0 until objectCount).map {
            Note(it, NoteType.TAP, 0.5f, 0.5f, spacingMs * (it + 1))
        }
        return Chart(ChartMeta(columns = 8, bpm = 150.0), notes)
    }

    /** Plays a whole chart at [skill], stepping time like a render loop would. */
    private fun play(skill: CpuPlayer.Skill, seed: Int = 0): ScoreState {
        val engine = PlayEngine(chart())
        val cpu = CpuPlayer(engine, skill, seed)

        var songMs = 0.0
        val end = spacingMs * (objectCount + 2)
        while (songMs < end) {
            cpu.update(songMs)
            engine.update(songMs)
            songMs += 8.0
        }
        return engine.score
    }

    @Test
    fun `a perfect cpu drops nothing`() {
        val score = play(CpuPlayer.Skill.PERFECT)
        assertEquals(0, score.count(Judgment.MISS))
        assertEquals(objectCount, score.count(Judgment.JUST))
    }

    @Test
    fun `an easy cpu is beatable`() {
        val score = play(CpuPlayer.Skill.EASY)
        assertTrue("should drop some objects", score.count(Judgment.MISS) > 0)
        assertTrue("but should still play most of the chart", score.judgedCount == objectCount)
    }

    @Test
    fun `skill levels rank the way their names say`() {
        val easy = play(CpuPlayer.Skill.EASY).score
        val normal = play(CpuPlayer.Skill.NORMAL).score
        val hard = play(CpuPlayer.Skill.HARD).score

        assertTrue("normal should beat easy", normal > easy)
        assertTrue("hard should beat normal", hard > normal)
    }

    @Test
    fun `the same chart and skill play out identically`() {
        // Reproducibility is the point: a CPU that rolled fresh dice each run
        // would make it impossible to tell an engine change from a lucky one.
        assertEquals(play(CpuPlayer.Skill.NORMAL).score, play(CpuPlayer.Skill.NORMAL).score)
    }

    @Test
    fun `a different seed plays differently`() {
        val a = play(CpuPlayer.Skill.NORMAL, seed = 1).score
        val b = play(CpuPlayer.Skill.NORMAL, seed = 2).score
        assertTrue("seeds should not collapse to one performance", a != b)
    }

    @Test
    fun `a cpu that stalls does not claim objects it slept through`() {
        val engine = PlayEngine(chart())
        val cpu = CpuPlayer(engine, CpuPlayer.Skill.PERFECT)

        // One enormous frame, as if the app had been backgrounded.
        cpu.update(0.0)
        cpu.update(spacingMs * 20)
        engine.update(spacingMs * 20)

        assertTrue("objects slept through are misses", engine.score.count(Judgment.MISS) > 0)
    }
}
