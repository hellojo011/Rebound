package dev.rebound.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded 16-bit signed PCM, interleaved. */
class Pcm(
    val samples: ShortArray,
    val channels: Int,
    val sampleRate: Int,
) {
    val frameCount: Int get() = samples.size / channels
    val durationMs: Double get() = frameCount * 1000.0 / sampleRate
}

/**
 * Turns an audio asset into raw PCM once, at load time.
 *
 * Decoding up front costs a little memory (a three minute stereo track at 48 kHz
 * is about 34 MB) and buys two things that matter more: the audio callback
 * becomes a plain memory read with no decoder in the hot path, and the song
 * position is exactly "frames consumed", with no codec buffering to account for.
 */
object PcmDecoder {

    fun decodeAsset(context: Context, assetPath: String): Pcm {
        // Generated .wav assets are parsed directly. It avoids a codec round trip
        // and, more usefully, avoids any doubt about where sample zero is.
        if (assetPath.endsWith(".wav", ignoreCase = true)) {
            return context.assets.open(assetPath).use { parseWav(it.readBytes()) }
        }
        return decodeWithMediaCodec(assetPath) { extractor ->
            context.assets.openFd(assetPath).use {
                extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
        }
    }

    /** Decodes an installed song's audio, whatever container it arrived in. */
    fun decodeFile(file: File): Pcm {
        require(file.isFile) { "no audio file at ${file.path}" }
        if (file.name.endsWith(".wav", ignoreCase = true)) {
            return parseWav(file.readBytes())
        }
        return decodeWithMediaCodec(file.path) { it.setDataSource(file.path) }
    }

    // --- WAV ------------------------------------------------------------

    fun parseWav(bytes: ByteArray): Pcm {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12) { "wav file is truncated" }
        require(readTag(buffer, 0) == "RIFF" && readTag(buffer, 8) == "WAVE") {
            "not a RIFF/WAVE file"
        }

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLength = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = readTag(buffer, pos)
            val size = buffer.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = buffer.getShort(body + 2).toInt()
                    sampleRate = buffer.getInt(body + 4)
                    bitsPerSample = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataLength = size
                }
            }
            // Chunks are word aligned.
            pos = body + size + (size and 1)
        }

        require(dataOffset >= 0) { "wav file has no data chunk" }
        require(bitsPerSample == 16) { "only 16-bit wav is supported, got $bitsPerSample" }
        require(channels > 0) { "wav file declares $channels channels" }

        val available = minOf(dataLength, bytes.size - dataOffset)
        val shorts = ShortArray(available / 2)
        val view = ByteBuffer.wrap(bytes, dataOffset, available)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        view.get(shorts)

        return Pcm(shorts, channels, sampleRate)
    }

    private fun readTag(buffer: ByteBuffer, offset: Int): String =
        String(ByteArray(4) { buffer.get(offset + it) }, Charsets.US_ASCII)

    // --- compressed formats ---------------------------------------------

    /**
     * @param label only for error messages.
     * @param setSource points the extractor at the media; assets and files need
     *   different calls, but everything after that is identical.
     */
    private fun decodeWithMediaCodec(label: String, setSource: (MediaExtractor) -> Unit): Pcm {
        val extractor = MediaExtractor()
        setSource(extractor)

        try {
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("no audio track in $label")

            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            try {
                while (!sawOutputEnd) {
                    if (!sawInputEnd) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuffer = requireNotNull(codec.getInputBuffer(inIndex))
                            val read = extractor.readSampleData(inBuffer, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                sawInputEnd = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> {
                            if (outIndex >= 0) {
                                val outBuffer = codec.getOutputBuffer(outIndex)
                                if (outBuffer != null && info.size > 0) {
                                    val chunk = ByteArray(info.size)
                                    outBuffer.position(info.offset)
                                    outBuffer.get(chunk)
                                    out.write(chunk)
                                }
                                codec.releaseOutputBuffer(outIndex, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    sawOutputEnd = true
                                }
                            }
                        }
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }

            val bytes = out.toByteArray()
            val shorts = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return Pcm(shorts, channels, sampleRate)
        } finally {
            extractor.release()
        }
    }

    // --- rate conversion -------------------------------------------------

    /**
     * Linear resample to [targetRate].
     *
     * Done once at load so the audio callback never has to convert. Linear
     * interpolation adds some high-frequency artefacts; it is inaudible enough
     * for a prototype and only runs at all when the file and the device disagree.
     */
    fun resample(pcm: Pcm, targetRate: Int): Pcm {
        if (pcm.sampleRate == targetRate || pcm.frameCount == 0) return pcm

        val ratio = pcm.sampleRate.toDouble() / targetRate
        val outFrames = (pcm.frameCount / ratio).toInt()
        val channels = pcm.channels
        val out = ShortArray(outFrames * channels)

        for (frame in 0 until outFrames) {
            val srcPos = frame * ratio
            val i0 = srcPos.toInt()
            val i1 = minOf(i0 + 1, pcm.frameCount - 1)
            val t = srcPos - i0
            for (c in 0 until channels) {
                val a = pcm.samples[i0 * channels + c].toDouble()
                val b = pcm.samples[i1 * channels + c].toDouble()
                out[frame * channels + c] = (a + (b - a) * t).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
        return Pcm(out, channels, targetRate)
    }

    private const val TIMEOUT_US = 10_000L
}
