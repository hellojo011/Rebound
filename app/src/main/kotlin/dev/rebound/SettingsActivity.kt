package dev.rebound

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.rebound.render.Skin
import dev.rebound.render.Skins
import dev.rebound.settings.Settings
import kotlin.math.roundToInt

/**
 * Sync offset, object size and skin.
 *
 * Everything is written as it is changed and picked up by the next run, so there
 * is no save button to forget and nothing to lose by backing out.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var syncValue: TextView
    private lateinit var scaleValue: TextView
    private lateinit var speedValue: TextView
    private lateinit var skinRow: LinearLayout
    private lateinit var topAlertRow: LinearLayout

    private var selectedSkin: Skin = Skins.CLASSIC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()
        selectedSkin = Settings.skin(this)
        setContentView(buildLayout())
    }

    private fun buildLayout(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(heading("SETTINGS"))
        content.addView(
            caption("Changes apply from the next song."),
        )

        content.addView(syncSection())
        content.addView(speedSection())
        content.addView(objectSizeSection())
        content.addView(topAlertSection())
        content.addView(skinSection())

        val scroller = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        ViewCompat.setOnApplyWindowInsetsListener(scroller) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(20), bars.top + dp(16), dp(20), bars.bottom + dp(24))
            insets
        }
        return scroller
    }

    // --- sync ---------------------------------------------------------------

    private fun syncSection(): View {
        val card = card()
        card.addView(label("AUDIO SYNC"))
        card.addView(
            caption(
                "Raise if your hits keep registering LATE, lower if they register EARLY. " +
                    "The JUST/GREAT readout under the bar shows which way you are off.",
            ),
        )

        syncValue = valueText(formatSync(Settings.syncOffsetMs(this).roundToInt()))
        card.addView(syncValue)

        val current = Settings.syncOffsetMs(this).roundToInt()
        card.addView(
            slider(
                steps = Settings.SYNC_MAX_MS - Settings.SYNC_MIN_MS,
                progress = current - Settings.SYNC_MIN_MS,
            ) { progress ->
                val ms = progress + Settings.SYNC_MIN_MS
                syncValue.text = formatSync(ms)
                Settings.setSyncOffsetMs(this, ms)
            },
        )

        card.addView(
            textButton("RESET TO 0") {
                Settings.setSyncOffsetMs(this, 0)
                recreate()
            },
        )
        return card
    }

    private fun formatSync(ms: Int) = if (ms > 0) "+$ms ms" else "$ms ms"

    // --- speed --------------------------------------------------------------

    private fun speedSection(): View {
        val card = card()
        card.addView(label("SCROLL SPEED"))
        card.addView(
            caption(
                "How fast objects cross the field. Faster means fewer on screen at " +
                    "once and more reading time per object; slower means you see " +
                    "patterns further ahead.",
            ),
        )

        val current = Settings.speed(this)
        speedValue = valueText(formatSpeed(current))
        card.addView(speedValue)

        card.addView(
            slider(steps = SPEED_STEPS, progress = speedToProgress(current)) { progress ->
                val speed = progressToSpeed(progress)
                speedValue.text = formatSpeed(speed)
                Settings.setSpeed(this, speed)
            },
        )
        return card
    }

    private fun formatSpeed(speed: Float) = "%.2f×".format(speed)

    private fun speedToProgress(speed: Float): Int {
        val span = Settings.SPEED_MAX - Settings.SPEED_MIN
        return (((speed - Settings.SPEED_MIN) / span) * SPEED_STEPS).roundToInt()
    }

    private fun progressToSpeed(progress: Int): Float {
        val span = Settings.SPEED_MAX - Settings.SPEED_MIN
        return Settings.SPEED_MIN + span * (progress.toFloat() / SPEED_STEPS)
    }

    // --- object size --------------------------------------------------------

    private fun objectSizeSection(): View {
        val card = card()
        card.addView(label("OBJECT SIZE"))
        card.addView(caption("Larger objects are easier to see; smaller ones crowd less on busy charts."))

        val current = Settings.objectScale(this)
        scaleValue = valueText(formatScale(current))
        card.addView(scaleValue)

        card.addView(
            slider(steps = SCALE_STEPS, progress = scaleToProgress(current)) { progress ->
                val scale = progressToScale(progress)
                scaleValue.text = formatScale(scale)
                Settings.setObjectScale(this, scale)
            },
        )
        return card
    }

    private fun formatScale(scale: Float) = "%.2f×".format(scale)

    private fun scaleToProgress(scale: Float): Int {
        val span = Settings.OBJECT_SCALE_MAX - Settings.OBJECT_SCALE_MIN
        return (((scale - Settings.OBJECT_SCALE_MIN) / span) * SCALE_STEPS).roundToInt()
    }

    private fun progressToScale(progress: Int): Float {
        val span = Settings.OBJECT_SCALE_MAX - Settings.OBJECT_SCALE_MIN
        return Settings.OBJECT_SCALE_MIN + span * (progress.toFloat() / SCALE_STEPS)
    }

    // --- top alert ----------------------------------------------------------

    private fun topAlertSection(): View {
        val card = card()
        card.addView(label("TOP OBJECT ALERT"))
        card.addView(
            caption(
                "Marks the tap point a green object is heading for. " +
                    "They arrive away from the bar, where the eye is not waiting.",
            ),
        )

        topAlertRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        refreshTopAlertRow()
        card.addView(topAlertRow)
        return card
    }

    private fun refreshTopAlertRow() {
        topAlertRow.removeAllViews()
        val on = Settings.topAlert(this)
        topAlertRow.addView(topAlertOption("ON", on))
        topAlertRow.addView(topAlertOption("OFF", !on))
    }

    private fun topAlertOption(text: String, selected: Boolean): View = TextView(this).apply {
        this.text = text
        setTextColor(if (selected) Color.WHITE else MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.12f
        gravity = Gravity.CENTER
        background = rounded(if (selected) CHIP_ACTIVE else CHIP, dp(12))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        }
        isClickable = true
        setOnClickListener {
            Settings.setTopAlert(this@SettingsActivity, text == "ON")
            refreshTopAlertRow()
        }
    }

    // --- skin ---------------------------------------------------------------

    private fun skinSection(): View {
        val card = card()
        card.addView(label("SKIN"))
        card.addView(caption("Judgment colours stay the same in every skin — they carry meaning."))

        skinRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        Skins.ALL.forEach { skin -> skinRow.addView(skinOption(skin)) }
        card.addView(skinRow)
        return card
    }

    private fun skinOption(skin: Skin): View {
        val selected = skin.id == selectedSkin.id

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (selected) CHIP_ACTIVE else CHIP, dp(12))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            isClickable = true
            setOnClickListener {
                Settings.setSkin(this@SettingsActivity, skin)
                selectedSkin = skin
                skinRow.removeAllViews()
                Skins.ALL.forEach { skinRow.addView(skinOption(it)) }
            }
        }

        row.addView(
            TextView(this).apply {
                text = skin.label
                setTextColor(if (selected) Color.WHITE else MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.12f
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )

        // A few swatches say more about a skin than its name does.
        listOf(skin.playerBar, skin.objectTap, skin.objectGold, skin.opponentBar)
            .forEach { row.addView(swatch(it)) }

        return row
    }

    private fun swatch(rgb: Int): View = View(this).apply {
        background = rounded(rgb or OPAQUE, dp(5))
        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(6) }
    }

    // --- small view helpers -------------------------------------------------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(14))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(12)
        }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.18f
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.14f
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, dp(4), 0, dp(2))
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun valueText(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setPadding(0, dp(8), 0, 0)
    }

    private fun slider(steps: Int, progress: Int, onChange: (Int) -> Unit) =
        SeekBar(this).apply {
            max = steps
            this.progress = progress.coerceIn(0, steps)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) onChange(value)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }

    private fun textButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.1f
        gravity = Gravity.CENTER
        background = rounded(CHIP, dp(16))
        setPadding(dp(16), dp(9), dp(16), dp(9))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(10)
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val SCALE_STEPS = 40
        const val SPEED_STEPS = 30

        const val BACKGROUND = 0xFF05080F.toInt()
        const val CARD = 0xFF101725.toInt()
        const val CHIP = 0xFF1B2740.toInt()
        const val CHIP_ACTIVE = 0xFF2C3E63.toInt()
        const val ACCENT = 0xFFFF6FA0.toInt()
        const val MUTED = 0xFF8FA3BF.toInt()
        const val OPAQUE = 0xFF000000.toInt()
    }
}
