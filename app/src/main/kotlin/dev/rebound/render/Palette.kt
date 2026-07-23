package dev.rebound.render

import dev.rebound.core.play.Judgment

/**
 * Colours that are *not* skinnable, as 0xRRGGBB.
 *
 * Judgment colours live here rather than in [Skin] on purpose: JUST reading as
 * cyan and MISS as red is information, not decoration, and a skin that recoloured
 * them would be changing what the player is being told. The rest is HUD chrome,
 * which sits above the field and stays constant so the readouts are legible
 * whatever the field is wearing.
 */
object Palette {

    const val CLEAR_GAUGE_TRACK = 0x14202F
    const val CLEAR_GAUGE_FILL = 0x35E8D2
    const val CLEAR_GAUGE_LOW = 0xFF5C6E

    const val HUD_FRAME = 0x9FB6D4
    const val HUD_WELL = 0x101A2A
    const val HUD_CHIP_ACTIVE = 0x0C3E6E

    fun judgment(judgment: Judgment): Int = when (judgment) {
        Judgment.JUST -> 0x6EF3FF
        Judgment.GREAT -> 0x9BFF8A
        Judgment.GOOD -> 0xFFE066
        Judgment.MISS -> 0xFF5C6E
        // Never shown as a popup, but flashes still ask for a colour.
        Judgment.KEEP -> 0xC8A6FF
    }
}
