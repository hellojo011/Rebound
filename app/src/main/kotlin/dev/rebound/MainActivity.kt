package dev.rebound

import android.content.Context
import android.content.Intent
import kotlin.random.Random
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.rebound.audio.AudioClock
import dev.rebound.audio.GameAudio
import dev.rebound.core.chart.Chart
import dev.rebound.core.chart.ChartParser
import dev.rebound.game.GameRenderer
import dev.rebound.game.GameView
import dev.rebound.game.HudView
import dev.rebound.game.OpponentControl
import dev.rebound.settings.Settings
import dev.rebound.song.SongLibrary
import kotlin.concurrent.thread

/** Plays one chart. Started by [SongSelectActivity], or directly for the demo. */
class MainActivity : ComponentActivity() {

    private val clock = AudioClock()

    private lateinit var root: FrameLayout
    private var gameView: GameView? = null
    private var renderer: GameRenderer? = null
    private var songStarted = false
    private var runFinished = false
    private var pauseOverlay: View? = null

    private var songStartTask: Runnable? = null
    private var countInTargetMs = 0.0
    private var countInInterrupted = false

    /** Set when a restart is passing the audio stream to the incoming run. */
    private var handingOverAudio = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        setContentView(root)
        root.addView(message("LOADING"))

        val autoPlay = intent.getBooleanExtra(EXTRA_AUTOPLAY, false)

        // Read once, up front. Settings that shifted mid-song would move the
        // goalposts under a score that is still being set.
        clock.userOffsetMs = Settings.syncOffsetMs(this)

        onBackPressedDispatcher.addCallback(this) {
            when {
                runFinished -> finish()
                pauseOverlay != null -> resumeFromMenu()
                renderer != null -> showPauseMenu()
                else -> finish()
            }
        }

        // Decoding a whole track takes a few hundred ms; keep it off the main thread.
        thread(name = "rebound-load") {
            try {
                val chart = loadChartAndPrepareAudio()
                val started = GameAudio.start()
                runOnUiThread {
                    if (started) startPlaying(chart, autoPlay) else fail("AUDIO STREAM FAILED TO OPEN")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "failed to load chart", t)
                runOnUiThread { fail("LOAD FAILED\n${t::class.java.simpleName}\n${t.message}") }
            }
        }
    }

    /**
     * Resolves what to play and hands the audio to the engine.
     *
     * With no song in the intent this falls back to the bundled demo, which is
     * the only chart that lives in assets -- everything else comes from the
     * player's library.
     */
    private fun loadChartAndPrepareAudio(): Chart {
        val songId = intent.getStringExtra(EXTRA_SONG_ID)

        // A fresh seed each play, so the random landing positions are somewhere
        // new every run rather than the fixed field the default (0) would give.
        val seed = Random.nextInt()

        if (songId == null) {
            val chart = ChartParser.parse(
                assets.open(DEMO_CHART).bufferedReader().use { it.readText() },
                positionSeed = seed,
            )
            GameAudio.prepareAsset(this, DEMO_DIR + chart.meta.audio)
            return chart
        }

        val song = SongLibrary.list(this).firstOrNull { it.id == songId }
            ?: error("song \"$songId\" is not in the library")
        val entry = intent.getStringExtra(EXTRA_CHART) ?: song.manifest.charts.first()

        val chart = ChartParser.parse(song.chartText(entry), positionSeed = seed)
        GameAudio.prepareFile(this, song.audioFile)
        return chart
    }

    private fun startPlaying(chart: Chart, autoPlay: Boolean) {
        val twoPlayer = intent.getBooleanExtra(EXTRA_TWO_PLAYER, false)
        val hud = HudView(this)

        val gameRenderer = GameRenderer(
            chart = chart,
            clock = clock,
            onHitSound = { GameAudio.triggerHit() },
            onHud = {
                runFinished = it.finished
                hud.submit(it)
            },
            opponentControl = if (twoPlayer) OpponentControl.HUMAN else OpponentControl.CPU,
            skin = Settings.skin(this),
            objectScale = Settings.objectScale(this),
        ).apply {
            this.autoPlay = autoPlay
            // Higher speed means less time in the air, not more distance.
            approachMs = GameRenderer.BASE_APPROACH_MS / Settings.speed(this@MainActivity)
        }

        hud.onToggleAutoPlay = {
            gameRenderer.autoPlay = !gameRenderer.autoPlay
            Log.i(TAG, "autoplay -> ${gameRenderer.autoPlay}")
        }
        hud.onDismissResult = { finish() }

        val view = GameView(this, gameRenderer, clock, twoPlayer)
        view.onPauseGesture = { showPauseMenu() }

        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(hud, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        renderer = gameRenderer
        gameView = view

        // Broken down by type: when something turns up on the field that was not
        // in the editor, this says whether the chart really contains it or
        // whether the field is inventing it.
        val byType = chart.notes.groupingBy { it.type }.eachCount()
        Log.i(
            TAG,
            "playing \"${chart.meta.title}\": ${chart.notes.size} objects " +
                "(${byType.entries.joinToString { "${it.key}=${it.value}" }}), " +
                "${chart.maxCombo} max combo, ends at ${chart.durationMs.toInt()} ms, " +
                "output latency ${"%.1f".format(GameAudio.outputLatencyMs())} ms",
        )

        beginCountIn(LEAD_IN_MS, 0.0)
    }

    /**
     * Counts in to a point in the song and starts playback when it lands.
     *
     * Song time runs through the count-in as one continuous timeline, which is
     * what gives the opening objects room to fly in instead of appearing already
     * on the bar. The scheduled start is held so it can be called off: being
     * backgrounded mid count-in must not leave the song starting to nobody.
     */
    private fun beginCountIn(durationMs: Long, resumeAtMs: Double) {
        cancelCountIn()
        countInTargetMs = resumeAtMs
        clock.startLeadIn(durationMs, resumeAtMs)
        renderer?.paused = false

        val task = Runnable {
            songStartTask = null
            songStarted = true
            GameAudio.playSongFrom(resumeAtMs)
        }
        songStartTask = task
        root.postDelayed(task, durationMs)
    }

    private fun cancelCountIn() {
        songStartTask?.let { root.removeCallbacks(it) }
        songStartTask = null
    }

    // --- pause ------------------------------------------------------------

    private fun showPauseMenu() {
        if (pauseOverlay != null || renderer == null || runFinished) return

        // Not during a count-in. The clock is running off uptime rather than off
        // the audio at that point, so it would carry on behind the menu and the
        // song would start into a paused game.
        if (clock.isLeadingIn()) return

        GameAudio.pauseSong()
        renderer?.paused = true

        val overlay = buildPauseMenu()
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        pauseOverlay = overlay
    }

    /**
     * Picks up a little before where the player left off.
     *
     * Dropping straight back into the music would cost them the next object
     * before they had their hands back on the screen, so the same count-in the
     * run opened with runs again, shorter, ending on the moment they paused.
     */
    private fun resumeFromMenu() {
        val overlay = pauseOverlay ?: return
        root.removeView(overlay)
        pauseOverlay = null

        beginCountIn(RESUME_LEAD_IN_MS, clock.songTimeMs().coerceAtLeast(0.0))
    }

    private fun restart() {
        // A fresh activity rather than resetting in place: the run has a lot of
        // state -- engine, clock, audio -- and rebuilding it is the one way to be
        // sure none of the old run is left behind.
        //
        // The audio engine is the one thing handed over rather than rebuilt.
        // finish() is asynchronous, so tearing the stream down here would close
        // it *after* the new run had already opened and started it, leaving a
        // clock that never advances.
        handingOverAudio = true
        val again = Intent(intent)
        finish()
        startActivity(again)
    }

    private fun buildPauseMenu(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(SCRIM)
            // Swallows everything, so nothing reaches the field underneath.
            isClickable = true
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
        }

        column.addView(
            TextView(this).apply {
                text = "PAUSED"
                setTextColor(Color.parseColor("#C6D8EE"))
                textSize = 15f
                letterSpacing = 0.28f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(22))
            },
        )

        column.addView(menuButton("RESUME", RESUME_COLOUR) { resumeFromMenu() })
        column.addView(menuButton("RESTART", RESTART_COLOUR) { restart() })
        column.addView(menuButton("QUIT", QUIT_COLOUR) { finish() })

        overlay.addView(column)
        return overlay
    }

    private fun menuButton(label: String, colour: Int, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#0A0E16"))
            textSize = 15f
            letterSpacing = 0.14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(colour)
                cornerRadius = dp(26).toFloat()
            }
            setPadding(dp(52), dp(16), dp(52), dp(16))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(240), WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun fail(text: String) {
        root.removeAllViews()
        root.addView(message(text))
    }

    private fun message(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#9FB3CC"))
        textSize = 16f
        gravity = Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onPause() {
        super.onPause()
        if (renderer != null && clock.isLeadingIn()) {
            // Backgrounded mid count-in. The menu is not available then, so call
            // the count-in off and run it again on the way back rather than
            // letting the song start to an empty room.
            cancelCountIn()
            countInInterrupted = true
        } else {
            // Leaving mid-song comes back to the menu rather than to a run that
            // carried on without the player.
            showPauseMenu()
        }
        GameAudio.pauseSong()
        gameView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        gameView?.onResume()

        if (countInInterrupted) {
            countInInterrupted = false
            val duration = if (countInTargetMs > 0.0) RESUME_LEAD_IN_MS else LEAD_IN_MS
            beginCountIn(duration, countInTargetMs)
            return
        }

        // Only once the song has actually begun, and never while the menu is up:
        // during a count-in there is nothing to resume, and resuming behind the
        // menu would start the music without the player.
        if (songStarted && pauseOverlay == null) GameAudio.resumeSong()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!handingOverAudio) GameAudio.stop()
    }

    companion object {
        private const val TAG = "Rebound"
        private const val BACKGROUND = 0xFF05080F.toInt()
        private const val DEMO_DIR = "songs/demo/"
        private const val DEMO_CHART = DEMO_DIR + "demo.rbc"

        /**
         * The prompt holds the first half, then clears and leaves the rest for
         * the opening objects to fly in against an empty field.
         */
        private const val LEAD_IN_MS = 5200L

        /** Shorter on resume: the player is already looking at the field. */
        private const val RESUME_LEAD_IN_MS = 2800L

        private const val SCRIM = 0xE6040711.toInt()
        private const val RESUME_COLOUR = 0xFF9BE88A.toInt()
        private const val RESTART_COLOUR = 0xFFFFD466.toInt()
        private const val QUIT_COLOUR = 0xFFF29BD4.toInt()

        /** `adb shell am start -n com.digiwb.rebound/dev.rebound.MainActivity --ez autoplay true` */
        const val EXTRA_AUTOPLAY = "autoplay"

        /** Library song id. Absent means the bundled demo. */
        const val EXTRA_SONG_ID = "songId"

        /** Which chart within the song. Absent means its first difficulty. */
        const val EXTRA_CHART = "chart"

        /** Whether a second person is playing the far side. Absent means a CPU is. */
        const val EXTRA_TWO_PLAYER = "twoPlayer"

        fun intentFor(
            context: Context,
            songId: String,
            chartEntry: String,
            twoPlayer: Boolean = false,
        ): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SONG_ID, songId)
                .putExtra(EXTRA_CHART, chartEntry)
                .putExtra(EXTRA_TWO_PLAYER, twoPlayer)

        fun demoIntent(context: Context, twoPlayer: Boolean = false): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_TWO_PLAYER, twoPlayer)
    }
}
