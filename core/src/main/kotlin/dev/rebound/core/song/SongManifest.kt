package dev.rebound.core.song

/** Thrown when a `.reb` is malformed, or is not a `.reb` at all. */
class RebFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The index inside a `.reb`: what the song is, and which entries hold its parts.
 *
 * Song-level facts live here rather than being read out of one of the charts,
 * because a song has several charts and they must not be able to disagree about
 * the title. Per-difficulty facts -- tempo, level, the objects themselves --
 * stay in each `.rbc`.
 *
 * Serialised in the same `key=value` style as the chart format, so the whole
 * project has one text convention and no serialisation dependency.
 */
data class SongManifest(
    val title: String,
    val artist: String,
    /** Entry name of the audio, in whatever encoding it was authored with. */
    val audio: String,
    /** Entry names of the charts, one per difficulty. */
    val charts: List<String>,
    val format: Int = FORMAT_VERSION,
) {
    init {
        require(charts.isNotEmpty()) { "a song package needs at least one chart" }
    }

    fun serialize(): String = buildString {
        appendLine("format=$format")
        appendLine("title=$title")
        appendLine("artist=$artist")
        appendLine("audio=$audio")
        charts.forEach { appendLine("chart=$it") }
    }

    companion object {
        /**
         * Bumped only for changes older readers cannot cope with. A reader
         * refuses anything newer than it understands rather than guessing.
         */
        const val FORMAT_VERSION = 1

        fun parse(text: String): SongManifest {
            val values = mutableMapOf<String, String>()
            val charts = mutableListOf<String>()

            // Windows editors and PowerShell write UTF-8 with a byte order mark.
            // Left in place it becomes part of the first key, and the file fails
            // with a message about the wrong thing entirely.
            text.removePrefix("﻿").lineSequence().forEach { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEach
                val key = line.substringBefore('=', missingDelimiterValue = "").trim().lowercase()
                if (key.isEmpty()) throw RebFormatException("manifest line is not key=value: \"$raw\"")
                val value = line.substringAfter('=').trim()
                if (key == "chart") charts += value else values[key] = value
            }

            val format = values["format"]?.toIntOrNull()
                ?: throw RebFormatException("manifest has no format version")
            if (format > FORMAT_VERSION) {
                throw RebFormatException(
                    "song was made with a newer version of Rebound (format $format, " +
                        "this build reads up to $FORMAT_VERSION)",
                )
            }
            if (charts.isEmpty()) throw RebFormatException("manifest lists no charts")

            return SongManifest(
                title = values["title"] ?: "Untitled",
                artist = values["artist"] ?: "Unknown",
                audio = values["audio"] ?: throw RebFormatException("manifest has no audio entry"),
                charts = charts,
                format = format,
            )
        }
    }
}
