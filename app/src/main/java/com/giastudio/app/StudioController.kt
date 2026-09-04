package com.giastudio.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.giastudio.app.audio.AudioCleanup
import com.giastudio.app.audio.AudioEngine
import com.giastudio.app.audio.Mixdown
import com.giastudio.app.audio.MixdownException
import com.giastudio.app.audio.PitchResult
import com.giastudio.app.audio.buildSnap
import com.giastudio.app.audio.readWavToMono
import com.giastudio.app.audio.resampleLinear
import com.giastudio.app.audio.writeMono16Wav
import com.giastudio.app.model.Clip
import com.giastudio.app.model.MAX_CLIPS_PER_TRACK
import com.giastudio.app.model.MAX_TRACKS
import com.giastudio.app.model.StudioProject
import com.giastudio.app.model.Track
import com.giastudio.app.model.projectFromJson
import com.giastudio.app.model.toJson
import com.giastudio.app.music.DemoSongs
import com.giastudio.app.music.SongRender
import com.giastudio.app.music.StemSpec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import org.json.JSONObject

/** One-tap offline cleanup operations shown in the clip inspector. */
enum class CleanOp(val label: String, val blurb: String) {
    NORMALIZE("Normalize", "Boost the whole clip to a strong, clean level."),
    REMOVE_DC("Fix DC", "Remove offset/rumble below 30 Hz that causes a muddy, thumping sound."),
    DENOISE("Denoise", "Detect the noise floor and silence quiet hiss between sounds."),
    TRIM("Trim silence", "Cut dead air from the start and end of the clip."),
}

/**
 * Bridges the Compose UI (main thread) with the audio engine, the session
 * model and the app's private file storage.
 */
class StudioController(private val appContext: Context) {

    private val engine = AudioEngine(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val recordingsDir = File(appContext.filesDir, "recordings").apply { mkdirs() }
    private val projectsDir = File(appContext.filesDir, "projects").apply { mkdirs() }
    private val exportsDir = File(appContext.cacheDir, "exports").apply { mkdirs() }

    /** clipId -> decoded mono samples at [engine.sampleRate]. */
    private val sampleCache = HashMap<String, FloatArray>()

    // ------------------------------------------------------------ UI state
    var project by mutableStateOf(StudioProject())
        private set
    var playheadSec by mutableStateOf(0.0)
        private set
    var playing by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var tuning by mutableStateOf(false)
        private set
    var monitorOn by mutableStateOf(false)
        private set
    var recLevel by mutableStateOf(0f)
        private set
    var previewing by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var busyText by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
        private set
    var toastMsg by mutableStateOf<String?>(null)
        private set
    var dirty by mutableStateOf(false)
        private set
    var selectedTrackId by mutableStateOf(0)
        private set
    var selectedClipId by mutableStateOf<String?>(null)
        private set

    // Tuner readout
    var tunerNote by mutableStateOf("–")
        private set
    var tunerCents by mutableStateOf(0)
        private set
    var tunerFreq by mutableStateOf(0.0)
        private set
    var tunerLevel by mutableStateOf(0f)
        private set

    private var armTrackId = 0
    private var pendingDiscard = false

    val anyArmed: Boolean get() = armTrackId != 0

    /** The engine's native sample rate — Create tab renders previews at this. */
    val sampleRate: Int get() = engine.sampleRate

    fun clearClipSelection() {
        selectedClipId = null
    }

    fun setBpm(bpm: Int) {
        project = project.copy(bpm = bpm.coerceIn(40, 300))
        dirty = true
    }

    fun renameSession(name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        project = project.copy(name = clean)
        dirty = true
    }

    fun toggleMonitor() {
        monitorOn = !monitorOn
    }

    init {
        engine.onPosition = { sec -> main { playheadSec = sec } }
        engine.onTransportStopped = {
            main {
                playing = false
                playheadSec = engine.positionSec
                if (recording) recording = false
            }
        }
        engine.onError = { msg -> main { toast(msg) } }
        engine.onRecordLevel = { lv -> main { recLevel = lv } }
        engine.onRecordingFinished = { file, _ ->
            main { finishRecording(file) }
        }
        engine.onTunerResult = { result, level ->
            main { onTunerSample(result, level) }
        }
    }

    private fun main(block: () -> Unit) {
        mainHandler.post(block)
    }

    fun toast(msg: String) {
        main { toastMsg = msg }
    }

    fun consumeToast() {
        toastMsg = null
    }

    // ------------------------------------------------------------- snapshot
    private fun pushSnapshot() {
        engine.currentSnap = buildSnap(project, engine.sampleRate) { id -> sampleCache[id] }
    }

    // ------------------------------------------------------------- projects
    fun newSession(name: String = "My First Session") {
        stopTransport()
        project = StudioProject(name = name)
        sampleCache.clear()
        selectedClipId = null
        selectedTrackId = 0
        armTrackId = 0
        playheadSec = 0.0
        pushSnapshot()
        dirty = false
    }

    fun sessionFileName(): String =
        sanitize(project.name) + ".gia.json"

    fun listProjects(): List<String> =
        (projectsDir.listFiles()?.mapNotNull { it.name } ?: emptyList())
            .filter { it.endsWith(".gia.json") }
            .sortedDescending()

    fun saveSession() {
        val file = File(projectsDir, sessionFileName())
        busy("Saving session…") {
            try {
                file.writeText(project.toJson().toString(2))
                dirty = false
                toast("Saved “${project.name}”")
            } catch (e: Exception) {
                toast("Could not save: ${e.message}")
            }
        }
    }

    fun loadSession(fileName: String) {
        stopTransport()
        busy("Opening session…") {
            try {
                val json = JSONObject(File(projectsDir, fileName).readText())
                val loaded = projectFromJson(json)
                sampleCache.clear()
                val missing = mutableListOf<String>()
                val idsToDrop = HashSet<String>()
                for (t in loaded.tracks) {
                    val keep = t.clips.filter { c ->
                        val f = File(recordingsDir, c.fileName)
                        if (!f.exists()) {
                            missing.add(c.name)
                            false
                        } else {
                            try {
                                val dec = readWavToMono(f)
                                val samples = if (dec.sampleRate == engine.sampleRate) {
                                    dec.samples
                                } else {
                                    resampleLinear(dec.samples, dec.sampleRate, engine.sampleRate)
                                }
                                sampleCache[c.id] = samples
                                true
                            } catch (e: Exception) {
                                missing.add(c.name)
                                false
                            }
                        }
                    }
                    t.clips.clear()
                    t.clips.addAll(keep)
                    if (t.clips.isEmpty()) idsToDrop.add(t.name)
                }
                project = loaded
                pushSnapshot()
                dirty = false
                playheadSec = 0.0
                selectedClipId = null
                toast(
                    if (missing.isEmpty()) "Opened “${loaded.name}”"
                    else "Opened; ${missing.size} clip(s) missing: ${missing.joinToString(", ")}"
                )
            } catch (e: Exception) {
                toast("Could not open session: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ transport
    fun togglePlayPause() {
        if (recording || tuning) return
        if (playing) {
            engine.pause()
            playing = false
        } else {
            pushSnapshot()
            engine.requestPlay()
            playing = true
        }
    }

    fun stopTransport() {
        engine.stop()
        engine.stopPreview()
        previewing = false
        playing = false
        recording = false
        playheadSec = 0.0
        recLevel = 0f
    }

    fun seekTo(sec: Double) {
        val end = project.arrangementEndSec()
        val s = sec.coerceIn(0.0, end.coerceAtLeast(0.0))
        engine.seekSeconds(s)
        playheadSec = s
    }

    fun toggleLoop() {
        engine.loopEnabled = !engine.loopEnabled
    }

    val loopEnabled: Boolean get() = engine.loopEnabled

    // ------------------------------------------------------------- recording
    fun setArmed(trackId: Int) {
        val t = trackById(trackId) ?: return
        val nowArmed = !t.armed
        armTrackId = if (nowArmed) trackId else 0
        project = project.copy(
            tracks = project.tracks.map { tr ->
                if (tr.id == trackId) tr.copy(armed = nowArmed) else tr.copy(armed = false)
            }.toMutableList()
        )
        dirty = true
        if (nowArmed) selectedTrackId = trackId
    }

    fun startRecording() {
        if (recording || playing || tuning) return
        if (armTrackId == 0) {
            toast("Arm a track first — tap the red ● on a track header.")
            return
        }
        val track = trackById(armTrackId) ?: return
        if (track.clips.size >= MAX_CLIPS_PER_TRACK) {
            toast("This track is full (${MAX_CLIPS_PER_TRACK} clips). Delete one first.")
            return
        }
        val fileName = "clip_${System.currentTimeMillis()}_${track.id}.wav"
        val file = File(recordingsDir, fileName)
        pendingDiscard = false
        recording = true
        recLevel = 0f
        selectedTrackId = track.id
        selectedClipId = null
        engine.startRecording(file, gateOn = track.inputGate, monitorOn = monitorOn)
        dirty = true
    }

    fun cancelRecording() {
        if (!recording) return
        pendingDiscard = true
        engine.stopRecording()
    }

    fun stopRecording() {
        if (!recording) return
        engine.stopRecording()
    }

    private fun finishRecording(file: File) {
        if (pendingDiscard) {
            file.delete()
            pendingDiscard = false
            recording = false
            recLevel = 0f
            return
        }
        val trackId = armTrackId
        val track = trackById(trackId)
        recording = false
        recLevel = 0f
        if (track == null) {
            file.delete()
            return
        }
        busy("Processing take…") {
            try {
                val dec = readWavToMono(file)
                if (dec.samples.isEmpty()) {
                    file.delete()
                    toast("Nothing was captured — speak up, or check the mic.")
                    return@busy
                }
                var samples = if (dec.sampleRate == engine.sampleRate) {
                    dec.samples
                } else {
                    resampleLinear(dec.samples, dec.sampleRate, engine.sampleRate)
                }
                var note = ""
                if (track.inputGate) {
                    samples = AudioCleanup.removeDc(samples)
                    samples = AudioCleanup.noiseGate(samples, engine.sampleRate)
                    note = " • auto-cleaned"
                }
                // Rewrite the WAV in the engine's sample rate so it loads fast
                // and exactly matches the in-memory buffer.
                writeMono16Wav(file, samples, engine.sampleRate)
                sampleCache.clear() // stale decoded copies
                main {
                    val t = trackById(trackId)
                    if (t == null) {
                        file.delete()
                        return@main
                    }
                    val takeNumber = t.clips.size + 1
                    val clip = Clip(
                        id = "clip_${System.currentTimeMillis()}",
                        name = "${t.name} take $takeNumber",
                        fileName = file.name,
                        startSec = t.contentEndSec(),
                        lengthSec = samples.size.toDouble() / engine.sampleRate,
                    )
                    val updated = t.copy(clips = (t.clips + clip).toMutableList())
                    project = project.copy(
                        tracks = project.tracks.map { if (it.id == t.id) updated else it }.toMutableList()
                    )
                    pushSnapshot()
                    selectedClipId = clip.id
                    dirty = true
                    toast("Recorded ${clip.name}${note}")
                }
            } catch (e: Exception) {
                toast("Recording problem: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------- tracks
    private fun trackById(id: Int): Track? = project.tracks.firstOrNull { it.id == id }

    private fun mutateTrack(id: Int, change: (Track) -> Track) {
        project = project.copy(tracks = project.tracks.map { if (it.id == id) change(it) else it }.toMutableList())
        pushSnapshot()
        dirty = true
    }

    fun selectTrack(id: Int) {
        selectedTrackId = id
    }

    fun toggleMute(id: Int) {
        mutateTrack(id) { it.copy(muted = !it.muted) }
    }

    fun toggleSolo(id: Int) {
        mutateTrack(id) { it.copy(soloed = !it.soloed) }
    }

    fun setVolume(id: Int, v: Float) {
        mutateTrack(id) { it.copy(volume = v.coerceIn(0f, 1.5f)) }
    }

    fun setPan(id: Int, p: Float) {
        mutateTrack(id) { it.copy(pan = p.coerceIn(-1f, 1f)) }
    }

    fun toggleInputGate(id: Int) {
        mutateTrack(id) { it.copy(inputGate = !it.inputGate) }
    }

    fun setMasterVolume(v: Float) {
        project = project.copy(masterVolume = v.coerceIn(0f, 1.5f))
        pushSnapshot()
        dirty = true
    }

    fun toggleMasterLimiter() {
        project = project.copy(masterLimiter = !project.masterLimiter)
        pushSnapshot()
        dirty = true
    }

    fun addTrack() {
        if (project.tracks.size >= MAX_TRACKS) {
            toast("GIA Studio allows up to ${MAX_TRACKS} tracks in this version.")
            return
        }
        val n = project.tracks.size + 1
        val track = Track(
            id = project.nextTrackId(),
            name = "Track $n",
            color = project.tracks.size % 8,
            volume = 0.85f,
        )
        project = project.copy(tracks = (project.tracks + track).toMutableList())
        pushSnapshot()
        dirty = true
        selectedTrackId = track.id
    }

    fun removeTrack(id: Int) {
        if (project.tracks.size <= 1) {
            toast("A session needs at least one track.")
            return
        }
        val t = trackById(id) ?: return
        t.clips.forEach { c ->
            File(recordingsDir, c.fileName).delete()
            sampleCache.remove(c.id)
        }
        project = project.copy(tracks = project.tracks.filter { it.id != id }.toMutableList())
        if (selectedTrackId == id) selectedTrackId = project.tracks.firstOrNull()?.id ?: 0
        if (armTrackId == id) armTrackId = 0
        pushSnapshot()
        dirty = true
    }

    fun renameTrack(id: Int, name: String) {
        val clean = name.trim().take(18)
        if (clean.isEmpty()) return
        mutateTrack(id) { it.copy(name = clean) }
    }

    // ------------------------------------------------------------- FX edits
    fun setFx(id: Int, change: (com.giastudio.app.model.FxState) -> com.giastudio.app.model.FxState) {
        mutateTrack(id) { it.copy(fx = change(it.fx)) }
    }

    // ---------------------------------------------------------------- clips
    fun selectClip(trackId: Int, clipId: String) {
        selectedTrackId = trackId
        selectedClipId = clipId
    }

    fun setClipGain(trackId: Int, clipId: String, g: Float) {
        mutateClip(trackId, clipId) { it.copy(gain = g.coerceIn(0f, 2f)) }
    }

    fun setClipFadeIn(trackId: Int, clipId: String, sec: Double) {
        mutateClip(trackId, clipId) { it.copy(fadeInSec = sec.coerceIn(0.0, 4.0)) }
    }

    fun setClipFadeOut(trackId: Int, clipId: String, sec: Double) {
        mutateClip(trackId, clipId) { it.copy(fadeOutSec = sec.coerceIn(0.0, 4.0)) }
    }

    private fun mutateClip(trackId: Int, clipId: String, change: (Clip) -> Clip) {
        mutateTrack(trackId) { t ->
            t.copy(clips = t.clips.map { if (it.id == clipId) change(it) else it }.toMutableList())
        }
    }

    fun deleteClip(trackId: Int, clipId: String) {
        val t = trackById(trackId) ?: return
        val clip = t.clips.firstOrNull { it.id == clipId } ?: return
        File(recordingsDir, clip.fileName).delete()
        sampleCache.remove(clipId)
        project = project.copy(
            tracks = project.tracks.map {
                if (it.id == trackId) {
                    it.copy(clips = it.clips.filterNot { c -> c.id == clipId }.toMutableList())
                } else {
                    it
                }
            }.toMutableList()
        )
        if (selectedClipId == clipId) selectedClipId = null
        pushSnapshot()
        dirty = true
    }

    /** Runs an offline cleanup op on a clip (background; updates file+model). */
    fun cleanClip(trackId: Int, clipId: String, op: CleanOp) {
        val t = trackById(trackId) ?: return
        val clip = t.clips.firstOrNull { it.id == clipId } ?: return
        busy("${op.label}…") {
            try {
                val samples = sampleCache[clipId] ?: run {
                    val dec = readWavToMono(File(recordingsDir, clip.fileName))
                    if (dec.sampleRate == engine.sampleRate) dec.samples
                    else resampleLinear(dec.samples, dec.sampleRate, engine.sampleRate)
                }
                val cleaned: FloatArray
                var message = ""
                when (op) {
                    CleanOp.NORMALIZE -> {
                        cleaned = AudioCleanup.normalizePeak(samples)
                        message = "Peak ${fmtDb(AudioCleanup.peakDb(samples))} → ${fmtDb(AudioCleanup.peakDb(cleaned))}"
                    }
                    CleanOp.REMOVE_DC -> {
                        cleaned = AudioCleanup.removeDc(samples)
                        message = "DC offset and rumble removed."
                    }
                    CleanOp.DENOISE -> {
                        cleaned = AudioCleanup.noiseGate(samples, engine.sampleRate)
                        message = "Noise floor silenced."
                    }
                    CleanOp.TRIM -> {
                        cleaned = AudioCleanup.trimSilence(samples, engine.sampleRate)
                        val cutSec = (samples.size - cleaned.size).toDouble() / engine.sampleRate
                        message = if (cutSec > 0.01) {
                            "Trimmed %.2f s of silence.".format(Locale.US, cutSec)
                        } else {
                            "Nothing to trim."
                        }
                    }
                }
                if (cleaned.isEmpty()) {
                    main { deleteClip(trackId, clipId) }
                    return@busy
                }
                val file = File(recordingsDir, clip.fileName)
                writeMono16Wav(file, cleaned, engine.sampleRate)
                sampleCache[clipId] = cleaned
                main {
                    mutateClip(trackId, clipId) {
                        it.copy(lengthSec = cleaned.size.toDouble() / engine.sampleRate)
                    }
                    toast(message)
                }
            } catch (e: Exception) {
                toast("Clean-up failed: ${e.message}")
            }
        }
    }

    private fun fmtDb(d: Double): String {
        if (d <= -120.0) return "−∞ dB"
        return "%.1f dB".format(Locale.US, d)
    }

    // --------------------------------------------------------- built-in music
    /** Play a rendered buffer (pad hit or full pattern loop) from the Create tab. */
    fun playPreview(samples: FloatArray, loop: Boolean) {
        engine.playPreview(samples, loop)
        previewing = engine.previewing
    }

    fun stopPreview() {
        engine.stopPreview()
        previewing = false
    }

    /**
     * Render a generated layer (beat/melody/chord stem) to a WAV clip and
     * place it in the session. New instruments get their own lane starting at
     * 0:00 so layers stack; adding to an existing instrument lane chains a
     * new section after its content, snapped to the bar grid.
     */
    fun addMusicStem(spec: StemSpec) {
        if (busy) return
        busy("Rendering ${spec.kindName}…") {
            try {
                val samples = SongRender.renderStem(spec, engine.sampleRate)
                if (samples.isEmpty()) {
                    toast("Nothing to add — tap some steps first.")
                    return@busy
                }
                if (spec.startSec != null) {
                    commitStemAt(spec, samples, spec.startSec)
                } else {
                    val barSec = 60.0 / spec.bpm.coerceIn(30, 300)
                    val lane = project.tracks.firstOrNull { it.name == spec.trackName }
                    val trackId: Int
                    val startSec: Double
                    if (lane != null) {
                        trackId = lane.id
                        startSec = if (lane.clips.isEmpty()) 0.0 else {
                            ceil(lane.contentEndSec() / barSec) * barSec
                        }
                    } else if (project.tracks.size < MAX_TRACKS) {
                        val t = Track(
                            id = project.nextTrackId(),
                            name = spec.trackName,
                            color = spec.color,
                            volume = spec.volume,
                            pan = spec.pan,
                        )
                        project = project.copy(tracks = (project.tracks + t).toMutableList())
                        trackId = t.id
                        startSec = 0.0
                    } else {
                        val quiet = project.tracks.minByOrNull { it.contentEndSec() }
                            ?: return@busy
                        trackId = quiet.id
                        startSec = if (quiet.clips.isEmpty()) 0.0 else {
                            ceil(quiet.contentEndSec() / barSec) * barSec
                        }
                    }
                    commitStemAt(spec, samples, startSec, trackId)
                }
            } catch (e: Exception) {
                toast("Could not create ${spec.kindName}: ${e.message}")
            }
        }
    }

    private fun commitStemAt(spec: StemSpec, samples: FloatArray, startSec: Double, forcedTrackId: Int? = null) {
        val track = forcedTrackId?.let { trackById(it) }
            ?: project.tracks.firstOrNull { it.name == spec.trackName }
            ?: return
        if (track.clips.size >= MAX_CLIPS_PER_TRACK) {
            toast("That lane is full (${MAX_CLIPS_PER_TRACK} clips). Remove one on the Studio tab first.")
            return
        }
        val stamp = System.currentTimeMillis()
        val fileName = "gen_${stamp}_${track.id}.wav"
        val file = File(recordingsDir, fileName)
        writeMono16Wav(file, samples, engine.sampleRate)
        val clipId = "gen_$stamp"
        sampleCache[clipId] = samples
        val clip = Clip(
            id = clipId,
            name = spec.label,
            fileName = fileName,
            startSec = startSec,
            lengthSec = samples.size.toDouble() / engine.sampleRate,
        )
        val updated = track.copy(clips = (track.clips + clip).toMutableList())
        project = project.copy(
            tracks = project.tracks.map { if (it.id == track.id) updated else it }.toMutableList()
        )
        pushSnapshot()
        selectedClipId = clipId
        dirty = true
        toast(
            "Added “${clip.name}” on ${updated.name} at ${fmtMinSec(startSec)} — Studio tab ▶ to hear it."
        )
    }

    /** Replace the session with the built-in demo song (regenerates audio). */
    fun loadDemoSong() {
        if (busy) return
        busy("Building demo song…") {
            try {
                val stems = DemoSongs.afterglow()
                val rendered = stems.map { spec -> spec to SongRender.renderStem(spec, engine.sampleRate) }
                stopTransport()
                project = StudioProject(name = DemoSongs.NAME, bpm = DemoSongs.BPM)
                sampleCache.clear()
                selectedClipId = null
                selectedTrackId = 0
                armTrackId = 0
                // Keep the first five default lanes, re-cast as the demo's own:
                // Vocal (empty, armed, ready for you) Beat / Keys / Bass / Lead.
                val renames = listOf(5 to "Lead")
                project = project.copy(
                    tracks = project.tracks.map { t ->
                        val renamed = renames.firstOrNull { it.first == t.id }
                            ?.let { t.copy(name = it.second) } ?: t
                        when (renamed.name) {
                            "Beat" -> renamed.copy(volume = 0.8f, pan = 0f)
                            "Keys" -> renamed.copy(
                                volume = 0.7f, pan = -0.15f,
                                fx = renamed.fx.copy(reverbOn = true, reverbMix = 0.16f),
                            )
                            "Bass" -> renamed.copy(
                                volume = 0.8f, pan = 0.05f,
                                fx = renamed.fx.copy(hpOn = true, hpHz = 45),
                            )
                            "Lead" -> renamed.copy(
                                volume = 0.75f, pan = 0.15f,
                                fx = renamed.fx.copy(
                                    delayOn = true, delayMs = 300f, delayFeedback = 0.3f,
                                    delayMix = 0.2f, reverbOn = true, reverbMix = 0.14f,
                                ),
                            )
                            else -> renamed
                        }
                    }.toMutableList()
                )
                while (project.tracks.size > 5) {
                    val last = project.tracks.maxByOrNull { it.id } ?: break
                    removeTrack(last.id)
                }
                rendered.forEach { (spec, samples) ->
                    val laneName = spec.trackName
                    val lane = project.tracks.firstOrNull { it.name == laneName }
                    if (lane == null) return@forEach
                    val fileName = "demo_${laneName.lowercase().replace(" ", "_")}.wav"
                    writeMono16Wav(File(recordingsDir, fileName), samples, engine.sampleRate)
                    val clipId = "demo_${laneName.lowercase().replace(" ", "_")}"
                    sampleCache[clipId] = samples
                    val clip = Clip(
                        id = clipId,
                        name = spec.label,
                        fileName = fileName,
                        startSec = spec.startSec ?: 0.0,
                        lengthSec = samples.size.toDouble() / engine.sampleRate,
                    )
                    val updated = lane.copy(clips = (lane.clips + clip).toMutableList())
                    project = project.copy(
                        tracks = project.tracks.map { if (it.id == lane.id) updated else it }.toMutableList()
                    )
                }
                pushSnapshot()
                playheadSec = 0.0
                dirty = true
                toast(
                    "“${DemoSongs.NAME}” is ready — ▶ on the Studio tab. Tap the red ● on Vocal, then REC, to sing over it."
                )
            } catch (e: Exception) {
                toast("Demo failed: ${e.message}")
            }
        }
    }

    private fun fmtMinSec(sec: Double): String {
        val total = sec.toInt().coerceAtLeast(0)
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    // ---------------------------------------------------------------- import
    fun importWav(trackId: Int, uri: Uri) {
        val t = trackById(trackId) ?: return
        if (t.clips.size >= MAX_CLIPS_PER_TRACK) {
            toast("Track is full (${MAX_CLIPS_PER_TRACK} clips).")
            return
        }
        busy("Importing audio…") {
            try {
                val displayName = queryDisplayName(uri)
                val fileName = "import_${System.currentTimeMillis()}.wav"
                val dest = File(recordingsDir, fileName)
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw Exception("Could not read that file")
                val dec = readWavToMono(dest)
                if (dec.samples.isEmpty()) throw Exception("No audio found in that file")
                val samples = if (dec.sampleRate == engine.sampleRate) {
                    dec.samples
                } else {
                    resampleLinear(dec.samples, dec.sampleRate, engine.sampleRate)
                }
                writeMono16Wav(dest, samples, engine.sampleRate)
                main {
                    val name = (displayName ?: "Imported").substringBeforeLast('.').take(24)
                    val clip = Clip(
                        id = "clip_${System.currentTimeMillis()}",
                        name = name.ifEmpty { "Imported" },
                        fileName = fileName,
                        startSec = t.contentEndSec(),
                        lengthSec = samples.size.toDouble() / engine.sampleRate,
                    )
                    mutateTrack(trackId) { it.copy(clips = (it.clips + clip).toMutableList()) }
                    selectedClipId = clip.id
                    toast("Imported ${clip.name}")
                }
            } catch (e: Exception) {
                toast("Import failed (WAV only in this version): ${e.message}")
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        appContext.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    // ----------------------------------------------------------------- tuner
    fun startTuner() {
        if (tuning || recording || playing) return
        tuning = true
        tunerNote = "…"
        tunerCents = 0
        tunerFreq = 0.0
        tunerLevel = 0f
        engine.startTuner()
    }

    fun stopTuner() {
        tuning = false
        engine.stopTuner()
        tunerNote = "–"
        tunerCents = 0
        tunerFreq = 0.0
        tunerLevel = 0f
    }

    private fun onTunerSample(result: PitchResult?, level: Float) {
        tunerLevel = level
        if (result == null) {
            tunerNote = if (level < 0.01f) "…" else "·"
            tunerFreq = 0.0
            tunerCents = 0
            return
        }
        val freq = result.frequency
        tunerFreq = freq
        tunerNote = com.giastudio.app.audio.Notes.nameOf(
            com.giastudio.app.audio.Notes.midiFor(freq)
        )
        tunerCents = com.giastudio.app.audio.Notes.centsOff(freq)
    }

    // ---------------------------------------------------------------- export
    fun exportAndShare(bitDepth: Int, normalize: Boolean) {
        if (busy) return
        busy("Rendering mix…", progressable = true) {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                val out = File(exportsDir, "GIA_${sanitize(project.name)}_$stamp.wav")
                Mixdown.renderToFile(
                    project = project,
                    target = out,
                    sampleRate = engine.sampleRate,
                    bitDepth = bitDepth,
                    normalize = normalize,
                    sampleProvider = { id -> sampleCache[id] },
                ) { p -> main { progress = p } }
                progress = 0f
                main { shareFile(out) }
            } catch (e: MixdownException) {
                toast(e.message ?: "Export failed")
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
            }
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                appContext, appContext.packageName + ".fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, project.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share “${project.name}” mix")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(chooser)
        } catch (e: Exception) {
            toast("Could not open the share sheet: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- misc
    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Session" }

    private fun busy(label: String, progressable: Boolean = false, work: () -> Unit) {
        busyText = label
        busy = true
        if (progressable) progress = 0f
        Thread({
            try {
                work()
            } finally {
                main {
                    busy = false
                    busyText = ""
                    progress = 0f
                }
            }
        }, "gia-worker").start()
    }

    fun release() {
        engine.release()
    }
}
