package dev.rebound.render

import dev.rebound.core.FieldGeometry

/**
 * Maps gameplay to pixels.
 *
 * The field is a portrait rectangle bounded by two bars: the opponent's across
 * the top and the player's near the bottom. Objects live in the space between
 * them, crossing the player's bar at any point along its length -- there are no
 * lanes, so x is a continuous fraction of the width throughout.
 *
 * The rows that gameplay cares about come from [FieldGeometry] rather than being
 * chosen here, so a green object is drawn exactly where it is judged.
 */
class Playfield(
    val width: Float,
    val height: Float,
    /** Player preference: how large objects are drawn, 1 being the default. */
    objectScale: Float = 1f,
) {
    val opponentBarY: Float = height * 0.075f
    val playerBarY: Float = height * 0.900f

    /** Vertical distance an object covers on its way over. */
    val travel: Float = playerBarY - opponentBarY

    /** The row of circular tap points, mirrored on each side. */
    val playerTapPointY: Float = py(FieldGeometry.TAP_POINT_Y)
    val opponentTapPointY: Float = py(1f - FieldGeometry.TAP_POINT_Y)

    val opponentGaugeY: Float = height * 0.205f
    val playerGaugeY: Float = height * 0.790f

    val opponentScoreY: Float = height * 0.445f
    val playerScoreY: Float = height * 0.560f

    /**
     * Diameter of a standard object. Small enough that a busy measure stays
     * readable -- objects that crowd each other are the fastest way to make a
     * chart unfair.
     */
    val objectSize: Float = width * 0.095f * objectScale

    // The tap points are targets, not objects, so they keep their size whatever
    // the player scales the objects to.
    val tapPointSize: Float = width * 0.100f

    /** The three fixed tap points, as fractions of the width. */
    val tapPointXs: FloatArray = FieldGeometry.TAP_POINT_XS

    fun px(x: Float): Float = x * width

    /** Field-space y, where 0 is the opponent's bar and 1 the player's. */
    fun py(t: Float): Float = opponentBarY + travel * t

    /**
     * Where an object sits at [progress] along its approach, 0 at spawn and 1 at
     * its judgment point. Travel is a straight line, which is what gives the
     * field its criss-cross read.
     */
    fun objectX(spawnX: Float, landingX: Float, progress: Float): Float =
        px(spawnX + (landingX - spawnX) * progress.coerceIn(0f, 1f))
}
