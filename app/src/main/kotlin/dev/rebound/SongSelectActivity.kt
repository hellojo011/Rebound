package dev.rebound

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.rebound.core.chart.ChartMeta
import dev.rebound.core.chart.ChartParser
import dev.rebound.song.SongLibrary
import kotlin.concurrent.thread

/**
 * The library: the bundled demo, plus every song the player has imported.
 *
 * Built from plain views rather than a list adapter. A song collection is tens
 * of rows, not thousands, and rebuilding the whole list on resume is both
 * simpler to follow and fast enough that recycling would buy nothing.
 */
class SongSelectActivity : ComponentActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var statusLine: TextView

    private val modeButtons = mutableMapOf<Boolean, TextView>()
    private var twoPlayer = false

    /**
     * `.reb` has no registered MIME type, so the picker has to allow anything and
     * the archive reader decides whether the file is really a song.
     */
    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importSong)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()
        setContentView(buildLayout())

        // Launched by opening a .reb from a file manager or a chat app.
        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let(::importSong)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // --- layout -----------------------------------------------------------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }

        // The window draws edge to edge, so the status bar would otherwise sit on
        // top of the heading. Insets are read at runtime rather than guessed,
        // because cutouts and gesture bars vary by device.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(20), bars.top + dp(16), dp(20), bars.bottom + dp(16))
            insets
        }

        root.addView(
            TextView(this).apply {
                text = "REBOUND"
                setTextColor(ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.18f
            },
        )
        root.addView(
            TextView(this).apply {
                text = "SELECT MUSIC"
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                letterSpacing = 0.28f
                setPadding(0, dp(2), 0, dp(18))
            },
        )

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pillButton("IMPORT  .reb") { importPicker.launch(arrayOf("*/*")) })
                addView(
                    pillButton("EDITOR") {
                        startActivity(Intent(this@SongSelectActivity, EditorActivity::class.java))
                    }.apply {
                        (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10)
                    },
                )
                addView(
                    pillButton("SETTINGS") {
                        startActivity(Intent(this@SongSelectActivity, SettingsActivity::class.java))
                    }.apply {
                        (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(10)
                    },
                )
            },
        )

        root.addView(modeRow())

        statusLine = TextView(this).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(2), dp(14), 0, dp(8))
        }
        root.addView(statusLine)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(listContainer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )

        return root
    }

    /**
     * One or two players.
     *
     * Both modes have two sides playing the same chart; this only decides who
     * moves the far one. In 2P the tablet goes flat between the two of you and
     * the top half of the screen is theirs.
     */
    private fun modeRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }

        row.addView(
            TextView(this).apply {
                text = "MODE"
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                letterSpacing = 0.2f
                setPadding(dp(2), 0, dp(12), 0)
            },
        )

        listOf(false to "1P  vs CPU", true to "2P  vs PLAYER").forEach { (isTwo, label) ->
            val button = pillButton(label) { selectMode(isTwo) }.apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8)
            }
            modeButtons[isTwo] = button
            row.addView(button)
        }

        selectMode(twoPlayer)
        return row
    }

    private fun selectMode(isTwoPlayer: Boolean) {
        twoPlayer = isTwoPlayer
        modeButtons.forEach { (candidate, button) ->
            button.background = roundedFill(if (candidate == isTwoPlayer) CHIP_ACTIVE else CHIP, dp(22))
            button.setTextColor(if (candidate == isTwoPlayer) Color.WHITE else MUTED)
        }
    }

    // --- data -------------------------------------------------------------

    private class Difficulty(val meta: ChartMeta, val entry: String)

    private class SongRow(
        val title: String,
        val artist: String,
        val badge: String?,
        val difficulties: List<Difficulty>,
        val play: (Difficulty) -> Unit,
        val edit: ((Difficulty) -> Unit)?,
        val remove: (() -> Unit)?,
    )

    /** Reads the library off the main thread, then rebuilds the list. */
    private fun refresh() {
        statusLine.text = "Reading library…"
        thread(name = "rebound-library") {
            val rows = mutableListOf<SongRow>()

            runCatching { demoRow() }
                .onSuccess { rows += it }
                .onFailure { Log.e(TAG, "could not read the demo chart", it) }

            SongLibrary.list(this).forEach { song ->
                // A song with one unreadable difficulty is still worth listing
                // for the ones that do parse.
                val difficulties = song.manifest.charts
                    .mapNotNull { entry ->
                        runCatching {
                            Difficulty(ChartParser.parseMeta(song.chartText(entry)), entry)
                        }
                            .onFailure { Log.w(TAG, "${song.id}/$entry: ${it.message}") }
                            .getOrNull()
                    }
                    // Easiest first, however they happen to be listed in the file.
                    .sortedBy { it.meta.level }
                if (difficulties.isEmpty()) return@forEach

                rows += SongRow(
                    title = song.manifest.title,
                    artist = song.manifest.artist,
                    badge = null,
                    difficulties = difficulties,
                    play = {
                        startActivity(
                            MainActivity.intentFor(this, song.id, it.entry, twoPlayer),
                        )
                    },
                    edit = {
                        startActivity(EditorActivity.intentFor(this, song.id, it.entry))
                    },
                    remove = {
                        thread(name = "rebound-delete") {
                            SongLibrary.delete(song)
                            runOnUiThread { refresh() }
                        }
                    },
                )
            }

            runOnUiThread { showRows(rows) }
        }
    }

    private fun demoRow(): SongRow {
        val meta = ChartParser.parseMeta(
            assets.open(DEMO_CHART).bufferedReader().use { it.readText() },
        )
        return SongRow(
            title = meta.title,
            artist = meta.artist,
            badge = "BUNDLED",
            difficulties = listOf(Difficulty(meta, "")),
            play = { startActivity(MainActivity.demoIntent(this, twoPlayer)) },
            // The demo lives in assets rather than the library, so there is
            // nothing on disk for the editor to open and write back to.
            edit = null,
            remove = null,
        )
    }

    private fun showRows(rows: List<SongRow>) {
        listContainer.removeAllViews()
        rows.forEach { listContainer.addView(songCard(it)) }

        val imported = rows.count { it.badge == null }
        statusLine.text = when (imported) {
            0 -> "No songs imported yet — use IMPORT to add a .reb"
            1 -> "1 song in your library"
            else -> "$imported songs in your library"
        }
    }

    // --- views ------------------------------------------------------------

    private fun songCard(row: SongRow): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedFill(CARD, dp(14))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(
            TextView(this).apply {
                text = row.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        row.badge?.let { heading.addView(tag(it, MUTED)) }
        card.addView(heading)

        card.addView(
            TextView(this).apply {
                text = row.artist
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(1), 0, dp(10))
            },
        )

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.difficulties.forEach { difficulty ->
            chips.addView(
                difficultyChip(
                    difficulty = difficulty,
                    onPlay = { row.play(difficulty) },
                    // Per difficulty, not per song: a song with two charts has
                    // two things to edit, and only one of them is the first.
                    onEdit = row.edit?.let { edit -> { edit(difficulty) } },
                ),
            )
        }
        card.addView(chips)

        row.remove?.let { remove ->
            // Long press rather than a delete button: removing a song is rare, and
            // a permanent button next to the play chips invites mis-taps.
            card.setOnLongClickListener {
                confirmRemoval(row.title, remove)
                true
            }
        }
        return card
    }

    /**
     * A difficulty: tap the body to play it, tap the strip underneath to edit it.
     *
     * Two targets in one chip rather than two chips, because they are two things
     * to do with the same difficulty and pulling them apart would leave the list
     * asking which EDIT belonged to which level.
     */
    private fun difficultyChip(
        difficulty: Difficulty,
        onPlay: () -> Unit,
        onEdit: (() -> Unit)?,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = roundedFill(CHIP, dp(10))
        setPadding(dp(14), dp(8), dp(14), if (onEdit == null) dp(8) else dp(2))
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            rightMargin = dp(8)
        }
        isClickable = true
        setOnClickListener { onPlay() }

        addView(
            TextView(context).apply {
                text = difficulty.meta.difficulty
                setTextColor(ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                letterSpacing = 0.12f
                gravity = Gravity.CENTER
            },
        )
        addView(
            TextView(context).apply {
                text = "Lv.${difficulty.meta.level}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                gravity = Gravity.CENTER
            },
        )

        onEdit?.let { edit ->
            addView(
                TextView(context).apply {
                    text = "EDIT"
                    setTextColor(MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    letterSpacing = 0.14f
                    gravity = Gravity.CENTER
                    background = roundedFill(CHIP_ACTIVE, dp(6))
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    // Handling the touch here keeps it from reaching the chip
                    // body, so an edit never starts a run by accident.
                    isClickable = true
                    setOnClickListener { edit() }
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        .apply { topMargin = dp(6) }
                },
            )
        }
    }

    private fun pillButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            background = roundedFill(CHIP, dp(22))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }

    private fun tag(label: String, color: Int): View =
        TextView(this).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            letterSpacing = 0.16f
            background = roundedFill(CHIP, dp(8))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

    private fun confirmRemoval(title: String, remove: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Remove \"$title\"?")
            .setMessage("The song and its charts will be deleted from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> remove() }
            .show()
    }

    // --- import -----------------------------------------------------------

    private fun importSong(uri: Uri) {
        statusLine.text = "Importing…"
        thread(name = "rebound-import") {
            val result = runCatching {
                val stream = contentResolver.openInputStream(uri)
                    ?: error("could not open the selected file")
                stream.use { SongLibrary.install(this, it) }
            }
            runOnUiThread {
                result
                    .onSuccess {
                        toast("Imported \"${it.manifest.title}\"")
                        refresh()
                    }
                    .onFailure {
                        toast(it.message ?: "That file could not be imported")
                        refresh()
                    }
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // --- helpers ----------------------------------------------------------

    private fun roundedFill(color: Int, radius: Int) = GradientDrawable().apply {
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
        const val TAG = "ReboundSelect"
        const val DEMO_CHART = "songs/demo/demo.rbc"

        const val BACKGROUND = 0xFF05080F.toInt()
        const val CARD = 0xFF101725.toInt()
        const val CHIP = 0xFF1B2740.toInt()
        const val CHIP_ACTIVE = 0xFF2C3E63.toInt()
        const val ACCENT = 0xFFFF6FA0.toInt()
        const val MUTED = 0xFF8FA3BF.toInt()
    }
}
