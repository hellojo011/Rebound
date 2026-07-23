package dev.rebound.game

/**
 * A touch, already converted to song time on the UI thread.
 *
 * The conversion happens where the event arrives rather than where the GL thread
 * gets to it, so however long the event waits in the queue, the timestamp it
 * carries still refers to when the finger actually moved.
 */
sealed interface TouchAction {
    val pointerId: Int
    val songTimeMs: Double

    /**
     * @param x position across the field, 0 at the left wall and 1 at the right.
     * @param y position down the field, 0 at the opponent's bar and 1 at the
     *   player's. Needed because green objects are judged on the tap point row
     *   rather than at the bar, so where the finger lands vertically decides
     *   which of two stacked objects it is reaching for.
     */
    data class Down(
        override val pointerId: Int,
        val x: Float,
        val y: Float,
        /** Already converted into that side's own field space. */
        val side: MatchSide,
        override val songTimeMs: Double,
    ) : TouchAction

    /**
     * The finger was thrown in a direction shortly after landing.
     *
     * Direction is in field space -- already divided through by the field's width
     * and height -- so a shot launched along it travels the way the finger moved
     * on screen rather than being skewed by the field's aspect ratio.
     */
    data class Flick(
        override val pointerId: Int,
        val dirX: Float,
        val dirY: Float,
        override val songTimeMs: Double,
    ) : TouchAction

    data class Up(
        override val pointerId: Int,
        override val songTimeMs: Double,
    ) : TouchAction
}
