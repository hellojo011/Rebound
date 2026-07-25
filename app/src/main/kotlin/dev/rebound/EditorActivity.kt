package dev.rebound

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.rebound.audio.GameAudio
import dev.rebound.audio.PcmDecoder
import dev.rebound.core.chart.ChartMeta
import dev.rebound.core.chart.ChartParser
import dev.rebound.core.chart.GridObject
import dev.rebound.core.chart.RbcWriter
import dev.rebound.core.song.RebArchive
import dev.rebound.core.song.SongManifest
import dev.rebound.editor.ChartGridView
import dev.rebound.editor.EditorChart
import dev.rebound.editor.EditorTool
import dev.rebound.editor.Timeline
import dev.rebound.editor.Waveform
import dev.rebound.editor.WaveformView
import dev.rebound.song.InstalledSong
import dev.rebound.song.SongLibrary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Chart editor.
 *
 * The waveform and the object grid share one time axis, so an object is placed
 * by looking at where the transient is and tapping directly underneath it. Tempo
 * and offset come first because nothing can be placed accurately until the grid
 * lines up with what you hear.
 */
class EditorActivity : ComponentActivity() {

    private val timeline = Timeline()
    private val chart = EditorChart()

    private lateinit var waveformView: WaveformView
    private lateinit var gridView: ChartGridView
    private lateinit var trackLabel: TextView
    private lateinit var bpmValue: TextView
    private lateinit var offsetValue: TextView
    private lateinit var playButton: TextView
    private lateinit var previewButton: TextView
    private lateinit var endButton: TextView
    private lateinit var timeLabel: TextView
    private lateinit var countLabel: TextView

    private val toolButtons = mutableMapOf<EditorTool, TextView>()
    private val snapButtons = mutableMapOf<Int, TextView>()

    private var audioFile: File? = null
    private var durationMs: Double = 0.0
    private var playing = false
    private var playheadMs: Double = 0.0
    private var followPlayhead = true

    /** True while the engine still holds this editor's audio. */
    private var audioReady = false

    /** Ticks a hit sound as the playhead crosses each object. */
    private var previewEnabled = true
    private var previewedUpToMs = 0.0

    private var songTitle = "Untitled"
    private var songArtist = "Editor"
    private var songDifficulty = "NORMAL"
    private var songLevel = 5

    /** Bytes waiting for the user to choose where to save them. */
    private var pendingExport: ByteArray? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing) return
            val position = GameAudio.songPositionMs().coerceIn(0.0, durationMs)

            // Sound every object the playhead passed since the last frame. This
            // is the fastest way to hear that a placement is a hair off the beat:
            // a tick that flams against the drum is obvious where a rectangle
            // sitting near a waveform peak is not.
            if (previewEnabled && position > previewedUpToMs) {
                val crossed = chart.headsBetween(previewedUpToMs, position)
                if (crossed.isNotEmpty()) GameAudio.triggerHit()
            }
            previewedUpToMs = position

            setPlayhead(position)
            if (position >= durationMs) stopPlayback() else gridView.postOnAnimation(this)
        }
    }

    private val audioPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importAudio)
        }

    private val exportPicker =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri ->
            val bytes = pendingExport
            pendingExport = null
            if (uri == null || bytes == null) return@registerForActivityResult
            writeExport(uri, bytes)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goFullscreen()
        setContentView(buildLayout())
        timeline.addListener {
            waveformView.invalidate()
            gridView.invalidate()
        }

        intent.getStringExtra(EXTRA_SONG_ID)?.let { songId ->
            if (intent.getBooleanExtra(EXTRA_NEW_CHART, false)) {
                openForNewChart(songId)
            } else {
                openExisting(songId, intent.getStringExtra(EXTRA_CHART))
            }
        }
    }

    /**
     * Starts a fresh difficulty for a song already in the library.
     *
     * The audio and the song's name come across so the new chart belongs to the
     * same song, but nothing is placed and the difficulty is named something the
     * song is not already using -- saving under a name it *is* using would
     * overwrite that chart rather than add one.
     */
    private fun openForNewChart(songId: String) {
        trackLabel.text = "Opening…"
        thread(name = "rebound-editor-new") {
            val result = runCatching {
                SongLibrary.list(this).firstOrNull { it.id == songId }
                    ?: error("that song is no longer in your library")
            }

            runOnUiThread {
                result
                    .onSuccess { song ->
                        songTitle = song.manifest.title
                        songArtist = song.manifest.artist
                        songDifficulty = freeDifficultyName(song)
                        songLevel = 1
                        chart.clear()
                        updateCount()

                        loadAudio(song.audioFile.name) { target ->
                            song.audioFile.inputStream().use { input ->
                                target.outputStream().use { input.copyTo(it) }
                            }
                        }
                        toast("New ${songDifficulty} chart for \"${song.manifest.title}\"")
                    }
                    .onFailure {
                        Log.e(TAG, "could not start a chart", it)
                        trackLabel.text = "No audio loaded"
                        toast(it.message ?: "That song could not be opened")
                    }
            }
        }
    }

    /** A difficulty name whose chart entry the song does not already have. */
    private fun freeDifficultyName(song: InstalledSong): String {
        val taken = song.manifest.charts.toSet()
        DIFFICULTY_NAMES.forEach { name ->
            if ("${sanitise(name).lowercase()}.rbc" !in taken) return name
        }
        var n = 2
        while ("chart$n.rbc" in taken) n++
        return "CHART$n"
    }

    /** Reopens a song from the library: its audio, its tempo and its objects. */
    private fun openExisting(songId: String, chartEntry: String?) {
        trackLabel.text = "Opening…"
        thread(name = "rebound-editor-open") {
            val result = runCatching {
                val song = SongLibrary.list(this).firstOrNull { it.id == songId }
                    ?: error("that song is no longer in your library")
                val entry = chartEntry ?: song.manifest.charts.first()
                val parsed = ChartParser.parse(song.chartText(entry))
                Triple(song, parsed, entry)
            }

            runOnUiThread {
                result
                    .onSuccess { (song, parsed, _) ->
                        songTitle = song.manifest.title
                        songArtist = song.manifest.artist
                        songDifficulty = parsed.meta.difficulty
                        songLevel = parsed.meta.level
                        setBpm(parsed.meta.bpm)
                        setOffset(parsed.meta.offsetMs)
                        setEnd(parsed.meta.endMs)

                        chart.replaceAll(
                            parsed.notes.map {
                                // Only a rally the author pinned down comes back
                                // as a marked object. One the parser chose on its
                                // own stays automatic, so re-saving does not
                                // freeze a choice the author never made.
                                GridObject(
                                    it.timeMs, it.column, it.type, it.endTimeMs,
                                    rallied = it.rallyExplicit,
                                    chainStart = it.chainStart,
                                    chainStop = it.chainStop,
                                    green = it.isGreenLong,
                                )
                            },
                        )
                        updateCount()

                        loadAudio(song.audioFile.name) { target ->
                            song.audioFile.inputStream().use { input ->
                                target.outputStream().use { input.copyTo(it) }
                            }
                        }
                    }
                    .onFailure {
                        Log.e(TAG, "could not open song", it)
                        trackLabel.text = "No audio loaded"
                        toast(it.message ?: "That song could not be opened")
                    }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (playing) stopPlayback()
    }

    override fun onResume() {
        super.onResume()
        // A playtest hands the audio engine over to the game, so coming back
        // means loading this track again.
        val file = audioFile
        if (file != null && !audioReady) reopenAudio(file)
    }

    override fun onDestroy() {
        super.onDestroy()
        GameAudio.stop()
    }

    // --- layout -----------------------------------------------------------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(14), bars.top + dp(10), dp(14), bars.bottom + dp(10))
            insets
        }

        root.addView(headerRow())
        root.addView(trackRow())
        root.addView(tempoRow())
        root.addView(toolRow())

        waveformView = WaveformView(this, timeline).apply {
            onSeek = { seekTo(it) }
            onUserPan = { followPlayhead = false }
        }
        root.addView(
            waveformView,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(96)).apply { topMargin = dp(8) },
        )

        gridView = ChartGridView(this, timeline, chart).apply {
            onEdited = { updateCount() }
        }
        root.addView(
            gridView,
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            },
        )

        root.addView(transportRow())

        selectTool(EditorTool.TAP)
        selectSnap(timeline.snapDivision)
        setEnd(timeline.endMs)
        return root
    }

    private fun headerRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
            TextView(this@EditorActivity).apply {
                text = "EDITOR"
                setTextColor(ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.18f
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        countLabel = TextView(this@EditorActivity).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, dp(12), 0)
        }
        addView(countLabel)
        addView(pill("PLAYTEST") { playtest() })
        addView(pill("SAVE") { promptForSongInfo { save() } }.withLeftMargin(dp(8)))
        addView(pill("EXPORT") { promptForSongInfo { export() } }.withLeftMargin(dp(8)))
        updateCount()
    }

    private fun trackRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(4))

        trackLabel = TextView(this@EditorActivity).apply {
            text = "No audio loaded"
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        addView(trackLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Somewhere to start without hunting for a file first.
        addView(pill("DEMO TRACK") { loadDemoTrack() })
        addView(
            pill("IMPORT AUDIO") { audioPicker.launch(arrayOf("audio/*")) }
                .withLeftMargin(dp(8)),
        )
    }

    private fun tempoRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val bpmCard = card()
        bpmCard.addView(label("BPM"))
        bpmValue = value(formatBpm())
        bpmValue.setOnClickListener { promptForBpm() }
        bpmCard.addView(bpmValue)
        bpmCard.addView(
            nudgeRow(listOf("−1" to -1.0, "−.1" to -0.1, "+.1" to 0.1, "+1" to 1.0)) {
                setBpm(timeline.bpm + it)
            },
        )
        row.addView(bpmCard, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val offsetCard = card()
        offsetCard.addView(label("OFFSET"))
        offsetValue = value(formatOffset())
        offsetValue.setOnClickListener { promptForOffset() }
        offsetCard.addView(offsetValue)
        offsetCard.addView(
            nudgeRow(listOf("−10" to -10.0, "−1" to -1.0, "+1" to 1.0, "+10" to 10.0)) {
                setOffset(timeline.offsetMs + it)
            },
        )
        row.addView(
            offsetCard,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(10) },
        )
        return row
    }

    /** Tools and snap resolution, scrollable so nothing is cut off on a phone. */
    private fun toolRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        EditorTool.entries.forEach { tool ->
            val button = pill(tool.label) { selectTool(tool) }.withLeftMargin(dp(5))
            toolButtons[tool] = button
            row.addView(button)
        }

        row.addView(
            TextView(this).apply {
                text = "SNAP"
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                letterSpacing = 0.14f
                setPadding(dp(16), 0, dp(6), 0)
            },
        )
        listOf(1 to "1/4", 2 to "1/8", 4 to "1/16", 8 to "1/32").forEach { (division, text) ->
            val button = pill(text) { selectSnap(division) }.withLeftMargin(dp(5))
            snapButtons[division] = button
            row.addView(button)
        }

        // The initial selection is applied by buildLayout once the grid exists;
        // selectTool writes to it, and it has not been created yet.
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
    }

    private fun transportRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        playButton = pill("PLAY") { togglePlayback() }
        addView(playButton)
        addView(pill("TO START") { seekTo(0.0) }.withLeftMargin(dp(8)))
        addView(pill("FOLLOW") { followPlayhead = true; centreOnPlayhead() }.withLeftMargin(dp(8)))

        // Where the chart is over. Without it the result screen lands the moment
        // the last object is dealt with, cutting off whatever the song was still
        // doing. Long press to go back to that behaviour.
        endButton = pill("SET END") { setEnd(playheadMs) }.withLeftMargin(dp(8))
        endButton.setOnLongClickListener {
            setEnd(0.0)
            toast("Chart now ends with its last object")
            true
        }
        addView(endButton)

        previewButton = pill("TICKS") { togglePreview() }.withLeftMargin(dp(8))
        addView(previewButton)
        applyPreviewStyle()

        timeLabel = TextView(this@EditorActivity).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
        }
        addView(timeLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        updateTimeLabel()

        addView(pill("−") { timeline.zoomBy(0.6f, gridView.width / 2f, gridView.width) })
        addView(
            pill("+") { timeline.zoomBy(1.7f, gridView.width / 2f, gridView.width) }
                .withLeftMargin(dp(8)),
        )
    }

    // --- selection --------------------------------------------------------

    private fun selectTool(tool: EditorTool) {
        gridView.tool = tool
        toolButtons.forEach { (candidate, button) ->
            button.background = rounded(if (candidate == tool) CHIP_ACTIVE else CHIP, dp(16))
            button.setTextColor(if (candidate == tool) Color.WHITE else MUTED)
        }
    }

    private fun selectSnap(division: Int) {
        timeline.snapDivision = division
        snapButtons.forEach { (candidate, button) ->
            button.background = rounded(if (candidate == division) CHIP_ACTIVE else CHIP, dp(16))
            button.setTextColor(if (candidate == division) Color.WHITE else MUTED)
        }
    }

    // --- audio ------------------------------------------------------------

    private fun importAudio(uri: Uri) {
        val name = displayName(uri)
        loadAudio(name) { target ->
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: error("could not read the selected file")
        }
    }

    private fun loadDemoTrack() = loadAudio(DEMO_AUDIO_NAME) { target ->
        assets.open("songs/demo/$DEMO_AUDIO_NAME").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
    }

    /**
     * Copies the audio into the cache, decodes it once, and uses that one decode
     * for both the waveform and playback.
     *
     * The copy is not busywork: decoding wants a real file rather than a content
     * stream, and the same copy is what will go inside the exported `.reb`.
     */
    private fun loadAudio(name: String, copyInto: (File) -> Unit) {
        trackLabel.text = "Decoding…"
        thread(name = "rebound-editor-import") {
            val result = runCatching {
                val target = File(cacheDir, "editor-audio-${sanitise(name)}")
                copyInto(target)

                val pcm = PcmDecoder.decodeFile(target)
                val waveform = Waveform.of(pcm)
                GameAudio.prepareDecoded(this, pcm, name)
                check(GameAudio.start()) { "audio stream failed to open" }

                Triple(target, waveform, name)
            }

            runOnUiThread {
                result
                    .onSuccess { (file, waveform, loadedName) ->
                        audioFile = file
                        audioReady = true
                        durationMs = waveform.durationMs
                        timeline.durationMs = durationMs
                        waveformView.waveform = waveform
                        trackLabel.text = "$loadedName  ·  ${formatTime(durationMs)}"
                        if (songTitle == "Untitled") {
                            songTitle = loadedName.substringBeforeLast('.')
                        }
                        seekTo(0.0)
                    }
                    .onFailure {
                        Log.e(TAG, "import failed", it)
                        trackLabel.text = "No audio loaded"
                        Toast.makeText(
                            this,
                            it.message ?: "That file could not be decoded",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }
    }

    private fun togglePlayback() {
        if (audioFile == null) return
        if (playing) stopPlayback() else startPlayback()
    }

    private fun startPlayback() {
        playing = true
        playButton.text = "PAUSE"
        followPlayhead = true
        previewedUpToMs = playheadMs
        GameAudio.playSongFrom(playheadMs)
        gridView.postOnAnimation(ticker)
    }

    private fun stopPlayback() {
        playing = false
        playButton.text = "PLAY"
        GameAudio.pauseSong()
    }

    private fun setEnd(ms: Double) {
        timeline.endMs = ms
        endButton.text = if (ms <= 0.0) "SET END" else "END ${formatTime(ms)}"
        endButton.setTextColor(if (ms <= 0.0) Color.WHITE else Color.parseColor("#FFD466"))
    }

    private fun togglePreview() {
        previewEnabled = !previewEnabled
        applyPreviewStyle()
    }

    private fun applyPreviewStyle() {
        previewButton.background = rounded(if (previewEnabled) CHIP_ACTIVE else CHIP, dp(16))
        previewButton.setTextColor(if (previewEnabled) Color.WHITE else MUTED)
    }

    /** Re-decodes the working track after a playtest handed the engine away. */
    private fun reopenAudio(file: File) {
        thread(name = "rebound-editor-reopen") {
            val ok = runCatching {
                GameAudio.prepareDecoded(this, PcmDecoder.decodeFile(file), file.name)
                check(GameAudio.start()) { "audio stream failed to open" }
            }.isSuccess
            runOnUiThread { audioReady = ok }
        }
    }

    // --- playtest and export ----------------------------------------------

    /**
     * Builds the chart as a real song package and plays it.
     *
     * Going through the same `.reb` the export produces means a playtest
     * exercises the writer, the archive and the library — if it plays here it
     * will play from the song list.
     */
    private fun playtest() {
        if (audioFile == null) return toast("Import audio first")
        if (chart.size == 0) return toast("Place some objects first")
        if (playing) stopPlayback()

        // The game needs the audio engine to itself.
        GameAudio.stop()
        audioReady = false

        thread(name = "rebound-playtest") {
            val result = runCatching {
                SongLibrary.install(this, ByteArrayInputStream(packageBytes()))
            }
            runOnUiThread {
                result
                    .onSuccess { song ->
                        // The chart being edited, not whichever one happens to be
                        // listed first: a song with several difficulties lists
                        // the older ones ahead of the one just written.
                        startActivity(
                            MainActivity.intentFor(this, song.id, currentChartEntry()),
                        )
                    }
                    .onFailure {
                        Log.e(TAG, "playtest failed", it)
                        toast(it.message ?: "Could not build the chart")
                    }
            }
        }
    }

    /**
     * Puts the chart straight into the library.
     *
     * The common case while working on something is wanting to play it, not
     * wanting a file; [export] is for handing the song to somebody else.
     */
    private fun save() {
        if (audioFile == null) return toast("Import audio first")
        if (chart.size == 0) return toast("Place some objects first")

        thread(name = "rebound-save") {
            val result = runCatching {
                SongLibrary.install(this, ByteArrayInputStream(packageBytes()))
            }
            runOnUiThread {
                result
                    .onSuccess { toast("Saved \"${it.manifest.title}\" to your library") }
                    .onFailure {
                        Log.e(TAG, "save failed", it)
                        toast(it.message ?: "Could not save the chart")
                    }
            }
        }
    }

    private fun export() {
        if (audioFile == null) return toast("Import audio first")
        if (chart.size == 0) return toast("Place some objects first")

        // Built off the main thread: it reads the song's other difficulties back
        // off disk to carry them along.
        thread(name = "rebound-package") {
            val result = runCatching { packageBytes() }
            runOnUiThread {
                result
                    .onSuccess {
                        pendingExport = it
                        exportPicker.launch("${sanitise(songTitle)}.${RebArchive.EXTENSION}")
                    }
                    .onFailure {
                        Log.e(TAG, "export failed", it)
                        toast(it.message ?: "Could not build the chart")
                    }
            }
        }
    }

    private fun writeExport(uri: Uri, bytes: ByteArray) {
        thread(name = "rebound-export") {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("could not write to that location")
                // Also install it, so a chart you just exported is immediately
                // playable without importing your own file back.
                SongLibrary.install(this, ByteArrayInputStream(bytes))
            }
            runOnUiThread {
                result
                    .onSuccess { toast("Exported, and added to your library") }
                    .onFailure {
                        Log.e(TAG, "export failed", it)
                        toast(it.message ?: "Export failed")
                    }
            }
        }
    }

    /** Which entry in the song package this difficulty is written to. */
    private fun currentChartEntry(): String = "${sanitise(songDifficulty).lowercase()}.rbc"

    private fun packageBytes(): ByteArray {
        val file = audioFile ?: error("no audio loaded")
        val extension = file.name.substringAfterLast('.', "wav").lowercase()
        val audioEntry = "audio.$extension"
        val chartEntry = currentChartEntry()

        val meta = ChartMeta(
            title = songTitle,
            artist = songArtist,
            audio = audioEntry,
            bpm = timeline.bpm,
            offsetMs = timeline.offsetMs,
            columns = chart.columns,
            level = songLevel,
            difficulty = songDifficulty,
            endMs = timeline.endMs,
        )

        // Saving a chart adds a difficulty to the song rather than replacing the
        // song. Installing rewrites the whole directory, so anything already
        // there has to be carried across explicitly or it is lost.
        val existing = SongLibrary.list(this).firstOrNull {
            it.manifest.title == songTitle && it.manifest.artist == songArtist
        }

        val entries = linkedMapOf<String, ByteArray>()
        val charts = mutableListOf<String>()

        existing?.manifest?.charts?.forEach { entry ->
            // The one being saved is written fresh below.
            if (entry == chartEntry) return@forEach
            val carried = existing.chartFile(entry)
            if (carried.isFile) {
                entries[entry] = carried.readBytes()
                charts += entry
            }
        }

        entries[audioEntry] = file.readBytes()
        entries[chartEntry] = RbcWriter.write(meta, chart.objects).toByteArray()
        charts += chartEntry

        val manifest = SongManifest(songTitle, songArtist, audioEntry, charts)
        return ByteArrayOutputStream()
            .also { RebArchive.write(it, manifest, entries) }
            .toByteArray()
    }

    private fun promptForSongInfo(onReady: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        fun field(hintText: String, initial: String, numeric: Boolean = false) =
            EditText(this).apply {
                hint = hintText
                setText(initial)
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            }.also { container.addView(it) }

        val title = field("Title", songTitle)
        val artist = field("Artist", songArtist)
        val difficulty = field("Difficulty", songDifficulty)
        val level = field("Level", songLevel.toString(), numeric = true)

        AlertDialog.Builder(this)
            .setTitle("Song info")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                songTitle = title.text.toString().trim().ifEmpty { "Untitled" }
                songArtist = artist.text.toString().trim().ifEmpty { "Unknown" }
                songDifficulty =
                    difficulty.text.toString().trim().uppercase().ifEmpty { "NORMAL" }
                songLevel = level.text.toString().trim().toIntOrNull()?.coerceIn(1, 99) ?: 1
                onReady()
            }
            .show()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun seekTo(ms: Double) {
        setPlayhead(ms.coerceIn(0.0, durationMs))
        previewedUpToMs = playheadMs
        centreOnPlayhead()
        // Restart from the new point so what you hear matches the line you moved.
        if (playing) GameAudio.playSongFrom(playheadMs)
    }

    private fun setPlayhead(ms: Double) {
        playheadMs = ms
        waveformView.playheadMs = ms
        gridView.playheadMs = ms
        if (followPlayhead) centreOnPlayhead()
        updateTimeLabel()
    }

    private fun centreOnPlayhead() {
        if (gridView.width > 0) timeline.centreOn(playheadMs, gridView.width)
    }

    // --- tempo ------------------------------------------------------------

    private fun setBpm(value: Double) {
        timeline.bpm = value
        bpmValue.text = formatBpm()
    }

    private fun setOffset(value: Double) {
        timeline.offsetMs = value
        offsetValue.text = formatOffset()
    }

    private fun promptForBpm() =
        promptForNumber("BPM", formatBpm().removeSuffix(" BPM")) { setBpm(it) }

    private fun promptForOffset() =
        promptForNumber("Offset (ms)", timeline.offsetMs.toString()) { setOffset(it) }

    private fun promptForNumber(title: String, current: String, onValue: (Double) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(current)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Set") { _, _ ->
                input.text.toString().trim().toDoubleOrNull()?.let(onValue)
            }
            .show()
    }

    // --- formatting -------------------------------------------------------

    private fun formatBpm(): String {
        val bpm = timeline.bpm
        return if (abs(bpm - bpm.toInt()) < 1e-6) "${bpm.toInt()} BPM" else "%.2f BPM".format(bpm)
    }

    private fun formatOffset() = "%+.0f ms".format(timeline.offsetMs)

    private fun updateTimeLabel() {
        timeLabel.text = "${formatTime(playheadMs)} / ${formatTime(durationMs)}"
    }

    private fun updateCount() {
        countLabel.text = "${chart.size} objects"
    }

    private fun formatTime(ms: Double): String {
        val total = (ms / 1000.0).toInt().coerceAtLeast(0)
        return "%d:%02d.%01d".format(total / 60, total % 60, ((ms % 1000) / 100).toInt())
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "audio"
    }

    private fun sanitise(name: String) =
        name.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }.joinToString("")

    // --- small view helpers -----------------------------------------------

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(12))
        setPadding(dp(13), dp(9), dp(13), dp(9))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        letterSpacing = 0.16f
    }

    private fun value(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(5))
        isClickable = true
    }

    private fun nudgeRow(steps: List<Pair<String, Double>>, onStep: (Double) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            steps.forEach { (text, delta) ->
                addView(pill(text) { onStep(delta) }.apply { setRightMargin(dp(5)) })
            }
        }

    private fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = rounded(CHIP, dp(16))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    }

    private fun TextView.withLeftMargin(margin: Int): TextView = apply {
        (layoutParams as LinearLayout.LayoutParams).leftMargin = margin
    }

    private fun TextView.setRightMargin(margin: Int) {
        (layoutParams as LinearLayout.LayoutParams).rightMargin = margin
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

    companion object {
        /** Library song to reopen. Absent starts an empty chart. */
        const val EXTRA_SONG_ID = "songId"

        /** Which chart within that song. Absent means its first difficulty. */
        const val EXTRA_CHART = "chart"

        /** Start a new difficulty for that song rather than reopening one. */
        const val EXTRA_NEW_CHART = "newChart"

        fun intentFor(context: Context, songId: String, chartEntry: String): Intent =
            Intent(context, EditorActivity::class.java)
                .putExtra(EXTRA_SONG_ID, songId)
                .putExtra(EXTRA_CHART, chartEntry)

        /** Opens the song's audio with an empty grid, to add a difficulty to it. */
        fun newChartIntent(context: Context, songId: String): Intent =
            Intent(context, EditorActivity::class.java)
                .putExtra(EXTRA_SONG_ID, songId)
                .putExtra(EXTRA_NEW_CHART, true)

        /** Tried in order when naming a song's next difficulty. */
        private val DIFFICULTY_NAMES = listOf("NORMAL", "HARD", "EXTRA", "EASY")

        private const val TAG = "ReboundEditor"
        private const val DEMO_AUDIO_NAME = "demo.wav"
        private const val BACKGROUND = 0xFF05080F.toInt()
        private const val CARD = 0xFF101725.toInt()
        private const val CHIP = 0xFF1B2740.toInt()
        private const val CHIP_ACTIVE = 0xFF2C3E63.toInt()
        private const val ACCENT = 0xFFFF6FA0.toInt()
        private const val MUTED = 0xFF8FA3BF.toInt()
    }
}
