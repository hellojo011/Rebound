package dev.rebound.core

import dev.rebound.core.chart.ChartMeta
import dev.rebound.core.chart.ChartParser
import dev.rebound.core.chart.ChartWriteException
import dev.rebound.core.chart.GridObject
import dev.rebound.core.chart.NoteType
import dev.rebound.core.chart.RbcWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RbcWriterTest {

    /** 150 BPM: a beat is 400 ms and a 4/4 measure 1600 ms. */
    private val meta = ChartMeta(
        title = "Written",
        artist = "Editor",
        audio = "audio.wav",
        bpm = 150.0,
        offsetMs = 0.0,
        columns = 8,
        level = 4,
        difficulty = "HARD",
    )

    private fun tap(timeMs: Double, column: Int) = GridObject(timeMs, column, NoteType.TAP)

    private fun write(vararg objects: GridObject) = RbcWriter.write(meta, objects.toList())

    // --- resolution ---------------------------------------------------------

    @Test
    fun `quarter notes need only four rows a measure`() {
        val text = write(tap(0.0, 0), tap(400.0, 1), tap(800.0, 2), tap(1200.0, 3))
        assertEquals(4, rowsOfFirstMeasure(text))
    }

    @Test
    fun `an eighth forces eight rows`() {
        val text = write(tap(0.0, 0), tap(200.0, 1))
        assertEquals(8, rowsOfFirstMeasure(text))
    }

    @Test
    fun `a triplet is written as thirds rather than forced onto sixteenths`() {
        // A beat split three ways: 0, 133.33, 266.67 ms.
        val third = 400.0 / 3.0
        val text = write(tap(0.0, 0), tap(third, 1), tap(third * 2, 2))
        assertEquals(12, rowsOfFirstMeasure(text))
    }

    @Test
    fun `resolution follows the finest object in the chart`() {
        assertEquals(1, RbcWriter.divisionFor(listOf(tap(0.0, 0), tap(400.0, 1)), 150.0, 0.0))
        assertEquals(4, RbcWriter.divisionFor(listOf(tap(0.0, 0), tap(100.0, 1)), 150.0, 0.0))
    }

    @Test
    fun `an object off every subdivision is refused`() {
        val e = assertThrows(ChartWriteException::class.java) {
            write(tap(0.0, 0), tap(37.0, 1))
        }
        assertTrue(e.message!!.contains("subdivision"))
    }

    // --- round trip ---------------------------------------------------------

    @Test
    fun `objects survive a write and a read`() {
        val objects = listOf(
            tap(0.0, 0),
            GridObject(400.0, 3, NoteType.GOLD),
            GridObject(800.0, 7, NoteType.GREEN),
            tap(1200.0, 1),
            tap(1600.0, 5),
        )
        val chart = ChartParser.parse(RbcWriter.write(meta, objects))

        assertEquals(listOf(0.0, 400.0, 800.0, 1200.0, 1600.0), chart.notes.map { it.timeMs })
        assertEquals(
            listOf(NoteType.TAP, NoteType.GOLD, NoteType.GREEN, NoteType.TAP, NoteType.TAP),
            chart.notes.map { it.type },
        )
    }

    @Test
    fun `an authored rally survives a write and a read`() {
        // A gold, then the object it is pinned to become, marked rallied. Room
        // between them so the pairing is not lost to a too-short flight.
        val objects = listOf(
            GridObject(0.0, 0, NoteType.GOLD),
            GridObject(1600.0, 1, NoteType.TAP, rallied = true),
        )
        val chart = ChartParser.parse(RbcWriter.write(meta, objects))

        val gold = chart.notes.first { it.type == NoteType.GOLD }
        val target = chart.notes.first { it.rallySourceIndex >= 0 }
        assertEquals(target.index, gold.rallyTargetIndex)
        assertTrue("kept as an authored pairing", target.rallyExplicit)
    }

    @Test
    fun `a bracketed chain survives a write and a read`() {
        val objects = listOf(
            GridObject(0.0, 0, NoteType.CHAIN, chainStart = true),
            GridObject(400.0, 0, NoteType.CHAIN),
            GridObject(800.0, 0, NoteType.CHAIN, chainStop = true),
        )
        val chart = ChartParser.parse(RbcWriter.write(meta, objects))
        val links = chart.notes.filter { it.type == NoteType.CHAIN }.sortedBy { it.timeMs }
        assertEquals(3, links.size)
        assertTrue(links[0].chainStart)
        assertEquals(links[0].index, links[1].chainPrevIndex)
        assertEquals(links[1].index, links[2].chainPrevIndex)
        assertTrue(links[2].chainStop)
    }

    @Test
    fun `a green long survives a write and a read`() {
        val chart = ChartParser.parse(
            RbcWriter.write(
                meta,
                listOf(GridObject(400.0, 1, NoteType.LONG, endTimeMs = 1200.0, green = true)),
            ),
        )
        val long = chart.notes.single()
        assertTrue("kept as a tap-point hold", long.isGreenLong)
        assertEquals(400.0, long.timeMs, 1e-9)
        assertEquals(1200.0, long.endTimeMs, 1e-9)
    }

    @Test
    fun `header metadata survives a round trip`() {
        val chart = ChartParser.parse(write(tap(0.0, 0)))
        assertEquals("Written", chart.meta.title)
        assertEquals("Editor", chart.meta.artist)
        assertEquals("audio.wav", chart.meta.audio)
        assertEquals(150.0, chart.meta.bpm, 1e-9)
        assertEquals(8, chart.meta.columns)
        assertEquals(4, chart.meta.level)
        assertEquals("HARD", chart.meta.difficulty)
    }

    @Test
    fun `a long object keeps its length`() {
        val chart = ChartParser.parse(
            write(GridObject(400.0, 2, NoteType.LONG, endTimeMs = 1200.0)),
        )
        val long = chart.notes.single()
        assertEquals(NoteType.LONG, long.type)
        assertEquals(400.0, long.timeMs, 1e-9)
        assertEquals(1200.0, long.endTimeMs, 1e-9)
    }

    @Test
    fun `a long object spanning a measure boundary survives`() {
        val chart = ChartParser.parse(
            write(GridObject(1200.0, 2, NoteType.LONG, endTimeMs = 2400.0)),
        )
        val long = chart.notes.single()
        assertEquals(1200.0, long.timeMs, 1e-9)
        assertEquals(2400.0, long.endTimeMs, 1e-9)
    }

    @Test
    fun `simultaneous objects share a row`() {
        val chart = ChartParser.parse(write(tap(800.0, 0), tap(800.0, 7)))
        assertEquals(2, chart.notes.size)
        assertEquals(chart.notes[0].timeMs, chart.notes[1].timeMs, 1e-9)
    }

    @Test
    fun `offset is carried through`() {
        val shifted = meta.copy(offsetMs = 250.0)
        val chart = ChartParser.parse(
            RbcWriter.write(shifted, listOf(tap(250.0, 0), tap(650.0, 1))),
        )
        assertEquals(listOf(250.0, 650.0), chart.notes.map { it.timeMs })
    }

    @Test
    fun `an empty chart still writes a readable header`() {
        val chart = ChartParser.parse(RbcWriter.write(meta, emptyList()))
        assertEquals(0, chart.notes.size)
        assertEquals("Written", chart.meta.title)
    }

    @Test
    fun `an object before the first beat is refused`() {
        val shifted = meta.copy(offsetMs = 500.0)
        assertThrows(ChartWriteException::class.java) {
            RbcWriter.write(shifted, listOf(tap(0.0, 0)))
        }
    }

    @Test
    fun `an object outside the column count is refused`() {
        assertThrows(ChartWriteException::class.java) { write(tap(0.0, 99)) }
    }

    @Test
    fun `a hold too short to survive quantisation is refused`() {
        // Both ends land on the same row, which would silently become a tap.
        assertThrows(ChartWriteException::class.java) {
            write(GridObject(0.0, 0, NoteType.LONG, endTimeMs = 0.0))
        }
    }

    /** Rows between the header's `--` and the first measure's `--`. */
    private fun rowsOfFirstMeasure(text: String): Int {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val start = lines.indexOf("--") + 1
        return lines.drop(start).indexOf("--")
    }
}
