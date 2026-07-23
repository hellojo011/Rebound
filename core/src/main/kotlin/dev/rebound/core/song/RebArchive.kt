package dev.rebound.core.song

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A `.reb` read into memory: the manifest plus every entry's bytes. */
class RebContents(
    val manifest: SongManifest,
    private val entries: Map<String, ByteArray>,
) {
    val audio: ByteArray
        get() = entries[manifest.audio]
            ?: throw RebFormatException("manifest points at \"${manifest.audio}\", which is missing")

    fun chartText(entry: String): String =
        entries[entry]?.toString(Charsets.UTF_8)
            ?: throw RebFormatException("manifest points at \"$entry\", which is missing")

    /** Every entry except the manifest, for writing the song out to a directory. */
    fun payload(): Map<String, ByteArray> = entries
}

/**
 * Reader and writer for `.reb`, the single-file song package.
 *
 * It is a zip holding a [SongManifest], the audio in whatever encoding it was
 * authored with, and one `.rbc` per difficulty. Zip because it is standard,
 * already in the JDK, and lets a song stay one file the player can hand around
 * without the audio and the chart ever getting separated.
 *
 * The archive is kept deliberately flat -- no directories, no nesting. Imported
 * songs come from wherever the player found them, and a flat namespace means an
 * entry name can never escape the directory it is being extracted into.
 */
object RebArchive {

    const val EXTENSION = "reb"
    const val MANIFEST_ENTRY = "rebound.manifest"

    /** Refuses anything larger, rather than letting a bad file exhaust memory. */
    const val MAX_UNCOMPRESSED_BYTES = 96L * 1024 * 1024

    fun read(input: InputStream): RebContents {
        val entries = mutableMapOf<String, ByteArray>()
        var total = 0L

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val name = entry.name
                validateEntryName(name)

                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = zip.read(chunk)
                    if (read <= 0) break
                    total += read
                    // Checked against the decompressed size as it is read, so a
                    // small file that inflates enormously is stopped too.
                    if (total > MAX_UNCOMPRESSED_BYTES) {
                        throw RebFormatException("song package is unreasonably large")
                    }
                    buffer.write(chunk, 0, read)
                }
                entries[name] = buffer.toByteArray()
                zip.closeEntry()
            }
        }

        val manifestBytes = entries.remove(MANIFEST_ENTRY)
            ?: throw RebFormatException("not a Rebound song: no $MANIFEST_ENTRY inside")

        val manifest = SongManifest.parse(manifestBytes.toString(Charsets.UTF_8))

        // Fail here rather than halfway through installing.
        if (manifest.audio !in entries) {
            throw RebFormatException("manifest names audio \"${manifest.audio}\", which is missing")
        }
        manifest.charts.forEach {
            if (it !in entries) {
                throw RebFormatException("manifest names chart \"$it\", which is missing")
            }
        }

        return RebContents(manifest, entries)
    }

    fun write(output: OutputStream, manifest: SongManifest, entries: Map<String, ByteArray>) {
        entries.keys.forEach(::validateEntryName)
        require(manifest.audio in entries) { "audio entry \"${manifest.audio}\" was not supplied" }
        manifest.charts.forEach {
            require(it in entries) { "chart entry \"$it\" was not supplied" }
        }

        ZipOutputStream(output).use { zip ->
            zip.putEntry(MANIFEST_ENTRY, manifest.serialize().toByteArray(Charsets.UTF_8))
            // Sorted so exporting the same song twice produces the same bytes.
            entries.toSortedMap().forEach { (name, bytes) -> zip.putEntry(name, bytes) }
        }
    }

    private fun ZipOutputStream.putEntry(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        // A fixed timestamp keeps exports reproducible and diffable.
        entry.time = 0L
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    /**
     * Entry names must be plain file names.
     *
     * A `.reb` arrives from outside the app and is unpacked onto disk, so an
     * entry called `../../databases/x` would otherwise write wherever it liked.
     */
    private fun validateEntryName(name: String) {
        if (name.isEmpty() ||
            name.contains('/') ||
            name.contains('\\') ||
            name == "." ||
            name == ".."
        ) {
            throw RebFormatException("unsafe entry name in song package: \"$name\"")
        }
    }
}
