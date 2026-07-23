package dev.rebound.render

/**
 * The colours of the playfield, as 0xRRGGBB.
 *
 * Skinnable because the field is the part players stare at for hours. Judgment
 * colours are deliberately *not* in here -- JUST being cyan and MISS being red
 * carries meaning, and a skin that recoloured them would be changing information
 * rather than decoration.
 *
 * Every skin keeps the same convention: the player's side is one hue and the
 * opponent's is its opposite, so a glance at an object still says whose it is
 * and which way it is going.
 */
data class Skin(
    val id: String,
    val label: String,

    val background: Int,
    val field: Int,
    val fieldEdge: Int,

    val playerBar: Int,
    val playerBarGlow: Int,
    val opponentBar: Int,
    val opponentBarGlow: Int,

    // Objects are coloured by which way they are travelling, not by whose they
    // are: on a shared field the only thing a glance needs to answer is "is this
    // one coming at me". Warm falls towards the near player, cool rises away.
    val objectTap: Int,
    val objectTapCore: Int,
    val objectTapFar: Int,
    val objectTapFarCore: Int,
    val objectGold: Int,
    val objectGoldCore: Int,
    val objectLong: Int,
    val objectLongBody: Int,
    val objectLongFar: Int,
    val objectLongFarBody: Int,
    val objectGreen: Int,
    val objectGreenCore: Int,

    val shotPlain: Int,
    val shotPowered: Int,
    val shotPoweredCore: Int,

    val tapPointRing: Int,
    val tapPointPlayer: Int,
    val tapPointOpponent: Int,

    val gaugeFrame: Int,
    val gaugeEmpty: Int,
    val gaugeFill: Int,
    val gaugePartial: Int,
)

object Skins {

    /** The default: warm player side, cool opponent side, near-black field. */
    val CLASSIC = Skin(
        id = "classic",
        label = "CLASSIC",
        background = 0x03050A,
        field = 0x070A12,
        fieldEdge = 0x123258,
        playerBar = 0xFF2D55,
        playerBarGlow = 0x7A1030,
        opponentBar = 0x2E9BFF,
        opponentBarGlow = 0x0C3E6E,
        objectTap = 0xFF4D8D,
        objectTapCore = 0xFFD9E8,
        objectTapFar = 0x4FC8FF,
        objectTapFarCore = 0xDCF3FF,
        objectGold = 0xFFC93C,
        objectGoldCore = 0xFFF3C4,
        objectLong = 0xFF5FA8,
        objectLongBody = 0x8C1F4E,
        objectLongFar = 0x5FC0FF,
        objectLongFarBody = 0x1B4B78,
        objectGreen = 0x4CE88B,
        objectGreenCore = 0xDFFFE9,
        shotPlain = 0xB88CD8,
        shotPowered = 0x4FD0FF,
        shotPoweredCore = 0xE6FAFF,
        tapPointRing = 0x39D98A,
        tapPointPlayer = 0xFF5570,
        tapPointOpponent = 0x4FA8FF,
        gaugeFrame = 0x9FB6D4,
        gaugeEmpty = 0x101A2A,
        gaugeFill = 0xFF3D77,
        gaugePartial = 0x8C2447,
    )

    /** Cooler and flatter, for players who find the default too loud. */
    val AZURE = Skin(
        id = "azure",
        label = "AZURE",
        background = 0x02060B,
        field = 0x061019,
        fieldEdge = 0x0E4460,
        playerBar = 0x36E0C8,
        playerBarGlow = 0x0C4F49,
        opponentBar = 0x6D7BFF,
        opponentBarGlow = 0x1E2464,
        objectTap = 0x4FD9E8,
        objectTapCore = 0xDDFBFF,
        objectTapFar = 0x9B8CFF,
        objectTapFarCore = 0xE7E1FF,
        objectGold = 0xFFD766,
        objectGoldCore = 0xFFF6DA,
        objectLong = 0x59D0C4,
        objectLongBody = 0x11524C,
        objectLongFar = 0x8E9BFF,
        objectLongFarBody = 0x2A2F6E,
        objectGreen = 0x8CF0A8,
        objectGreenCore = 0xE7FFEE,
        shotPlain = 0x7E9BC4,
        shotPowered = 0x7CFFE4,
        shotPoweredCore = 0xEBFFFA,
        tapPointRing = 0x2FC8A4,
        tapPointPlayer = 0x36E0C8,
        tapPointOpponent = 0x6D7BFF,
        gaugeFrame = 0x8FB2C8,
        gaugeEmpty = 0x0B1620,
        gaugeFill = 0x2FD3BE,
        gaugePartial = 0x13564F,
    )

    /** High contrast, very little colour. Easiest to read on a dim screen. */
    val MONO = Skin(
        id = "mono",
        label = "MONO",
        background = 0x000000,
        field = 0x08090B,
        fieldEdge = 0x30343C,
        playerBar = 0xFFFFFF,
        playerBarGlow = 0x4A4E56,
        opponentBar = 0x8A9099,
        opponentBarGlow = 0x2A2E34,
        objectTap = 0xF2F4F8,
        objectTapCore = 0x9AA0AA,
        objectTapFar = 0x7C838F,
        objectTapFarCore = 0xC9CED6,
        objectGold = 0xFFD24A,
        objectGoldCore = 0xFFF0BE,
        objectLong = 0xC8CEDA,
        objectLongBody = 0x40454E,
        objectLongFar = 0x767C86,
        objectLongFarBody = 0x2A2E34,
        objectGreen = 0x6BE58F,
        objectGreenCore = 0xDFFFE9,
        shotPlain = 0x767C86,
        shotPowered = 0xFFFFFF,
        shotPoweredCore = 0xFFFFFF,
        tapPointRing = 0x9AA0AA,
        tapPointPlayer = 0xFFFFFF,
        tapPointOpponent = 0x8A9099,
        gaugeFrame = 0xB4BAC4,
        gaugeEmpty = 0x14161A,
        gaugeFill = 0xFFFFFF,
        gaugePartial = 0x585E68,
    )

    val ALL: List<Skin> = listOf(CLASSIC, AZURE, MONO)

    fun byId(id: String?): Skin = ALL.firstOrNull { it.id == id } ?: CLASSIC
}
