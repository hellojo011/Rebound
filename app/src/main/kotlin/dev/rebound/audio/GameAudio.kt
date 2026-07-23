package dev.rebound.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Loads a song, hands it to the native engine and starts the stream. */
object GameAudio {

    private const val TAG = "ReboundAudio"

    /**
     * The rate the device's fast audio path actually runs at. Feeding Oboe
     * anything else forces a resampler into the output path, which costs latency
     * and muddies the frame counters the clock depends on.
     */
    fun deviceSampleRate(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
    }

    /**
     * Decodes [songAsset], resamples it to the device rate, generates the hit
     * sound and opens the output stream.
     *
     * Blocking and slow (hundreds of ms for a full track) -- call it off the main
     * thread.
     *
     * @return the song duration in ms.
     */
    fun prepareAsset(context: Context, songAsset: String): Double {
        val rate = deviceSampleRate(context)
        return handOver(PcmDecoder.decodeAsset(context, songAsset), rate, songAsset)
    }

    /** Prepares an installed song's audio, in whatever container it arrived in. */
    fun prepareFile(context: Context, file: File): Double {
        val rate = deviceSampleRate(context)
        return handOver(PcmDecoder.decodeFile(file), rate, file.name)
    }

    /**
     * Hands over audio that has already been decoded.
     *
     * The editor decodes once to build its waveform; decoding the same file a
     * second time just to play it would double the wait on a long track.
     */
    fun prepareDecoded(context: Context, decoded: Pcm, label: String): Double =
        handOver(decoded, deviceSampleRate(context), label)

    private fun handOver(decoded: Pcm, targetRate: Int, label: String): Double {
        val song = PcmDecoder.resample(decoded, targetRate)
        Log.i(
            TAG,
            "prepared $label: ${song.frameCount} frames, ${song.channels}ch, " +
                "${song.sampleRate}Hz (source ${decoded.sampleRate}Hz)",
        )

        NativeAudio.nativeSetSong(song.samples, song.channels, song.sampleRate)
        NativeAudio.nativeSetSfx(hitSound(targetRate), 1)

        return song.durationMs
    }

    fun start(): Boolean {
        val ok = NativeAudio.nativeStart()
        if (ok) {
            Log.i(
                TAG,
                "stream ${NativeAudio.nativeStreamSampleRate()}Hz " +
                    "burst=${NativeAudio.nativeFramesPerBurst()} " +
                    "latency=${"%.1f".format(NativeAudio.nativeOutputLatencyMs())}ms",
            )
        } else {
            Log.e(TAG, "failed to open output stream")
        }
        return ok
    }

    fun playSong() = NativeAudio.nativePlaySong()

    /** Plays from an arbitrary point. Song position stays absolute afterwards. */
    fun playSongFrom(ms: Double) = NativeAudio.nativePlaySongFromMs(ms)

    fun pauseSong() = NativeAudio.nativePauseSong()

    /** Continues a paused song. Unlike [playSong] it does not rewind to the top. */
    fun resumeSong() = NativeAudio.nativeResumeSong()

    fun triggerHit() = NativeAudio.nativeTriggerSfx()

    /**
     * Raw song position, with no player calibration applied.
     *
     * The editor wants where the audio actually is, not where the player's
     * calibrated clock says it is -- a chart authored through someone's personal
     * latency correction would be wrong for everybody else.
     */
    fun songPositionMs(): Double = NativeAudio.nativeSongPositionMs()

    fun stop() = NativeAudio.nativeStop()

    fun outputLatencyMs(): Double = NativeAudio.nativeOutputLatencyMs()

    /**
     * A short synthesised click.
     *
     * Generated rather than shipped so the project carries no audio assets it
     * would need to license, and so it is always at the device's sample rate.
     * A fast-decaying tone with a noisy attack reads as a percussive "tick"
     * without masking the music.
     */
    private fun hitSound(sampleRate: Int): ShortArray {
        val lengthFrames = sampleRate / 25 // 40 ms
        val out = ShortArray(lengthFrames)
        var noise = 0x2545F491
        for (i in 0 until lengthFrames) {
            val t = i.toDouble() / sampleRate
            val envelope = exp(-t * 90.0)
            val tone = sin(2.0 * PI * 1800.0 * t) * 0.6
            // xorshift, so the attack is identical on every device and run
            noise = noise xor (noise shl 13)
            noise = noise xor (noise ushr 17)
            noise = noise xor (noise shl 5)
            val transient = (noise / Int.MAX_VALUE.toDouble()) * 0.4 * exp(-t * 900.0)
            val sample = (tone + transient) * envelope * Short.MAX_VALUE * 0.8
            out[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }
}
