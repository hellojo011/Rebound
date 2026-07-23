package dev.rebound.core.play

import kotlin.math.abs
import kotlin.math.floor

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
 * Side walls are handled by *unfolding*: the path is a straight line drawn in a
 * space where the field is tiled and mirrored, and folding that line back into
 * 0..1 turns it into a bouncing one. It costs one function and cannot drift or
 * tunnel the way stepwise reflection can.
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

    /**
     * Re-aims the shot at the object it turns out to be connected to.
     *
     * Called once the far side's next object is known, which is what sets the
     * flight time: the shot has to be in the air for exactly as long as the gap.
     *
     * Aiming and flicking can happen in either order, so a swing already applied
     * is re-applied to the new target rather than being flattened by it.
     */
    fun aimAt(landingX: Float, arriveAtMs: Double, landingY: Float = 0f) {
        this.landingX = landingX
        this.landingY = landingY
        this.endMs = arriveAtMs
        this.unfoldedEndX = landingX
        if (swungDirX != 0f) unfoldedEndX = unfoldedTarget(startX, landingX, swungDirX)
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
        dip = if (dirY > DOWNWARD_ENOUGH) DIP_DEPTH else 0f
        swungDirX = dirX
        unfoldedEndX = unfoldedTarget(startX, landingX, dirX)
    }

    private val durationMs: Double get() = (endMs - startMs).coerceAtLeast(1.0)

    fun progressAt(songMs: Double): Float =
        ((songMs - startMs) / durationMs).coerceIn(0.0, 1.0).toFloat()

    fun xAt(songMs: Double): Float =
        fold(startX + (unfoldedEndX - startX) * progressAt(songMs))

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

    /** How many walls the path meets on its way over. */
    val bounces: Int
        get() {
            val low = minOf(startX, unfoldedEndX).toDouble()
            val high = maxOf(startX, unfoldedEndX).toDouble()
            // Every whole-number boundary crossed in unfolded space is a wall.
            return (floor(high) - floor(low)).toInt()
        }

    companion object {
        /**
         * Folds unfolded space into the field.
         *
         * A triangle wave of period 2: 0..1 passes through, 1..2 comes back, and
         * so on outwards in both directions.
         */
        fun fold(value: Float): Float {
            var v = value % 2f
            if (v < 0f) v += 2f
            return if (v <= 1f) v else 2f - v
        }

        /**
         * Picks where to aim in unfolded space so the shot still lands on
         * [endX] having caromed the way the flick suggested.
         *
         * A flick sideways should visibly meet a wall, so the nearest candidate
         * in that direction is taken rather than the direct line. A flick that
         * was essentially straight over keeps the direct line.
         */
        fun unfoldedTarget(startX: Float, endX: Float, dirX: Float): Float {
            if (abs(dirX) < STRAIGHT_ENOUGH) return endX

            val candidates = (-2..2).flatMap { listOf(2f * it + endX, 2f * it - endX) }
            val chosen = if (dirX > 0f) {
                candidates.filter { it > startX + MIN_SWING }.minOrNull()
            } else {
                candidates.filter { it < startX - MIN_SWING }.maxOrNull()
            }
            return chosen ?: endX
        }

        /** Below this the flick counts as straight over rather than sideways. */
        private const val STRAIGHT_ENOUGH = 0.12f

        /** How far the aim must swing for a bounce to be worth drawing. */
        private const val MIN_SWING = 0.3f

        /** Above this the flick counts as downward rather than across. */
        private const val DOWNWARD_ENOUGH = 0.35f

        /** How far past the bar the dip reaches, in field heights. */
        private const val DIP_DEPTH = 0.085f

        /** The share of the flight spent going down before climbing away. */
        private const val DIP_FRACTION = 0.2f
    }
}
