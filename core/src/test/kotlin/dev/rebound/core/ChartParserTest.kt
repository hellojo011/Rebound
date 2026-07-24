package dev.rebound.core

import dev.rebound.core.chart.ChartParseException
import dev.rebound.core.chart.ChartParser
import dev.rebound.core.chart.NoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
    fun `an approach always stays inside the field`() {
        val chart = ChartParser.parse(
            header() + "\n10000000\n01000000\n00100000\n00010000\n--\n" +
                "00001000\n00000100\n00000010\n00000001\n--\n",
        )
        // A spawn may sit outside 0..1 -- that is what makes the approach carom
        // off a wall -- but the folded path it produces must never leave the
        // field at any point along it.
        chart.notes.forEach { note ->
            var p = 0f
            while (p <= 1f) {
                val x = note.approachX(p)
                assertTrue("approach ${note.index} left the field at $p: $x", x in -1e-5f..1f + 1e-5f)
                p += 0.02f
            }
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
    fun `objects during a long hold do not land on it`() {
        // A long held across a whole measure, with a tap on every row of it.
        val chart = ChartParser.parse(
            header() + "\n30000000\n10000000\n10000000\n40000000\n--\n",
        )
        val long = chart.notes.first { it.isLong }
        val taps = chart.notes.filter { it.type == NoteType.TAP }
        assertEquals(2, taps.size)
        taps.forEach {
            assertTrue(
                "a tap at ${it.timeMs} landed on the held long (${it.x} vs ${long.x})",
                abs(it.x - long.x) >= 0.2f,
            )
        }
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
            ChartParser.parse(header() + "\nx0000000\n--\n")
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

    // --- rallies ------------------------------------------------------------

    @Test
    fun `a lone gold picks a later object to become`() {
        // A gold, then a tap a beat later with room to fly to.
        val chart = ChartParser.parse(header() + "\n20000000\n00000000\n00000000\n00000000\n--\n" +
            "10000000\n00000000\n00000000\n00000000\n--\n")
        val gold = chart.notes.first { it.type == NoteType.GOLD }
        val tap = chart.notes.first { it.type == NoteType.TAP }
        assertEquals(tap.index, gold.rallyTargetIndex)
        assertEquals(gold.index, tap.rallySourceIndex)
        assertEquals("chosen, not authored", false, tap.rallyExplicit)
    }

    @Test
    fun `an authored rally pins the target the gold becomes`() {
        // Gold in column 0, its target written as an 8 (rallied gold) later.
        val chart = ChartParser.parse(header() + "\n20000000\n00000000\n00000000\n00000000\n--\n" +
            "80000000\n00000000\n00000000\n00000000\n--\n")
        val gold = chart.notes.first()
        val target = chart.notes.first { it.rallySourceIndex >= 0 }
        assertEquals(target.index, gold.rallyTargetIndex)
        assertTrue("authored targets are marked", target.rallyExplicit)
    }

    @Test
    fun `a green object can be what a gold becomes`() {
        // A gold, then a lone green (a TOP object) with room in front of it: the
        // gold flies up to the tap point the green is judged over.
        val chart = ChartParser.parse(header() + "\n20000000\n00000000\n00000000\n00000000\n--\n" +
            "50000000\n00000000\n00000000\n00000000\n--\n")
        val gold = chart.notes.first { it.type == NoteType.GOLD }
        val green = chart.notes.first { it.type == NoteType.GREEN }
        assertEquals(green.index, gold.rallyTargetIndex)
        assertEquals(gold.index, green.rallySourceIndex)
    }

    @Test
    fun `a long object is never what a gold becomes`() {
        val chart = ChartParser.parse(header() + "\n20000000\n00000000\n00000000\n00000000\n--\n" +
            "30000000\n00000000\n40000000\n00000000\n--\n")
        val gold = chart.notes.first { it.type == NoteType.GOLD }
        assertEquals(-1, gold.rallyTargetIndex)
    }

    @Test
    fun `an object may carom off a wall on the way in`() {
        // Enough objects that the per-object hash makes at least one bounce.
        val chart = ChartParser.parse(
            header() + "\n" + (0 until 8).joinToString("") { "10000000\n" } + "--\n" +
                (0 until 8).joinToString("") { "00000001\n" } + "--\n",
        )
        assertTrue(
            "some approach should meet a wall",
            chart.notes.any { it.bouncesOnApproach },
        )
    }

    // --- chains -------------------------------------------------------------

    @Test
    fun `a bracketed run joins from start to stop`() {
        // [ opens, 6 continues, ] closes -- one three-link chain.
        val chart = ChartParser.parse(header() + "\n[0000000\n60000000\n]0000000\n00000000\n--\n")
        val links = chart.notes.filter { it.type == NoteType.CHAIN }.sortedBy { it.timeMs }
        assertEquals(3, links.size)
        assertEquals("the start heads the run", -1, links[0].chainPrevIndex)
        assertTrue("and it is flagged as the start", links[0].chainStart)
        assertEquals(links[0].index, links[1].chainPrevIndex)
        assertEquals(links[1].index, links[2].chainPrevIndex)
        assertTrue("the last is flagged as the stop", links[2].chainStop)
    }

    @Test
    fun `every link of one chain lands on the same spot`() {
        val chart = ChartParser.parse(header() + "\n[0000000\n60000000\n]0000000\n00000000\n--\n")
        val xs = chart.notes.filter { it.type == NoteType.CHAIN }.map { it.x }.distinct()
        assertEquals("a run converges on one landing", 1, xs.size)
    }

    @Test
    fun `an unbracketed chain link joins nothing`() {
        // A plain 6 with no bracket around it is a lone object.
        val chart = ChartParser.parse(header() + "\n60000000\n60000000\n00000000\n00000000\n--\n")
        assertTrue(chart.notes.all { it.chainPrevIndex < 0 })
    }

    @Test
    fun `two bracketed runs in different columns stay separate`() {
        // A run in column 0 and a run in column 6, brackets interleaved in time.
        val chart = ChartParser.parse(
            header() + "\n[00000[0\n]00000]0\n00000000\n00000000\n--\n",
        )
        val colZero = chart.notes.filter { it.column == 0 }.sortedBy { it.timeMs }
        val colSix = chart.notes.filter { it.column == 6 }.sortedBy { it.timeMs }
        assertEquals(2, colZero.size)
        assertEquals(2, colSix.size)
        assertEquals(-1, colZero[0].chainPrevIndex)
        assertEquals(colZero[0].index, colZero[1].chainPrevIndex)
        assertEquals(-1, colSix[0].chainPrevIndex)
        assertEquals(colSix[0].index, colSix[1].chainPrevIndex)
    }

    @Test
    fun `a gold inside a bracket joins the chain`() {
        // [ then a gold then ]: the gold is a middle link of the run.
        val chart = ChartParser.parse(header() + "\n[0000000\n20000000\n]0000000\n00000000\n--\n")
        val ordered = chart.notes.sortedBy { it.timeMs }
        assertEquals(NoteType.GOLD, ordered[1].type)
        assertEquals("the gold joins the run", ordered[0].index, ordered[1].chainPrevIndex)
        assertEquals("and the stop joins the gold", ordered[1].index, ordered[2].chainPrevIndex)
        assertEquals("all share the run's landing", ordered[0].x, ordered[1].x, 1e-6f)
    }

    @Test
    fun `a plain tap inside a bracket joins the chain`() {
        // The bracket alone decides the chain: a tap between [ and ] is a link.
        val chart = ChartParser.parse(header() + "\n[0000000\n10000000\n]0000000\n00000000\n--\n")
        val ordered = chart.notes.sortedBy { it.timeMs }
        assertEquals(NoteType.TAP, ordered[1].type)
        assertEquals("the tap joins the run", ordered[0].index, ordered[1].chainPrevIndex)
        assertEquals("and the stop joins the tap", ordered[1].index, ordered[2].chainPrevIndex)
        assertEquals("the tap shares the run's landing", ordered[0].x, ordered[1].x, 1e-6f)
    }

    @Test
    fun `a tap outside a bracket joins nothing`() {
        // A tap on its own, no brackets: an ordinary object, not a chain link.
        val chart = ChartParser.parse(header() + "\n10000000\n10000000\n00000000\n00000000\n--\n")
        assertTrue(chart.notes.all { it.chainPrevIndex < 0 })
    }

    // --- green longs --------------------------------------------------------

    @Test
    fun `a green long is a long held at a tap point`() {
        val chart = ChartParser.parse(header() + "\n{0000000\n00000000\n}0000000\n00000000\n--\n")
        val long = chart.notes.single()
        assertEquals(NoteType.LONG, long.type)
        assertTrue("held up at a tap point", long.isGreenLong)
        assertEquals(FieldGeometry.TAP_POINT_Y, long.y, 1e-6f)
        assertEquals(0.0, long.timeMs, 1e-9)
        assertEquals(800.0, long.endTimeMs, 1e-9)
    }

    @Test
    fun `a green long snaps onto a tap point`() {
        val chart = ChartParser.parse(header() + "\n{0000000\n}0000000\n00000000\n00000000\n--\n")
        assertTrue(chart.notes.single().x in FieldGeometry.TAP_POINT_XS.toList())
    }
}
