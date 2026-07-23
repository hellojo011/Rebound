package dev.rebound.core

import dev.rebound.core.chart.ChartParseException
import dev.rebound.core.chart.ChartParser
import dev.rebound.core.chart.NoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartParserTest {

    /** At 150 BPM a beat is 400 ms and a 4/4 measure is 1600 ms. */
    private fun header(extra: String = "") = """
        title=Demo Track
        artist=Rebound
        audio=demo.wav
        bpm=150
        offset=0
        columns=8
        $extra
        --
    """.trimIndent()

    @Test
    fun `reads header metadata`() {
        val chart = ChartParser.parse(header("level=7") + "\n00000000\n--\n")
        assertEquals("Demo Track", chart.meta.title)
        assertEquals("demo.wav", chart.meta.audio)
        assertEquals(150.0, chart.meta.bpm, 1e-9)
        assertEquals(8, chart.meta.columns)
        assertEquals(7, chart.meta.level)
    }

    @Test
    fun `four rows subdivide a measure into quarter notes`() {
        val chart = ChartParser.parse(header() + "\n10000000\n10000000\n10000000\n10000000\n--\n")
        assertEquals(listOf(0.0, 400.0, 800.0, 1200.0), chart.notes.map { it.timeMs })
        assertTrue(chart.notes.all { it.type == NoteType.TAP })
    }

    @Test
    fun `objects land at scattered positions along the bar`() {
        // Only the tap points are fixed; a bar object may cross anywhere, so the
        // column an author writes in does not decide where it lands.
        val chart = ChartParser.parse(
            header() + "\n10000000\n10000000\n10000000\n10000000\n" +
                "10000000\n10000000\n10000000\n10000000\n--\n",
        )
        assertTrue(
            "expected varied landing positions",
            chart.notes.map { it.x }.distinct().size >= 4,
        )
        assertTrue("and all of them on the bar", chart.notes.all { it.x in 0f..1f })
    }

    @Test
    fun `an object comes in from the half opposite where it lands`() {
        val chart = ChartParser.parse(
            header() + "\n10000000\n10000000\n10000000\n10000000\n" +
                "10000000\n10000000\n10000000\n10000000\n--\n",
        )
        chart.notes.forEach {
            if (it.x < 0.5f) {
                assertTrue("landing ${it.x} should come from the right", it.spawnX > 0.5f)
            } else {
                assertTrue("landing ${it.x} should come from the left", it.spawnX < 0.5f)
            }
        }
    }

    @Test
    fun `objects landing mid-bar still approach at an angle`() {
        val chart = ChartParser.parse(header() + "\n00010000\n00010000\n--\n")
        chart.notes.forEach {
            assertNotEquals("should not fall straight down", it.x, it.spawnX, 0.05f)
        }
    }

    @Test
    fun `approach angles are scattered rather than identical`() {
        // A fixed rule makes a chart look mechanical; the origins should vary
        // even for objects landing in the same place.
        val chart = ChartParser.parse(
            header() + "\n00010000\n00010000\n00010000\n00010000\n" +
                "00010000\n00010000\n00010000\n00010000\n--\n",
        )
        assertTrue(
            "expected varied approach origins",
            chart.notes.map { it.spawnX }.distinct().size >= 4,
        )
    }

    @Test
    fun `spawn position always stays inside the field`() {
        val chart = ChartParser.parse(
            header() + "\n10000000\n01000000\n00100000\n00010000\n--\n" +
                "00001000\n00000100\n00000010\n00000001\n--\n",
        )
        chart.notes.forEach {
            assertTrue("spawn ${it.spawnX} out of range", it.spawnX in 0f..1f)
        }
    }

    @Test
    fun `gold objects parse as their own type`() {
        val chart = ChartParser.parse(header() + "\n20000000\n--\n")
        assertEquals(NoteType.GOLD, chart.notes.single().type)
    }

    @Test
    fun `green objects snap onto the nearest tap point`() {
        val chart = ChartParser.parse(header() + "\n50000000\n00050000\n00000005\n--\n")
        assertEquals(
            FieldGeometry.TAP_POINT_XS.toList(),
            chart.notes.map { it.x },
        )
        assertTrue(chart.notes.all { it.type == NoteType.GREEN })
    }

    @Test
    fun `green objects are judged at the tap point row, others at the bar`() {
        val chart = ChartParser.parse(header() + "\n50000000\n10000000\n--\n")
        assertEquals(FieldGeometry.TAP_POINT_Y, chart.notes[0].y, 1e-6f)
        assertEquals(FieldGeometry.BAR_Y, chart.notes[1].y, 1e-6f)
    }

    @Test
    fun `long object spans from start marker to end marker`() {
        val chart = ChartParser.parse(header() + "\n30000000\n00000000\n40000000\n00000000\n--\n")
        val long = chart.notes.single()
        assertEquals(NoteType.LONG, long.type)
        assertEquals(0.0, long.timeMs, 1e-9)
        assertEquals(800.0, long.endTimeMs, 1e-9)
    }

    @Test
    fun `simultaneous objects keep distinct positions`() {
        val chart = ChartParser.parse(header() + "\n10000001\n--\n")
        assertEquals(2, chart.notes.size)
        assertEquals(chart.notes[0].timeMs, chart.notes[1].timeMs, 1e-9)
        assertNotEquals(chart.notes[0].x, chart.notes[1].x, 1e-6f)
    }

    @Test
    fun `offset shifts every object`() {
        val chart = ChartParser.parse(
            "title=T\nbpm=150\noffset=250\ncolumns=8\n--\n10000000\n--\n",
        )
        assertEquals(250.0, chart.notes.single().timeMs, 1e-9)
    }

    @Test
    fun `mid-chart bpm command changes spacing from that row on`() {
        val chart = ChartParser.parse(
            header() + "\n10000000\n10000000\nbpm=300\n10000000\n10000000\n--\n",
        )
        val times = chart.notes.map { it.timeMs }
        assertEquals(0.0, times[0], 1e-9)
        assertEquals(400.0, times[1], 1e-9)
        // The command takes effect *at* row 3, so the gap leading up to it is
        // still spanned at the old tempo.
        assertEquals(800.0, times[2], 1e-9)
        assertEquals(1000.0, times[3], 1e-9)
    }

    @Test
    fun `empty measure advances time`() {
        val chart = ChartParser.parse(header() + "\n--\n10000000\n--\n")
        assertEquals(1600.0, chart.notes.single().timeMs, 1e-9)
    }

    @Test
    fun `a byte order mark does not break the header`() {
        // Windows editors write UTF-8 with a BOM by default, and charts are
        // hand-authored, so this arrives more often than it ought to.
        val chart = ChartParser.parse("﻿" + header() + "\n10000000\n--\n")
        assertEquals("Demo Track", chart.meta.title)
        assertEquals(1, chart.notes.size)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val chart = ChartParser.parse(header() + "\n# note\n\n10000000 // trailing\n--\n")
        assertEquals(1, chart.notes.size)
    }

    @Test
    fun `maxCombo counts long objects twice`() {
        val chart = ChartParser.parse(header() + "\n30000000\n01000000\n40000000\n00000000\n--\n")
        assertEquals(3, chart.maxCombo)
    }

    @Test
    fun `rejects row whose width does not match column count`() {
        val e = assertThrows(ChartParseException::class.java) {
            ChartParser.parse(header() + "\n100000000\n--\n")
        }
        assertTrue(e.message!!.contains("9 columns"))
    }

    @Test
    fun `rejects unknown object character`() {
        assertThrows(ChartParseException::class.java) {
            ChartParser.parse(header() + "\n90000000\n--\n")
        }
    }

    @Test
    fun `rejects unclosed long object`() {
        assertThrows(ChartParseException::class.java) {
            ChartParser.parse(header() + "\n30000000\n00000000\n--\n")
        }
    }

    @Test
    fun `rejects long end without start`() {
        assertThrows(ChartParseException::class.java) {
            ChartParser.parse(header() + "\n40000000\n--\n")
        }
    }

    @Test
    fun `rejects missing bpm header`() {
        assertThrows(ChartParseException::class.java) {
            ChartParser.parse("title=T\ncolumns=8\n--\n10000000\n--\n")
        }
    }
}
