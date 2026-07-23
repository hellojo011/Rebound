package dev.rebound.game

/**
 * Which half of the table a touch or a readout belongs to.
 *
 * [OPPONENT] is drawn as [PLAYER] rotated by half a turn -- both axes flipped --
 * because that is literally what the other person is looking at across a tablet
 * lying between them. Their left is the player's right.
 */
enum class MatchSide {
    PLAYER,
    OPPONENT,
    ;

    val mirrored: Boolean get() = this == OPPONENT

    /** Maps a field coordinate into screen-space field coordinates. */
    fun map(value: Float): Float = if (mirrored) 1f - value else value
}

/** Who is playing the far side. */
enum class OpponentControl {
    /** Nobody: single player, and the top half is a CPU. */
    CPU,

    /** A second person, touching the top half of the same screen. */
    HUMAN,
}
