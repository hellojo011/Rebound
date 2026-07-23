package dev.rebound.core.play

import dev.rebound.core.chart.Note
import dev.rebound.core.chart.NoteType

/**
 * Drives one side of a match without a human on it.
 *
 * Its imperfection is deliberate and reproducible: every object gets a timing
 * error and a keep-or-drop decision hashed from its own index, so a given chart
 * played by a given skill always goes the same way. A CPU that varied run to run
 * would make it impossible to tell whether a change to the engine or a change in
 * the dice moved the result.
 */
class CpuPlayer(
    private val engine: PlayEngine,
    private val skill: Skill = Skill.NORMAL,
    private val seed: Int = 0,
) {

    enum class Skill(
        /** Largest timing error, in milliseconds either side of the beat. */
        val spreadMs: Double,
        /** Roughly how often an object is dropped outright, 0..1. */
        val missRate: Double,
    ) {
        EASY(80.0, 0.14),
        NORMAL(38.0, 0.05),
        HARD(16.0, 0.012),
        PERFECT(0.0, 0.0),
    }

    /** Objects already dealt with, so each is decided once and not re-pressed. */
    private val handled = HashSet<Int>()

    /**
     * Plays whatever is due at [songTimeMs].
     *
     * Call once a frame, before the engine's own update, so a press lands inside
     * its window rather than being pre-empted by the miss sweep.
     */
    fun update(songTimeMs: Double) {
        for (visible in engine.visibleNotes(songTimeMs, 0.0)) {
            val note = visible.note

            if (visible.isHeld) {
                if (note.endTimeMs <= songTimeMs) engine.release(note.index, note.endTimeMs)
                continue
            }
            if (note.index in handled) continue

            if (drops(note)) {
                // Decided to let this one go; mark it so it is not reconsidered.
                handled += note.index
                continue
            }

            val pressAt = note.timeMs + offsetFor(note)
            if (pressAt > songTimeMs) continue

            // Anything this far past its window is gone; pressing at its nominal
            // time would award a hit for an object that has already scrolled by.
            if (songTimeMs - pressAt > engine.windows.hitMs) {
                handled += note.index
                continue
            }

            handled += note.index
            val result = engine.press(note.x, note.y, pressAt) ?: continue

            // A CPU that never spent its gauge would sit on a full one all song.
            if (note.type == NoteType.GOLD) {
                val direction = if (note.index % 2 == 0) 0.5f else -0.5f
                engine.flick(note.index, direction, -1f, pressAt)
            }
        }
    }

    private fun drops(note: Note): Boolean =
        skill.missRate > 0.0 && unit(note.index, DROP_SALT) < skill.missRate

    private fun offsetFor(note: Note): Double =
        if (skill.spreadMs <= 0.0) 0.0 else (unit(note.index, TIMING_SALT) * 2.0 - 1.0) * skill.spreadMs

    /** A stable 0..1 value per object and purpose. */
    private fun unit(index: Int, salt: Int): Double {
        var h = (index * 0x9E3779B1.toInt()) xor (seed * 0x85EBCA77.toInt()) xor salt
        h = h xor (h ushr 15)
        h *= -0x7A143595
        h = h xor (h ushr 13)
        return ((h ushr 8) and 0xFFFF) / 65535.0
    }

    private companion object {
        const val TIMING_SALT = 0x51ED2701
        const val DROP_SALT = 0x2F1B3C4D
    }
}
