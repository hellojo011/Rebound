package dev.rebound.game

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.SparseArray
import android.view.MotionEvent
import dev.rebound.audio.AudioClock
import dev.rebound.core.FieldGeometry
import kotlin.math.hypot

/**
 * The playfield surface, and the only place touches enter the game.
 *
 * Two things it has to get right. Each pointer is tracked independently, so a
 * chord across the bar registers as separate presses rather than one coalesced
 * touch. And a finger that keeps moving after it lands is reported as a flick,
 * which is what turns a gold object into a JUST REFLEC -- the press and the
 * flick are two separate inputs on the same finger.
 */
@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    private val renderer: GameRenderer,
    private val clock: AudioClock,
    /** With a second person on the far side, the top half is theirs. */
    private val twoPlayer: Boolean = false,
) : GLSurfaceView(context) {

    private class Pointer(
        val downX: Float,
        val downY: Float,
        val downUptimeMs: Long,
        val side: MatchSide,
    ) {
        var flicked = false
    }

    private val pointers = SparseArray<Pointer>()

    /**
     * Two fingers drawn together near the middle of the field, the original's
     * gesture for opening the pause menu.
     */
    var onPauseGesture: (() -> Unit)? = null

    private var pinchStartSpan = 0f
    private var watchingPinch = false

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return true
        val songMs = clock.songTimeAtEvent(event.eventTime)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) startWatchingPinch(event)
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)

                // In a two-player match the screen is split down the middle and
                // the far half belongs to the person facing you; their field is
                // this one rotated half a turn, so both axes flip.
                val side = if (twoPlayer && y < height / 2f) {
                    MatchSide.OPPONENT
                } else {
                    MatchSide.PLAYER
                }

                pointers.put(id, Pointer(x, y, event.eventTime, side))
                renderer.postTouch(
                    TouchAction.Down(
                        id,
                        side.map(x / width),
                        side.map(fieldY(y)),
                        side,
                        songMs,
                    ),
                )
            }

            MotionEvent.ACTION_MOVE -> {
                detectPinch(event)
                detectFlicks(event, songMs)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                watchingPinch = false
                val id = event.getPointerId(event.actionIndex)
                pointers.remove(id)
                renderer.postTouch(TouchAction.Up(id, songMs))
            }

            MotionEvent.ACTION_CANCEL -> {
                watchingPinch = false
                for (i in 0 until pointers.size()) {
                    renderer.postTouch(TouchAction.Up(pointers.keyAt(i), songMs))
                }
                pointers.clear()
            }
        }
        return true
    }

    /**
     * Arms pinch detection, but only for two fingers landing around the middle
     * of the field.
     *
     * Restricting it to the centre is what keeps it clear of play: objects are
     * struck down at the bar, so a two-finger gesture centred in the empty
     * middle of the field is not something a chart ever asks for.
     */
    private fun startWatchingPinch(event: MotionEvent) {
        val midX = (event.getX(0) + event.getX(1)) / 2f
        val midY = (event.getY(0) + event.getY(1)) / 2f
        watchingPinch = midX in width * 0.2f..width * 0.8f &&
            midY in height * 0.25f..height * 0.75f
        pinchStartSpan = span(event)
    }

    /**
     * Fires once the fingers have been drawn decisively together.
     *
     * Inwards only, matching the original: spreading two fingers apart is close
     * enough to reaching for two objects at once that it would eventually pause
     * someone mid-chart. The threshold is generous because two fingers landing
     * on two simultaneous objects barely change their separation at all.
     */
    private fun detectPinch(event: MotionEvent) {
        if (!watchingPinch || event.pointerCount != 2 || pinchStartSpan < 1f) return

        if (span(event) / pinchStartSpan <= PINCH_IN_RATIO) {
            watchingPinch = false
            onPauseGesture?.invoke()
        }
    }

    private fun span(event: MotionEvent): Float =
        hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    /**
     * Converts a pixel y into field space, 0 at the opponent's bar and 1 at the
     * player's.
     *
     * Clamped at both ends, so a thumb resting below the bar -- which is most of
     * them -- still reads as being on the bar rather than past it.
     */
    private fun fieldY(pixelY: Float): Float {
        val field = renderer.playfield() ?: return FieldGeometry.BAR_Y
        return ((pixelY - field.opponentBarY) / field.travel).coerceIn(0f, FieldGeometry.BAR_Y)
    }

    private fun detectFlicks(event: MotionEvent, songMs: Double) {
        val threshold = width * FLICK_DISTANCE_FRACTION

        for (index in 0 until event.pointerCount) {
            val id = event.getPointerId(index)
            val pointer = pointers.get(id) ?: continue
            if (pointer.flicked) continue

            // Only movement soon after the press counts; a finger resting on a
            // long object and drifting is not a flick.
            if (event.eventTime - pointer.downUptimeMs > FLICK_WINDOW_MS) continue

            val dx = event.getX(index) - pointer.downX
            val dy = event.getY(index) - pointer.downY
            if (hypot(dx, dy) < threshold) continue

            pointer.flicked = true
            // Divide through by the field's dimensions so the shot travels the way
            // the finger moved on screen, not skewed by the field's aspect ratio.
            // A direction is negated rather than mirrored about 0.5, since the far
            // side's whole field is turned around.
            val sign = if (pointer.side.mirrored) -1f else 1f
            renderer.postTouch(
                TouchAction.Flick(id, sign * dx / width, sign * dy / height, songMs),
            )
        }
    }

    private companion object {
        const val FLICK_DISTANCE_FRACTION = 0.045f
        const val FLICK_WINDOW_MS = 220L

        /** How far the fingers must close before it counts as a pause gesture. */
        const val PINCH_IN_RATIO = 0.65f
    }
}
