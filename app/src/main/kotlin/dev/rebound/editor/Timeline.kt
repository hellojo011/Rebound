package dev.rebound.editor

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The shared horizontal time axis of the editor.
 *
 * The waveform and the object grid are two views of the same moment in the song
 * and must never disagree about where that moment is on screen, so the mapping
 * between time and x lives here rather than in either of them. Panning or
 * zooming one moves both because there is only one set of numbers.
 */
class Timeline {

    var pixelsPerSecond: Float = 120f
        private set

    var scrollMs: Double = 0.0
        private set

    var bpm: Double = 150.0
        set(value) {
            field = value.coerceIn(20.0, 400.0)
            notifyChanged()
        }

    /** Milliseconds from the first sample to the first beat of measure 1. */
    var offsetMs: Double = 0.0
        set(value) {
            field = value
            notifyChanged()
        }

    /** Steps per beat that placement snaps to: 1 beat, 2 eighths, 4 sixteenths… */
    var snapDivision: Int = 4
        set(value) {
            field = value.coerceIn(1, 16)
            notifyChanged()
        }

    /** Length of the loaded track, so scrolling can be bounded. */
    var durationMs: Double = 0.0

    /** Where the chart is declared to end, or zero when it is not set. */
    var endMs: Double = 0.0
        set(value) {
            field = value.coerceAtLeast(0.0)
            notifyChanged()
        }

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun notifyChanged() {
        listeners.forEach { it() }
    }

    // --- mapping ----------------------------------------------------------

    fun timeAt(x: Float): Double = scrollMs + x / pixelsPerSecond * 1000.0

    fun xAt(ms: Double): Float = ((ms - scrollMs) / 1000.0 * pixelsPerSecond).toFloat()

    fun beatMs(): Double = 60_000.0 / bpm

    fun stepMs(): Double = beatMs() / snapDivision

    /** The nearest snap position to [ms]. */
    fun snap(ms: Double): Double {
        val step = stepMs()
        val steps = ((ms - offsetMs) / step).roundToLong()
        return offsetMs + steps * step
    }

    /** Index of the first beat at or before [ms], for drawing grid lines. */
    fun beatIndexAt(ms: Double): Long = floor((ms - offsetMs) / beatMs()).toLong()

    fun timeOfBeat(beat: Long): Double = offsetMs + beat * beatMs()

    // --- navigation -------------------------------------------------------

    fun scrollBy(deltaPx: Float, viewWidth: Int) {
        scrollMs += deltaPx / pixelsPerSecond * 1000.0
        clamp(viewWidth)
        notifyChanged()
    }

    fun centreOn(ms: Double, viewWidth: Int) {
        scrollMs = ms - viewWidth / 2f / pixelsPerSecond * 1000.0
        clamp(viewWidth)
        notifyChanged()
    }

    /** Zooms about [focusX] so whatever is under the fingers stays under them. */
    fun zoomBy(factor: Float, focusX: Float, viewWidth: Int) {
        val focusMs = timeAt(focusX)
        pixelsPerSecond = (pixelsPerSecond * factor)
            .coerceIn(MIN_PIXELS_PER_SECOND, MAX_PIXELS_PER_SECOND)
        scrollMs = focusMs - focusX / pixelsPerSecond * 1000.0
        clamp(viewWidth)
        notifyChanged()
    }

    private fun clamp(viewWidth: Int) {
        // Half a screen of slack at each end, so the first and last beats can be
        // worked on instead of being pinned against the edge.
        val visibleMs = viewWidth / pixelsPerSecond * 1000.0
        scrollMs = scrollMs.coerceIn(-visibleMs / 2, durationMs + visibleMs / 2)
    }

    private companion object {
        const val MIN_PIXELS_PER_SECOND = 12f
        const val MAX_PIXELS_PER_SECOND = 2400f
    }
}
