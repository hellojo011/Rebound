package dev.rebound.core

import dev.rebound.core.song.RebArchive
import dev.rebound.core.song.RebFormatException
import dev.rebound.core.song.SongManifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RebArchiveTest {

    private val audio = "fake audio bytes".toByteArray()
    private val normalChart = "title=T\nbpm=150\ncolumns=8\n--\n10000000\n--\n"
    private val hardChart = "title=T\nbpm=150\ncolumns=8\n--\n11000000\n--\n"

    private val manifest = SongManifest(
        title = "Test Song",
        artist = "Someone",
        audio = "audio.ogg",
        charts = listOf("normal.rbc", "hard.rbc"),
    )

    private val entries = mapOf(
        "audio.ogg" to audio,
        "normal.rbc" to normalChart.toByteArray(),
        "hard.rbc" to hardChart.toByteArray(),
    )

    private fun pack(
        manifest: SongManifest = this.manifest,
        entries: Map<String, ByteArray> = this.entries,
    ): ByteArray = ByteArrayOutputStream().also {
        RebArchive.write(it, manifest, entries)
    }.toByteArray()

    // --- manifest -----------------------------------------------------------

    @Test
    fun `manifest survives a round trip`() {
        val parsed = SongManifest.parse(manifest.serialize())
        assertEquals(manifest, parsed)
    }

    @Test
    fun `manifest keeps every chart listed`() {
        val parsed = SongManifest.parse(manifest.serialize())
        assertEquals(listOf("normal.rbc", "hard.rbc"), parsed.charts)
    }

    @Test
    fun `manifest ignores comments and blank lines`() {
        val parsed = SongManifest.parse(
            "# a song\nformat=1\n\ntitle=T\nartist=A\naudio=a.mp3\nchart=c.rbc\n",
        )
        assertEquals("T", parsed.title)
        assertEquals(listOf("c.rbc"), parsed.charts)
    }

    @Test
    fun `a byte order mark does not break the manifest`() {
        val parsed = SongManifest.parse("﻿" + manifest.serialize())
        assertEquals(manifest, parsed)
    }

    @Test
    fun `manifest from a newer format is refused`() {
        val e = assertThrows(RebFormatException::class.java) {
            SongManifest.parse("format=99\ntitle=T\naudio=a.mp3\nchart=c.rbc\n")
        }
        assertTrue(e.message!!.contains("newer version"))
    }

    @Test
    fun `manifest without charts is refused`() {
        assertThrows(RebFormatException::class.java) {
            SongManifest.parse("format=1\ntitle=T\naudio=a.mp3\n")
        }
    }

    @Test
    fun `manifest without audio is refused`() {
        assertThrows(RebFormatException::class.java) {
            SongManifest.parse("format=1\ntitle=T\nchart=c.rbc\n")
        }
    }

    // --- archive ------------------------------------------------------------

    @Test
    fun `package survives a round trip`() {
        val contents = RebArchive.read(ByteArrayInputStream(pack()))

        assertEquals("Test Song", contents.manifest.title)
        assertEquals("Someone", contents.manifest.artist)
        assertArrayEquals(audio, contents.audio)
        assertEquals(normalChart, contents.chartText("normal.rbc"))
        assertEquals(hardChart, contents.chartText("hard.rbc"))
    }

    @Test
    fun `charts inside a package parse as charts`() {
        val contents = RebArchive.read(ByteArrayInputStream(pack()))
        val chart = dev.rebound.core.chart.ChartParser.parse(
            contents.chartText(contents.manifest.charts.first()),
        )
        assertEquals(1, chart.notes.size)
    }

    @Test
    fun `exporting the same song twice produces identical bytes`() {
        // Reproducible exports mean a re-export with no edits is a no-op, which
        // makes packages easy to version and to compare.
        assertArrayEquals(pack(), pack())
    }

    @Test
    fun `a zip that is not a song package is refused`() {
        val plainZip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use {
                it.putNextEntry(ZipEntry("readme.txt"))
                it.write("hello".toByteArray())
                it.closeEntry()
            }
        }.toByteArray()

        val e = assertThrows(RebFormatException::class.java) {
            RebArchive.read(ByteArrayInputStream(plainZip))
        }
        assertTrue(e.message!!.contains("not a Rebound song"))
    }

    @Test
    fun `a package missing the audio it names is refused`() {
        val broken = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(RebArchive.MANIFEST_ENTRY))
                zip.write(manifest.serialize().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("normal.rbc"))
                zip.write(normalChart.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("hard.rbc"))
                zip.write(hardChart.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val e = assertThrows(RebFormatException::class.java) {
            RebArchive.read(ByteArrayInputStream(broken))
        }
        assertTrue(e.message!!.contains("audio"))
    }

    @Test
    fun `an entry that would escape the extraction directory is refused`() {
        // A .reb comes from outside the app and gets unpacked onto disk, so a
        // traversing entry name has to be stopped before anything is written.
        val hostile = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("../../evil.txt"))
                zip.write("pwned".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val e = assertThrows(RebFormatException::class.java) {
            RebArchive.read(ByteArrayInputStream(hostile))
        }
        assertTrue(e.message!!.contains("unsafe entry name"))
    }

    @Test
    fun `writing rejects an entry name with a path in it`() {
        assertThrows(RebFormatException::class.java) {
            RebArchive.write(
                ByteArrayOutputStream(),
                manifest.copy(audio = "nested/audio.ogg"),
                mapOf(
                    "nested/audio.ogg" to audio,
                    "normal.rbc" to normalChart.toByteArray(),
                    "hard.rbc" to hardChart.toByteArray(),
                ),
            )
        }
    }

    @Test
    fun `writing rejects a manifest whose files were not supplied`() {
        assertThrows(IllegalArgumentException::class.java) {
            RebArchive.write(
                ByteArrayOutputStream(),
                manifest,
                mapOf("audio.ogg" to audio),
            )
        }
    }
}
