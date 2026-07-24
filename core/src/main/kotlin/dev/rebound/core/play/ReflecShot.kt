package dev.rebound.core.play

import dev.rebound.core.FieldGeometry
import kotlin.math.abs

/**
 * An object crossing back to the far side after being struck.
 *
 * Driven by time rather than by velocity: a shot is created already knowing when
 * and where it must arrive, and its position is a function of the song clock.
 * That is what a rally needs -- the gold object you strike *is* the opponent's
 * next object arriving, so the flight has to fill exactly the gap between the
 * two, however long that gap happens to be. A fixed speed could not.
 *
 * Coordinates are in the striking side's field space: x runs 0 (left wall) to 1
 * (right wall), y runs 1 (the striker's bar) to 0 (the far bar).
 *
 * Side walls are handled by [FieldGeometry.fold]: the path is a straight line
 * drawn in unfolded space, and folding it back into 0..1 turns it into a
 * bouncing one. How many walls it meets is chosen rather than fallen into --
 * see [sideBounces].
 */
class ReflecShot(
    val id: Int,
    val startX: Float,
    val startMs: Double,
    landingX: Float,
    endMs: Double,
    /** Set when a gauge segment was spent to power the shot. */
    var powered: Boolean = false,
) {
    /** Where it actually lands across the field. */
    var landingX: Float = landingX
        private set

    /**
     * How far it travels, in the striker's field space.
     *
     * Zero is the far bar, which is where most objects are judged -- but a green
     * object is judged over a tap point, short of the bar, and the shot that
     * *is* that object has to stop there rather than carrying on past it.
     */
    var landingY: Float = 0f
        private set

    var endMs: Double = endMs
        private set

    /** Target in unfolded space; folding it gives [landingX]. */
    var unfoldedEndX: Float = landingX
        private set

    /** The flick this was sent round with, kept so re-aiming does not undo it. */
    private var swungDirX: Float = 0f
    private var swungDirY: Float = 0f
    private var swung: Boolean = false

    /**
     * Re-aims the shot at the object it turns out to be connected to.
     *
     * Called once the far side's object is known, which is what sets the flight
     * time: the shot has to be in the air for exactly as long as the gap.
     *
     * Aiming and flicking can happen in either order, so a swing already applied
     * is re-applied to the new target rather than being flattened by it -- and
     * the new target may change how far it travels, which changes the bounce
     * budget along with it.
     */
    fun aimAt(landingX: Float, arriveAtMs: Double, landingY: Float = 0f) {
        this.landingX = landingX
        this.landingY = landingY
        this.endMs = arriveAtMs
        recompute()
    }

    /**
     * How far below the striker's bar the path dips before climbing away.
     *
     * Zero unless it was flicked downward, which sends it into the floor first
     * and off it again -- the whole point of being able to flick down.
     */
    var dip: Float = 0f
        private set

    /** Sends it round by the walls and, if flicked down, off the floor. */
    fun swing(dirX: Float, dirY: Float = 0f) {
        swungDirX = dirX
        swungDirY = dirY
        swung = true
        recompute()
    }

    /**
     * How many side walls this shot is aimed to meet.
     *
     * A reflec is allowed three bounces in all, and which walls they land on is
     * read off the flick: sent straight over it takes none, sent sideways it
     * zigzags off both walls, and sent downward it spends one on the floor and
     * still zigzags off the other two.
     *
     * A flight that stops short of the far bar -- one becoming a green object,
     * judged up at a tap point -- gets half the field to do it in, so it is
     * allowed one side wall rather than two. Squeezing the full zigzag into that
     * distance reads as a stutter, not as a carom.
     */
    val sideBounces: Int
        get() = FieldGeometry.wallCrossings(startX, unfoldedEndX)

    private fun recompute() {
        if (!swung) {
            dip = 0f
            unfoldedEndX = landingX
            return
        }

        val goingDown = swungDirY > DOWNWARD_ENOUGH
        dip = if (goingDown) DIP_DEPTH else 0f

        // A short flight has less field to spend, so it carries a smaller budget.
        val budget = if (landingY > SHORT_FLIGHT_Y) SHORT_SIDE_BOUNCES else SIDE_BOUNCES
        val wanted = if (abs(swungDirX) < STRAIGHT_ENOUGH) 0 else budget
        unfoldedEndX = unfoldedTarget(startX, landingX, swungDirX, wanted)
    }

    private val durationMs: Double get() = (endMs - startMs).coerceAtLeast(1.0)

    fun progressAt(songMs: Double): Float =
        ((songMs - startMs) / durationMs).coerceIn(0.0, 1.0).toFloat()

    fun xAt(songMs: Double): Float =
        FieldGeometry.fold(startX + (unfoldedEndX - startX) * progressAt(songMs))

    /** 1 at the striker's bar, [landingY] where the object it becomes is judged. */
    fun yAt(songMs: Double): Float {
        val t = progressAt(songMs)
        if (dip <= 0f) return 1f + (landingY - 1f) * t

        // Down into the floor over the first slice of the flight, then away.
        val floor = 1f + dip
        return if (t < DIP_FRACTION) {
            1f + dip * (t / DIP_FRACTION)
        } else {
            val climb = (t - DIP_FRACTION) / (1f - DIP_FRACTION)
            floor + (landingY - floor) * climb
        }
    }

    fun hasArrived(songMs: Double): Boolean = songMs >= endMs

    /** Every wall the path meets, the floor included. */
    val bounces: Int
        get() = sideBounces + if (dip > 0f) 1 else 0

    companion object {
        /**
         * Picks where to aim in unfolded space so the shot lands on [endX] having
         * met exactly [wanted] side walls on the way.
         *
         * Only values of the form `2k ± endX` fold back onto [endX], so the
         * candidates are enumerated and the one with the right number of
         * crossings, on the side the flick pointed, is taken. If the exact count
         * cannot be had the next fewest is used -- a shot that bounces once when
         * it wanted to bounce twice still reads as a carom, where one that
         * silently flew straight would not.
         */
        fun unfoldedTarget(startX: Float, endX: Float, dirX: Float, wanted: Int): Float {
            if (wanted <= 0) return endX

            val candidates = (-3..3).flatMap { listOf(2f * it + endX, 2f * it - endX) }
            val forward = if (dirX > 0f) {
                candidates.filter { it > startX }.sorted()
            } else {
                candidates.filter { it < startX }.sortedDescending()
            }

            for (count in wanted downTo 1) {
                forward.firstOrNull { FieldGeometry.wallCrossings(startX, it) == count }
                    ?.let { return it }
            }
            return endX
        }

        /** Below this the flick counts as straight over rather than sideways. */
        const val STRAIGHT_ENOUGH = 0.12f

        /** Above this the flick counts as downward rather than across. */
        const val DOWNWARD_ENOUGH = 0.35f

        /** Side walls a full-length reflec zigzags off. */
        const val SIDE_BOUNCES = 2

        /** Side walls a flight that stops short of the bar gets instead. */
        const val SHORT_SIDE_BOUNCES = 1

        /** Past this landing height the flight counts as short. */
        const val SHORT_FLIGHT_Y = 0.05f

        /** How far past the bar the dip reaches, in field heights. */
        private const val DIP_DEPTH = 0.085f

        /** The share of the flight spent going down before climbing away. */
        private const val DIP_FRACTION = 0.2f
    }
}
