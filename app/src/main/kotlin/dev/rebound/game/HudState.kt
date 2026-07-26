package dev.rebound.game

import dev.rebound.core.play.Judgment

/**
 * One verdict, floating over the object that earned it.
 *
 * @param x,y where the object was, as fractions of the screen.
 * @param deltaMs how far off the press was; not shown for a sustain tick.
 */
data class JudgmentPopup(
    val judgment: Judgment,
    val deltaMs: Double,
    val atNanos: Long,
    val x: Float,
    val y: Float,
)

/** Immutable snapshot handed from the GL thread to the HUD each frame. */
data class HudState(
    val title: String = "",
    val difficulty: String = "",
    val level: Int = 0,
    val score: Int = 0,
    val combo: Int = 0,
    val bestCombo: Int = 0,
    val clearGauge: Double = 0.0,
    /** Percentage needed to clear, shown alongside the result. */
    val clearThreshold: Double = 70.0,
    val just: Int = 0,
    val great: Int = 0,
    val good: Int = 0,
    val miss: Int = 0,
    /** Sustain ticks earned holding long objects. */
    val keep: Int = 0,
    val maxCombo: Int = 0,
    val lastJudgment: Judgment? = null,
    val lastDeltaMs: Double = 0.0,
    val lastJudgmentAtNanos: Long = 0L,
    /**
     * Verdicts still on screen, each over the object that earned it.
     *
     * A list rather than a single latest one: objects arrive together, and a
     * verdict that vanished the moment the next object was struck would leave a
     * chord telling the player about only one of its notes.
     */
    val popups: List<JudgmentPopup> = emptyList(),
    val opponentPopups: List<JudgmentPopup> = emptyList(),
    /** Set when a flick spent a gauge segment, for the JUST REFLEC callout. */
    val lastReflecAtNanos: Long = 0L,
    val opponentLastReflecAtNanos: Long = 0L,
    val opponentScore: Int = 0,
    val opponentCombo: Int = 0,
    val opponentJust: Int = 0,
    val opponentGreat: Int = 0,
    val opponentGood: Int = 0,
    val opponentMiss: Int = 0,
    val opponentLastJudgment: Judgment? = null,
    val opponentLastDeltaMs: Double = 0.0,
    val opponentLastJudgmentAtNanos: Long = 0L,
    /** Whether the far side is a CPU, for labelling its readout. */
    val opponentIsCpu: Boolean = true,
    val autoPlay: Boolean = false,
    val finished: Boolean = false,
    /** Whether the run would clear if it ended now. Only read on the result screen. */
    val cleared: Boolean = false,
    val songTimeMs: Double = 0.0,
    /**
     * Milliseconds left of a count-in, or zero when one is not running.
     *
     * Covers both the one at the start of a run and the shorter one after a
     * pause, which the HUD presents identically -- from the player's side they
     * are the same moment.
     */
    val leadInRemainingMs: Double = 0.0,
    /** How long the current count-in is in total, so the HUD can pace the prompt. */
    val leadInTotalMs: Double = 0.0,
    val paused: Boolean = false,
)
