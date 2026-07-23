package dev.rebound.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

/**
 * The track's waveform with a beat grid drawn over it.
 *
 * The whole point is the comparison: if the tempo and offset are right, the
 * measure lines sit on the transients. Getting that to read means drawing the
 * grid *over* the waveform in a colour that survives being on top of it, and
 * making measure lines clearly heavier than beats — otherwise a chart can be a
 * whole beat out and still look aligned.
 *
 * Time and x come from the shared [Timeline], so panning here moves the object
 * grid below by exactly the same amount.
 */
class WaveformView(context: Context, private val timeline: Timeline) : View(context) {

    var waveform: Waveform? = null
        set(value) {
            field = value
            invalidate()
        }

    var playheadMs: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }

    /** Called when the user taps to move the playhead. */
    var onSeek: ((Double) -> Unit)? = null

    /** Called when a drag pans the view, so the caller can stop auto-following. */
    var onUserPan: (() -> Unit)? = null

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3E6F9E")
    }
    private val waveQuietPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#21405C")
    }
    private val beatPaint = Paint().apply {
        color = Color.parseColor("#6E7F99")
        strokeWidth = 1f
    }
    private val measurePaint = Paint().apply {
        color = Color.parseColor("#D8E4F5")
        strokeWidth = 2f
    }
    private val measureTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A9BCD6")
        textSize = 10f * context.resources.displayMetrics.density
    }
    private val playheadPaint = Paint().apply {
        color = Color.parseColor("#FF6FA0")
        strokeWidth = 3f
    }
    private val centreLinePaint = Paint().apply {
        color = Color.parseColor("#1A2434")
        strokeWidth = 1f
    }
    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#54627A")
        textSize = 13f * context.resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                timeline.zoomBy(detector.scaleFactor, detector.focusX, width)
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val limit = waveform?.durationMs ?: 0.0
                onSeek?.invoke(timeline.timeAt(e.x).coerceIn(0.0, limit))
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                onUserPan?.invoke()
                timeline.scrollBy(distanceX, width)
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val wave = waveform
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f

        canvas.drawColor(Color.parseColor("#0A0F18"))

        if (wave == null) {
            canvas.drawText("Import an audio file to begin", w / 2f, mid, emptyTextPaint)
            return
        }

        canvas.drawLine(0f, mid, w, mid, centreLinePaint)
        drawWaveform(canvas, wave, w, mid)
        drawGrid(canvas, h)

        val playX = timeline.xAt(playheadMs)
        if (playX >= 0f && playX <= w) canvas.drawLine(playX, 0f, playX, h, playheadPaint)
    }

    private fun drawWaveform(canvas: Canvas, wave: Waveform, w: Float, mid: Float) {
        val msPerPixel = 1000.0 / timeline.pixelsPerSecond
        val maxHeight = mid * 0.88f

        var x = 0
        while (x < w.toInt()) {
            val start = timeline.timeAt(x.toFloat())
            val peak = wave.peakBetween(start, start + msPerPixel)
            if (peak > 0f) {
                val half = max(1f, peak * maxHeight)
                val paint = if (peak > 0.12f) wavePaint else waveQuietPaint
                canvas.drawRect(x.toFloat(), mid - half, x + 1f, mid + half, paint)
            }
            x++
        }
    }

    private fun drawGrid(canvas: Canvas, h: Float) {
        val beatMs = timeline.beatMs()
        val visibleEndMs = timeline.timeAt(width.toFloat())

        var beat = max(0L, timeline.beatIndexAt(timeline.scrollMs))
        // Below about six pixels apart, beat lines turn into a grey wash that
        // hides the waveform. Measures alone stay useful much further out.
        val drawBeats = beatMs / 1000.0 * timeline.pixelsPerSecond >= 6.0

        while (true) {
            val ms = timeline.timeOfBeat(beat)
            if (ms > visibleEndMs) break
            val x = timeline.xAt(ms)
            if (beat % BEATS_PER_MEASURE == 0L) {
                canvas.drawLine(x, 0f, x, h, measurePaint)
                canvas.drawText(
                    "${beat / BEATS_PER_MEASURE + 1}",
                    x + 4f,
                    measureTextPaint.textSize + 2f,
                    measureTextPaint,
                )
            } else if (drawBeats) {
                canvas.drawLine(x, h * 0.20f, x, h * 0.80f, beatPaint)
            }
            beat++
        }
    }

    private companion object {
        const val BEATS_PER_MEASURE = 4L
    }
}
