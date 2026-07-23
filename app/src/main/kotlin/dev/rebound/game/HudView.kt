package dev.rebound.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import dev.rebound.core.play.Judgment
import dev.rebound.render.Palette
import kotlin.math.abs

/**
 * Scores, combo and judgment feedback, drawn with Canvas on top of the GL surface.
 *
 * Text is the one thing OpenGL makes disproportionately expensive -- a font
 * atlas and glyph layout is a lot of machinery for a handful of labels. Letting
 * the platform draw them keeps that complexity out of the project entirely.
 *
 * Positions are expressed as the same fractions of height the playfield uses, so
 * the two layers stay registered with each other.
 */
class HudView(context: Context) : View(context) {

    @Volatile
    private var state: HudState = HudState()

    /**
     * Tapped on the AUTO chip. The chip sits over the field, so the view swallows
     * that touch rather than letting it fall through as an object press.
     */
    var onToggleAutoPlay: (() -> Unit)? = null

    /** Tapped the result screen, i.e. "done, take me back". */
    var onDismissResult: (() -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val labelPaint = paint(11f, Color.parseColor("#6E82A0"))
    private val titlePaint = paint(14f, Color.parseColor("#C6D8EE"), bold = true)
    // The scores sit in the middle of the field, where objects travel. Holding
    // them well short of opaque keeps an object readable as it passes behind.
    private val opponentScorePaint = paint(44f, Color.parseColor("#3FA9C9"), bold = true)
        .apply { alpha = 150 }
    private val playerScorePaint = paint(44f, Color.parseColor("#FF6FA0"), bold = true)
        .apply { alpha = 170 }
    private val comboPaint = paint(30f, Color.parseColor("#FFFFFF"), bold = true)
    private val comboLabelPaint = paint(11f, Color.parseColor("#8FA3BF"))
    private val judgmentPaint = paint(26f, Color.WHITE, bold = true)
    private val deltaPaint = paint(12f, Color.parseColor("#9FB3CC"))
    private val reflecPaint = paint(20f, Color.parseColor("#7FE4FF"), bold = true)
    private val statPaint = paint(12f, Color.parseColor("#8FA3BF"))
    private val clearLabelPaint = paint(9f, Color.parseColor("#9FB6D4"))
    private val clearValuePaint = paint(15f, Color.WHITE, bold = true)
    private val resultPaint = paint(24f, Color.WHITE, bold = true)
    private val readyPaint = paint(30f, Color.parseColor("#FF8FB4"), bold = true)

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxRect = RectF()
    private val chipRect = RectF()
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipTextPaint = paint(11f, Color.parseColor("#C8D8EE"), bold = true)

    init {
        isClickable = false
        isFocusable = false
    }

    fun submit(newState: HudState) {
        state = newState
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false

        // While the pause menu is up it owns every touch; the HUD must not treat
        // a tap on RESUME as a tap on the AUTO chip underneath it.
        if (state.paused) return false

        // The result overlay covers the field, so it takes the whole screen --
        // otherwise a tap meant to dismiss it would fall through as an object press.
        if (state.finished) {
            onDismissResult?.invoke()
            return true
        }

        if (!chipRect.contains(event.x, event.y)) return false
        onToggleAutoPlay?.invoke()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val s = state
        val w = width.toFloat()
        val h = height.toFloat()

        drawHeader(canvas, s, w)
        drawScores(canvas, s, w, h)
        // Against another person there is no gauge to clear -- the higher score
        // wins, and a pass mark would just be noise on the field.
        if (s.opponentIsCpu) drawClearGauge(canvas, s, w, h)
        drawCombo(canvas, s, w, h)
        drawReflecCallout(canvas, s, w, h)
        drawJudgment(canvas, s, w, h)
        drawStats(canvas, s, h)
        drawAutoChip(canvas, s, w)
        if (s.leadInRemainingMs > 0.0) drawReadyPrompt(canvas, s, w, h)
        if (s.finished) drawResult(canvas, s, w, h)
    }

    /**
     * The count-in prompt.
     *
     * It occupies only the first half of the count-in and then clears, leaving
     * the rest of it for the opening objects to fly in against an empty field.
     * The lead-in is not only for show: the first object needs the length of an
     * approach before its beat, so without one it would appear already on the bar.
     *
     * Using a fraction rather than a fixed duration means the shorter count-in
     * after a pause is paced the same way without a second set of numbers.
     */
    private fun drawReadyPrompt(canvas: Canvas, s: HudState, w: Float, h: Float) {
        val total = s.leadInTotalMs
        if (total <= 0.0) return

        val promptWindow = total * PROMPT_FRACTION
        val elapsed = total - s.leadInRemainingMs
        if (elapsed > promptWindow) return

        val fade = ((promptWindow - elapsed) / PROMPT_FADE_MS).coerceIn(0.0, 1.0).toFloat()

        readyPaint.textAlign = Paint.Align.CENTER
        readyPaint.alpha = (fade * 255).toInt().coerceIn(0, 255)
        canvas.drawText("ARE YOU READY?", w / 2f, h * 0.50f, readyPaint)
        readyPaint.textAlign = Paint.Align.LEFT
        readyPaint.alpha = 255
    }

    /** Above the opponent's bar, in the strip the field leaves empty. */
    private fun drawHeader(canvas: Canvas, s: HudState, w: Float) {
        canvas.drawText(s.title, dp(14f), dp(22f), titlePaint)
        val subtitle = buildString {
            append(s.difficulty)
            if (s.level > 0) append("  Lv.${s.level}")
        }
        canvas.drawText(subtitle, dp(14f), dp(38f), labelPaint)
    }

    private fun drawScores(canvas: Canvas, s: HudState, w: Float, h: Float) {
        opponentScorePaint.textAlign = Paint.Align.CENTER
        playerScorePaint.textAlign = Paint.Align.CENTER
        labelPaint.textAlign = Paint.Align.CENTER

        // The far side's readout is drawn upside down: whoever it belongs to is
        // sitting on the other side of the tablet.
        canvas.save()
        canvas.rotate(180f, w / 2f, h * 0.435f)
        canvas.drawText(s.opponentScore.toString(), w / 2f, h * 0.435f, opponentScorePaint)
        canvas.drawText(
            if (s.opponentIsCpu) "CPU" else "2P",
            w / 2f, h * 0.435f + dp(16f), labelPaint,
        )
        canvas.restore()

        canvas.drawText(s.score.toString(), w / 2f, h * 0.560f, playerScorePaint)

        opponentScorePaint.textAlign = Paint.Align.LEFT
        playerScorePaint.textAlign = Paint.Align.LEFT
        labelPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawClearGauge(canvas: Canvas, s: HudState, w: Float, h: Float) {
        val boxWidth = w * 0.30f
        val boxHeight = h * 0.038f
        val right = w - dp(14f)
        val top = h * 0.525f
        boxRect.set(right - boxWidth, top, right, top + boxHeight)

        boxPaint.color = Palette.CLEAR_GAUGE_TRACK or OPAQUE
        canvas.drawRoundRect(boxRect, boxHeight * 0.4f, boxHeight * 0.4f, boxPaint)
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = dp(1f)
        boxPaint.color = Palette.HUD_FRAME or OPAQUE
        canvas.drawRoundRect(boxRect, boxHeight * 0.4f, boxHeight * 0.4f, boxPaint)
        boxPaint.style = Paint.Style.FILL

        clearLabelPaint.textAlign = Paint.Align.CENTER
        clearValuePaint.textAlign = Paint.Align.CENTER
        canvas.drawText("CLEAR GAUGE", boxRect.centerX(), boxRect.top + boxHeight * 0.38f, clearLabelPaint)
        canvas.drawText(
            "%.1f %%".format(s.clearGauge),
            boxRect.centerX(), boxRect.top + boxHeight * 0.85f, clearValuePaint,
        )
        clearLabelPaint.textAlign = Paint.Align.LEFT
        clearValuePaint.textAlign = Paint.Align.LEFT
    }

    private fun drawCombo(canvas: Canvas, s: HudState, w: Float, h: Float) {
        if (s.combo < 2) return
        comboPaint.textAlign = Paint.Align.CENTER
        comboLabelPaint.textAlign = Paint.Align.CENTER
        val y = h * 0.635f
        canvas.drawText(s.combo.toString(), w / 2f, y, comboPaint)
        canvas.drawText("COMBO", w / 2f, y + dp(14f), comboLabelPaint)
        comboPaint.textAlign = Paint.Align.LEFT
        comboLabelPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawReflecCallout(canvas: Canvas, s: HudState, w: Float, h: Float) {
        if (s.lastReflecAtNanos == 0L) return
        val ageSeconds = (System.nanoTime() - s.lastReflecAtNanos) / 1_000_000_000.0
        if (ageSeconds > REFLEC_HOLD_SECONDS) return

        val fade = (1.0 - ageSeconds / REFLEC_HOLD_SECONDS).toFloat()
        reflecPaint.textAlign = Paint.Align.CENTER
        reflecPaint.alpha = (fade * 255).toInt().coerceIn(0, 255)
        canvas.drawText("JUST REFLEC", w / 2f, h * 0.72f, reflecPaint)
        reflecPaint.textAlign = Paint.Align.LEFT
        reflecPaint.alpha = 255
    }

    private fun drawJudgment(canvas: Canvas, s: HudState, w: Float, h: Float) {
        drawJudgmentFor(
            canvas, s.lastJudgment, s.lastDeltaMs, s.lastJudgmentAtNanos,
            w, h * 0.825f, upsideDown = false,
        )
        // The far side gets its own, the right way up for whoever is reading it.
        if (!s.opponentIsCpu) {
            drawJudgmentFor(
                canvas, s.opponentLastJudgment, s.opponentLastDeltaMs,
                s.opponentLastJudgmentAtNanos,
                w, h * 0.175f, upsideDown = true,
            )
        }
    }

    private fun drawJudgmentFor(
        canvas: Canvas,
        lastJudgment: Judgment?,
        deltaMs: Double,
        atNanos: Long,
        w: Float,
        y: Float,
        upsideDown: Boolean,
    ) {
        val judgment = lastJudgment ?: return
        val ageSeconds = (System.nanoTime() - atNanos) / 1_000_000_000.0
        if (ageSeconds > JUDGMENT_HOLD_SECONDS) return

        canvas.save()
        if (upsideDown) canvas.rotate(180f, w / 2f, y)

        val fade = (1.0 - ageSeconds / JUDGMENT_HOLD_SECONDS).toFloat()
        val alpha = (fade * 255).toInt().coerceIn(0, 255)

        judgmentPaint.textAlign = Paint.Align.CENTER
        judgmentPaint.color = Palette.judgment(judgment) or OPAQUE
        judgmentPaint.alpha = alpha
        canvas.drawText(judgment.name, w / 2f, y, judgmentPaint)

        if (judgment != Judgment.MISS) {
            deltaPaint.textAlign = Paint.Align.CENTER
            deltaPaint.alpha = alpha
            val sign = if (deltaMs >= 0) "LATE" else "EARLY"
            canvas.drawText(
                "%s %.0f ms".format(sign, abs(deltaMs)),
                w / 2f, y + dp(16f), deltaPaint,
            )
            deltaPaint.textAlign = Paint.Align.LEFT
            deltaPaint.alpha = 255
        }
        judgmentPaint.textAlign = Paint.Align.LEFT
        judgmentPaint.alpha = 255
        canvas.restore()
    }

    private fun drawStats(canvas: Canvas, s: HudState, h: Float) {
        canvas.drawText(
            "JUST ${s.just}   GREAT ${s.great}   GOOD ${s.good}   MISS ${s.miss}",
            dp(14f), h - dp(14f), statPaint,
        )
    }

    private fun drawAutoChip(canvas: Canvas, s: HudState, w: Float) {
        val chipWidth = dp(54f)
        val chipHeight = dp(24f)
        val right = w - dp(14f)
        val top = dp(14f)
        chipRect.set(right - chipWidth, top, right, top + chipHeight)

        chipPaint.color = if (s.autoPlay) {
            Palette.HUD_CHIP_ACTIVE or OPAQUE
        } else {
            Palette.HUD_WELL or OPAQUE
        }
        canvas.drawRoundRect(chipRect, chipHeight / 2f, chipHeight / 2f, chipPaint)

        chipTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "AUTO",
            chipRect.centerX(),
            chipRect.centerY() + chipTextPaint.textSize * 0.35f,
            chipTextPaint,
        )
        chipTextPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawResult(canvas: Canvas, s: HudState, w: Float, h: Float) {
        // Nearly opaque: the field's own score readouts sit right behind this
        // panel, and a thinner scrim leaves two sets of numbers overlapping.
        canvas.drawColor(Color.argb(240, 3, 5, 10))

        if (!s.opponentIsCpu) {
            drawVersusResult(canvas, s, w, h)
            return
        }

        val cx = w / 2f
        var y = h * 0.36f

        // Against a CPU the gauge decides the outcome and the combo decides how
        // loudly a clear is announced.
        val headline = when {
            !s.cleared -> "FAILED"
            s.miss == 0 -> "FULL COMBO"
            else -> "CLEARED"
        }
        val good = s.cleared

        resultPaint.textAlign = Paint.Align.CENTER
        resultPaint.color = if (good) Color.WHITE else Palette.CLEAR_GAUGE_LOW or OPAQUE
        canvas.drawText(headline, cx, y, resultPaint)

        y += dp(26f)
        statPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            run {
                "CLEAR GAUGE %.1f %%   (need %.0f %%)".format(s.clearGauge, s.clearThreshold)
            },
            cx, y, statPaint,
        )

        y += dp(56f)
        playerScorePaint.textAlign = Paint.Align.CENTER
        // Held back during play so objects read through it; on the result screen
        // there is nothing behind it and it should be the brightest thing here.
        playerScorePaint.alpha = 255
        canvas.drawText(s.score.toString(), cx, y, playerScorePaint)
        playerScorePaint.textAlign = Paint.Align.LEFT
        playerScorePaint.alpha = 170

        y += dp(40f)
        statPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("MAX COMBO ${s.bestCombo} / ${s.maxCombo}", cx, y, statPaint)
        y += dp(20f)
        canvas.drawText(
            "JUST ${s.just}   GREAT ${s.great}   GOOD ${s.good}   MISS ${s.miss}" +
                if (s.keep > 0) "   KEEP ${s.keep}" else "",
            cx, y, statPaint,
        )
        y += dp(40f)
        canvas.drawText("TAP TO CONTINUE", cx, y, statPaint)
        statPaint.textAlign = Paint.Align.LEFT
        resultPaint.textAlign = Paint.Align.LEFT
    }

    /**
     * Two results, one for each person, each the right way up for them.
     *
     * The two are not the same panel repeated: WIN and LOSE are opposite facts
     * about the same match, and each player should be reading their own.
     */
    private fun drawVersusResult(canvas: Canvas, s: HudState, w: Float, h: Float) {
        val drawn = s.score == s.opponentScore
        drawSideResult(
            canvas, w, h * 0.62f, upsideDown = false,
            headline = if (drawn) "DRAW" else if (s.score > s.opponentScore) "WIN" else "LOSE",
            won = drawn || s.score > s.opponentScore,
            score = s.score,
            versus = s.opponentScore,
            bestCombo = s.bestCombo,
            maxCombo = s.maxCombo,
            counts = "JUST ${s.just}   GREAT ${s.great}   GOOD ${s.good}   MISS ${s.miss}",
        )
        drawSideResult(
            canvas, w, h * 0.38f, upsideDown = true,
            headline = if (drawn) "DRAW" else if (s.opponentScore > s.score) "WIN" else "LOSE",
            won = drawn || s.opponentScore > s.score,
            score = s.opponentScore,
            versus = s.score,
            bestCombo = s.opponentCombo,
            maxCombo = s.maxCombo,
            counts = "JUST ${s.opponentJust}   GREAT ${s.opponentGreat}   " +
                "GOOD ${s.opponentGood}   MISS ${s.opponentMiss}",
        )

        statPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("TAP TO CONTINUE", w / 2f, h * 0.5f, statPaint)
        statPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawSideResult(
        canvas: Canvas,
        w: Float,
        centreY: Float,
        upsideDown: Boolean,
        headline: String,
        won: Boolean,
        score: Int,
        versus: Int,
        bestCombo: Int,
        maxCombo: Int,
        counts: String,
    ) {
        canvas.save()
        if (upsideDown) canvas.rotate(180f, w / 2f, centreY)

        val cx = w / 2f
        var y = centreY - dp(46f)

        resultPaint.textAlign = Paint.Align.CENTER
        resultPaint.color = if (won) Color.WHITE else Palette.CLEAR_GAUGE_LOW or OPAQUE
        canvas.drawText(headline, cx, y, resultPaint)

        y += dp(40f)
        playerScorePaint.textAlign = Paint.Align.CENTER
        playerScorePaint.alpha = 255
        canvas.drawText("$score", cx, y, playerScorePaint)
        playerScorePaint.textAlign = Paint.Align.LEFT
        playerScorePaint.alpha = 170

        y += dp(24f)
        statPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("vs $versus    MAX COMBO $bestCombo / $maxCombo", cx, y, statPaint)
        y += dp(20f)
        canvas.drawText(counts, cx, y, statPaint)
        statPaint.textAlign = Paint.Align.LEFT
        resultPaint.textAlign = Paint.Align.LEFT

        canvas.restore()
    }

    private fun paint(sizeSp: Float, color: Int, bold: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizeSp * density
            this.color = color
            typeface = Typeface.create(
                Typeface.SANS_SERIF,
                if (bold) Typeface.BOLD else Typeface.NORMAL,
            )
        }

    private fun dp(value: Float) = value * density

    private companion object {
        const val JUDGMENT_HOLD_SECONDS = 0.45
        const val REFLEC_HOLD_SECONDS = 0.70

        /** The prompt shows for this much of a count-in, then clears the field. */
        const val PROMPT_FRACTION = 0.5

        /** How long the prompt takes to fade out at the end of its window. */
        const val PROMPT_FADE_MS = 500.0
        const val OPAQUE = 0xFF000000.toInt()
    }
}
