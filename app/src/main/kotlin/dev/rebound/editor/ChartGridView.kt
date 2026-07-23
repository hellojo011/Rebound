package dev.rebound.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.rebound.core.FieldGeometry
import dev.rebound.core.chart.GridObject
import dev.rebound.core.chart.NoteType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class EditorTool(val label: String) {
    TAP("TAP"),
    GOLD("GOLD"),
    CHAIN("CHAIN"),
    LONG("LONG"),
    ERASE("ERASE"),
}

/**
 * The object grid, laid out the way the game actually works.
 *
 * Only the three circular tap points are fixed positions; everything that lands
 * on the bar lands somewhere random along it. So there is nothing for an author
 * to place horizontally, and a sixteen-column grid would be sixteen columns of
 * lie. Instead there is one lane per tap point and a handful of interchangeable
 * slots for bar objects -- the slot an object sits in decides nothing except
 * that it is a separate object, which is what lets a chord be written at all.
 *
 * The lane picks green or not; the tool picks tap, gold or long.
 */
class ChartGridView(
    context: Context,
    private val timeline: Timeline,
    private val chart: EditorChart,
) : View(context) {

    private class Lane(
        val label: String,
        /** Where this lane writes into the `.rbc` grid. */
        val column: Int,
        /** Set for the tap-point lanes, which can only hold green objects. */
        val forcedType: NoteType?,
    ) {
        val isTapPoint: Boolean get() = forcedType == NoteType.GREEN
    }

    var tool: EditorTool = EditorTool.TAP

    var playheadMs: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }

    /** Fired after any edit, so the caller can refresh its object count. */
    var onEdited: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density

    private val lanes: List<Lane> = buildLanes(chart.columns)

    private val lanePaint = Paint().apply { color = Color.parseColor("#0C121C") }
    private val laneAltPaint = Paint().apply { color = Color.parseColor("#101825") }
    private val tapPointLanePaint = Paint().apply { color = Color.parseColor("#0F241D") }
    private val laneLinePaint = Paint().apply {
        color = Color.parseColor("#182234")
        strokeWidth = 1f
    }
    private val laneLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5C6A80")
        textSize = 9f * context.resources.displayMetrics.density
    }
    private val snapPaint = Paint().apply {
        color = Color.parseColor("#1C2738")
        strokeWidth = 1f
    }
    private val beatPaint = Paint().apply {
        color = Color.parseColor("#3A4759")
        strokeWidth = 1f
    }
    private val measurePaint = Paint().apply {
        color = Color.parseColor("#D8E4F5")
        strokeWidth = 2f
    }
    private val playheadPaint = Paint().apply {
        color = Color.parseColor("#FF6FA0")
        strokeWidth = 3f
    }
    private val endPaint = Paint().apply {
        color = Color.parseColor("#FFD466")
        strokeWidth = 3f
    }
    private val pastEndPaint = Paint().apply { color = Color.parseColor("#33000000") }
    private val endLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD466")
        textSize = 9f * context.resources.displayMetrics.density
    }
    private val objectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80E05CFF")
    }
    private val objectRect = RectF()

    // In-progress long object, while a drag is under way.
    private var drawingLane = -1
    private var drawingStartMs = 0.0
    private var drawingEndMs = 0.0

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
                editAt(e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (tool == EditorTool.LONG) return true // handled as a draw
                timeline.scrollBy(distanceX, width)
                return true
            }
        },
    )

    /**
     * One lane per tap point, then a few interchangeable slots for bar objects.
     *
     * The tap-point lanes write into whichever grid column is nearest their
     * point, which is what the parser snaps on. The slots take distinct columns
     * only so that objects written at the same instant stay separate objects.
     */
    private fun buildLanes(columns: Int): List<Lane> {
        val tapPoints = FieldGeometry.TAP_POINT_XS.mapIndexed { index, x ->
            Lane(
                label = listOf("TOP L", "TOP C", "TOP R").getOrElse(index) { "TOP" },
                column = (x * columns - 0.5f).roundToInt().coerceIn(0, columns - 1),
                forcedType = NoteType.GREEN,
            )
        }

        val taken = tapPoints.map { it.column }.toSet()
        val free = (0 until columns).filter { it !in taken }
        val slots = (0 until SLOT_COUNT).map { slot ->
            // Spread across the free columns so nothing collides with a tap point.
            val column = free.getOrElse(slot * free.size / SLOT_COUNT) { slot }
            Lane("SLOT ${slot + 1}", column, forcedType = null)
        }

        return tapPoints + slots
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            cancelDrawing()
            return true
        }

        if (tool == EditorTool.LONG) handleLongDraw(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun handleLongDraw(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val lane = laneAt(event.y)
                // A tap point holds green objects, which have no length.
                drawingLane = if (lanes[lane].isTapPoint) -1 else lane
                drawingStartMs = timeline.snap(timeline.timeAt(event.x))
                drawingEndMs = drawingStartMs
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (drawingLane < 0) return
                drawingEndMs = timeline.snap(timeline.timeAt(event.x))
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                if (drawingLane < 0) return
                val start = min(drawingStartMs, drawingEndMs)
                val end = max(drawingStartMs, drawingEndMs)
                // A drag that never moved is a tap, and a zero-length hold is not
                // a thing; leave it to the tap handler.
                if (end - start >= timeline.stepMs() * 0.5) {
                    chart.place(
                        GridObject(start, lanes[drawingLane].column, NoteType.LONG, end),
                        tolerance(),
                    )
                    onEdited?.invoke()
                }
                cancelDrawing()
            }

            MotionEvent.ACTION_CANCEL -> cancelDrawing()
        }
    }

    private fun cancelDrawing() {
        drawingLane = -1
        invalidate()
    }

    private fun editAt(x: Float, y: Float) {
        val lane = lanes[laneAt(y)]
        val timeMs = timeline.snap(timeline.timeAt(x))

        if (tool == EditorTool.ERASE) {
            if (chart.removeAt(timeMs, lane.column, tolerance()) != null) onEdited?.invoke()
            invalidate()
            return
        }

        val type = lane.forcedType ?: when (tool) {
            EditorTool.TAP -> NoteType.TAP
            EditorTool.GOLD -> NoteType.GOLD
            EditorTool.CHAIN -> NoteType.CHAIN
            // A tap with the long tool is ambiguous, so treat it as an erase of
            // whatever is there rather than dropping a zero-length hold.
            EditorTool.LONG -> {
                if (chart.removeAt(timeMs, lane.column, tolerance()) != null) onEdited?.invoke()
                invalidate()
                return
            }
            EditorTool.ERASE -> return
        }

        // Tapping an identical object removes it, so the same gesture undoes itself.
        val existing = chart.findAt(timeMs, lane.column, tolerance())
        if (existing != null && existing.type == type && !existing.isLong) {
            chart.removeAt(timeMs, lane.column, tolerance())
        } else {
            chart.place(GridObject(timeMs, lane.column, type), tolerance())
        }
        onEdited?.invoke()
        invalidate()
    }

    /** Half a snap step, so a tap lands on the cell it looks like it landed on. */
    private fun tolerance(): Double = timeline.stepMs() * 0.49

    private fun laneAt(y: Float): Int {
        val laneHeight = height.toFloat() / lanes.size
        return (y / laneHeight).toInt().coerceIn(0, lanes.size - 1)
    }

    private fun laneIndexOfColumn(column: Int): Int =
        lanes.indexOfFirst { it.column == column }.takeIf { it >= 0 } ?: (lanes.size - 1)

    private fun laneTop(index: Int): Float = height.toFloat() / lanes.size * index

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val laneHeight = h / lanes.size

        drawLanes(canvas, w, laneHeight)
        drawGrid(canvas, h)
        drawObjects(canvas, laneHeight)
        drawInProgressLong(canvas, laneHeight)

        drawEndMarker(canvas, w, h)

        val playX = timeline.xAt(playheadMs)
        if (playX >= 0f && playX <= w) canvas.drawLine(playX, 0f, playX, h, playheadPaint)
    }

    private fun drawLanes(canvas: Canvas, w: Float, laneHeight: Float) {
        lanes.forEachIndexed { index, lane ->
            val top = laneHeight * index
            val paint = when {
                lane.isTapPoint -> tapPointLanePaint
                index % 2 == 0 -> lanePaint
                else -> laneAltPaint
            }
            canvas.drawRect(0f, top, w, top + laneHeight, paint)
            canvas.drawLine(0f, top, w, top, laneLinePaint)
            canvas.drawText(
                lane.label,
                4f * density,
                top + laneLabelPaint.textSize + 3f * density,
                laneLabelPaint,
            )
        }
    }

    private fun drawGrid(canvas: Canvas, h: Float) {
        val visibleEndMs = timeline.timeAt(width.toFloat())
        val beatMs = timeline.beatMs()
        val stepMs = timeline.stepMs()

        val drawSteps = stepMs / 1000.0 * timeline.pixelsPerSecond >= 7.0
        val drawBeats = beatMs / 1000.0 * timeline.pixelsPerSecond >= 6.0

        var beat = max(0L, timeline.beatIndexAt(timeline.scrollMs))
        while (true) {
            val beatTime = timeline.timeOfBeat(beat)
            if (beatTime > visibleEndMs) break

            if (drawSteps) {
                for (step in 1 until timeline.snapDivision) {
                    val x = timeline.xAt(beatTime + step * stepMs)
                    canvas.drawLine(x, 0f, x, h, snapPaint)
                }
            }

            val x = timeline.xAt(beatTime)
            if (beat % BEATS_PER_MEASURE == 0L) {
                canvas.drawLine(x, 0f, x, h, measurePaint)
            } else if (drawBeats) {
                canvas.drawLine(x, 0f, x, h, beatPaint)
            }
            beat++
        }
    }

    private fun drawObjects(canvas: Canvas, laneHeight: Float) {
        val startMs = timeline.scrollMs
        val endMs = timeline.timeAt(width.toFloat())
        val inset = laneHeight * 0.16f
        val minWidth = 6f * density

        chart.between(startMs, endMs).forEach { item ->
            objectPaint.color = colourFor(item.type)
            val left = timeline.xAt(item.timeMs)
            val right = if (item.isLong) timeline.xAt(item.endTimeMs) else left
            val top = laneTop(laneIndexOfColumn(item.column)) + inset

            objectRect.set(
                left - minWidth / 2f,
                top,
                max(right + minWidth / 2f, left + minWidth),
                top + laneHeight - inset * 2f,
            )
            val radius = laneHeight * 0.22f
            canvas.drawRoundRect(objectRect, radius, radius, objectPaint)
        }
    }

    private fun drawInProgressLong(canvas: Canvas, laneHeight: Float) {
        if (drawingLane < 0) return
        val left = timeline.xAt(min(drawingStartMs, drawingEndMs))
        val right = timeline.xAt(max(drawingStartMs, drawingEndMs))
        val top = laneTop(drawingLane) + laneHeight * 0.16f
        objectRect.set(left, top, max(right, left + 6f * density), top + laneHeight * 0.68f)
        canvas.drawRoundRect(objectRect, laneHeight * 0.22f, laneHeight * 0.22f, ghostPaint)
    }

    /** Where the chart is declared to be over. Everything past it is dead air. */
    private fun drawEndMarker(canvas: Canvas, w: Float, h: Float) {
        if (timeline.endMs <= 0.0) return
        val x = timeline.xAt(timeline.endMs)
        if (x < -w || x > w * 2f) return

        canvas.drawRect(x, 0f, max(x, w), h, pastEndPaint)
        canvas.drawLine(x, 0f, x, h, endPaint)
        canvas.drawText("END", x + 4f * density, endLabelPaint.textSize + 3f * density, endLabelPaint)
    }

    private fun colourFor(type: NoteType): Int = when (type) {
        NoteType.TAP -> Color.parseColor("#FF4D8D")
        NoteType.GOLD -> Color.parseColor("#FFC93C")
        NoteType.GREEN -> Color.parseColor("#4CE88B")
        NoteType.CHAIN -> Color.parseColor("#FF8AC4")
        NoteType.LONG -> Color.parseColor("#E05CFF")
    }

    private companion object {
        const val BEATS_PER_MEASURE = 4L

        /** Enough to write a four-object chord, which is as dense as it gets. */
        const val SLOT_COUNT = 4
    }
}
