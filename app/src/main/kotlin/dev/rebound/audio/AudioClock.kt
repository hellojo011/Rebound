package dev.rebound.audio

import android.os.SystemClock

/**
 * The single source of truth for "where are we in the song".
 *
 * Song time is negative during the lead-in and crosses zero exactly as the first
 * sample is heard, so the count-in is not a separate mode the rest of the game
 * has to know about: objects approach, and the engine judges, against one
 * continuous timeline that simply starts before the music does.
 *
 * Two corrections live here, and both matter more than they look:
 *
 * 1. [userOffsetMs] absorbs whatever output latency the device has left
 *    unreported, calibrated by the player.
 * 2. [songTimeAtEvent] rewinds to the moment a touch *happened* rather than the
 *    moment the app got around to handling it. Input can sit in the queue for a
 *    frame or more, and judging against handling time would systematically
 *    punish the player for the system's delay.
 */
class AudioClock {

    /**
     * Player calibration in milliseconds. Raise it if hits consistently register
     * LATE, lower it if they register EARLY.
     */
    var userOffsetMs: Double = 0.0

    /** Uptime the song is due to begin at. Only meaningful while [leadingIn]. */
    private var songStartUptimeMs: Long = 0L

    /** Song position the current count-in is counting towards. */
    private var leadInTargetMs: Double = 0.0

    /** Length of the current count-in, for pacing what is drawn during it. */
    var leadInDurationMs: Double = 0.0
        private set

    @Volatile
    private var leadingIn: Boolean = false

    /**
     * Starts counting down to a point in the song.
     *
     * The caller is expected to actually begin playback at [resumeAtMs] once
     * [durationMs] has elapsed; until the audio engine reports a running song,
     * this countdown is what the clock reports.
     *
     * @param resumeAtMs where playback will pick up. Zero at the start of a run;
     *   after a pause it is where the player left off, so the count-in rewinds a
     *   little and runs back up to it rather than dropping them in cold.
     */
    fun startLeadIn(durationMs: Long, resumeAtMs: Double = 0.0) {
        songStartUptimeMs = SystemClock.uptimeMillis() + durationMs
        leadInDurationMs = durationMs.toDouble()
        leadInTargetMs = resumeAtMs
        leadingIn = true
    }

    /** True while the count-in is still running. */
    fun isLeadingIn(): Boolean = leadingIn

    /** Milliseconds left of the count-in, or zero when it is not running. */
    fun leadInRemainingMs(): Double =
        if (!leadingIn) {
            0.0
        } else {
            (songStartUptimeMs - SystemClock.uptimeMillis()).coerceAtLeast(0L).toDouble()
        }

    /** Song position being heard right now; negative before the song begins. */
    fun songTimeMs(): Double {
        if (leadingIn) {
            val remaining = songStartUptimeMs - SystemClock.uptimeMillis()
            if (remaining > 0L) return leadInTargetMs - remaining.toDouble() - userOffsetMs

            // The countdown has elapsed, but the stream needs a callback or two
            // to actually start. Hold at the target until the engine confirms
            // the song's first frame has been written.
            //
            // Asking the engine directly, rather than watching for the reported
            // position to become positive, matters: a position that comes back
            // wrong for any reason would otherwise strand the clock here with
            // the count-in over and the song apparently frozen.
            if (!NativeAudio.nativeIsSongStarted()) return leadInTargetMs - userOffsetMs

            // And keep holding until the audio clock has actually caught up.
            // It reports the frame leaving the speaker, which trails the frame
            // just written by the width of the output buffer, so for the first
            // few milliseconds of playback it is still behind the count-in.
            // Handing over then would step time *backwards* on the very frame
            // the first object lands -- brief, but a rhythm game is exactly the
            // place where a few milliseconds in the wrong direction is felt.
            //
            // Waiting instead freezes the clock for that moment, which is
            // shorter than a frame and, unlike a step back, monotonic.
            val position = NativeAudio.nativeSongPositionMs()
            if (position < leadInTargetMs) return leadInTargetMs - userOffsetMs
            leadingIn = false
        }
        return NativeAudio.nativeSongPositionMs() - userOffsetMs
    }

    /**
     * Song position at the instant an input event was generated.
     *
     * @param eventUptimeMs `MotionEvent.getEventTime()`, which is on the same
     *   [SystemClock.uptimeMillis] timebase we sample here.
     */
    fun songTimeAtEvent(eventUptimeMs: Long): Double {
        val queuedForMs = (SystemClock.uptimeMillis() - eventUptimeMs).coerceAtLeast(0L)
        return songTimeMs() - queuedForMs
    }
}
