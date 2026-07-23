package dev.rebound.editor

import dev.rebound.audio.Pcm
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A peak envelope of a track, for drawing.
 *
 * Reduced to one amplitude per bucket of frames rather than kept sample by
 * sample: at any zoom level a chart author can actually work at, a screen column
 * covers hundreds of samples, and the only thing that matters is how loud the
 * loudest one was — that is what makes a transient visible as a spike you can
 * line a beat up against.
 */
class Waveform(
    /** Peak amplitude per bucket, 0..1. */
    val peaks: FloatArray,
    val framesPerBucket: Int,
    val sampleRate: Int,
    val frameCount: Int,
) {
    val durationMs: Double = frameCount * 1000.0 / sampleRate

    private val msPerBucket: Double = framesPerBucket * 1000.0 / sampleRate

    /**
     * The loudest peak between two times, or 0 outside the track.
     *
     * Taking the maximum rather than an average is deliberate: averaging would
     * smooth away the very transients the grid has to be aligned to.
     */
    fun peakBetween(startMs: Double, endMs: Double): Float {
        if (endMs <= 0.0 || startMs >= durationMs || peaks.isEmpty()) return 0f

        val first = max(0, (startMs / msPerBucket).toInt())
        val last = min(peaks.size - 1, (endMs / msPerBucket).toInt())
        if (first > last) return 0f

        var peak = 0f
        for (i in first..last) peak = max(peak, peaks[i])
        return peak
    }

    companion object {
        /**
         * About 5 ms per bucket at 48 kHz — fine enough that a kick drum reads as
         * a distinct spike, coarse enough that a five minute track is well under
         * a megabyte.
         */
        const val DEFAULT_FRAMES_PER_BUCKET = 256

        fun of(pcm: Pcm, framesPerBucket: Int = DEFAULT_FRAMES_PER_BUCKET): Waveform {
            val channels = pcm.channels
            val frames = pcm.frameCount
            val buckets = (frames + framesPerBucket - 1) / framesPerBucket
            val peaks = FloatArray(max(buckets, 0))

            var frame = 0
            var bucket = 0
            while (frame < frames) {
                val end = min(frame + framesPerBucket, frames)
                var peak = 0
                var i = frame * channels
                val stop = end * channels
                while (i < stop) {
                    val value = abs(pcm.samples[i].toInt())
                    if (value > peak) peak = value
                    i++
                }
                peaks[bucket++] = peak / 32768f
                frame = end
            }

            return Waveform(peaks, framesPerBucket, pcm.sampleRate, frames)
        }
    }
}
