package com.giastudio.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giastudio.app.StudioController
import com.giastudio.app.audio.Notes
import com.giastudio.app.music.MusicEvent
import com.giastudio.app.music.SongRender
import com.giastudio.app.music.StemSpec
import com.giastudio.app.music.Synth
import com.giastudio.app.music.SynthSound
import com.giastudio.app.ui.theme.Neo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RollNote(val midi: Int, val step: Int, val length: Int, val velocity: Float = 0.86f)
private val rollSounds = listOf(SynthSound.EPIANO, SynthSound.PLUCK, SynthSound.LEAD, SynthSound.PAD, SynthSound.BASS, SynthSound.STRINGS, SynthSound.ORGAN, SynthSound.BELL)

/** Touch-first piano roll backed by GIA's existing synth and arrangement renderer. */
@Composable
fun PianoRollScreen(ctrl: StudioController) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val prefs = remember { context.getSharedPreferences("gia_piano_roll", Context.MODE_PRIVATE) }
    val notes = remember {
        mutableStateListOf<RollNote>().apply {
            runCatching {
                val saved = JSONArray(prefs.getString("notes", "[]") ?: "[]")
                for (i in 0 until saved.length()) {
                    val item = saved.getJSONObject(i)
                    add(RollNote(
                        midi = item.optInt("midi", 60).coerceIn(0, 127),
                        step = item.optInt("step", 0).coerceIn(0, 63),
                        length = item.optInt("length", 2).coerceIn(1, 16),
                        velocity = item.optDouble("velocity", 0.86).toFloat().coerceIn(0.05f, 1f),
                    ))
                }
            }
        }
    }
    var selectedKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val undoStack = remember { mutableStateListOf<List<RollNote>>() }
    val redoStack = remember { mutableStateListOf<List<RollNote>>() }
    fun pushUndo() {
        undoStack.add(notes.toList())
        if (undoStack.size > 30) undoStack.removeAt(0)
        redoStack.clear()
    }
    fun restoreNotes(snapshot: List<RollNote>) {
        notes.clear()
        notes.addAll(snapshot)
        selectedKey = null
    }
    val scope = rememberCoroutineScope()
    var sound by remember {
        mutableStateOf(runCatching {
            SynthSound.valueOf(prefs.getString("sound", "EPIANO") ?: "EPIANO")
        }.getOrDefault(SynthSound.EPIANO))
    }
    var noteLength by remember { mutableIntStateOf(prefs.getInt("noteLength", 2).coerceIn(1, 8)) }
    var velocity by remember { mutableFloatStateOf(prefs.getFloat("velocity", 0.86f).coerceIn(0.05f, 1f)) }
    var swing by remember { mutableFloatStateOf(prefs.getFloat("swing", 0f).coerceIn(0f, 0.6f)) }
    var bars by remember { mutableIntStateOf(prefs.getInt("bars", 2).coerceIn(1, 4)) }
    var octave by remember { mutableIntStateOf(prefs.getInt("octave", 4).coerceIn(2, 6)) }
    var rootKey by remember { mutableIntStateOf(prefs.getInt("rootKey", 0).coerceIn(0, 11)) }
    var cellWidth by remember { mutableIntStateOf(prefs.getInt("cellWidth", 38).coerceIn(28, 48)) }
    var playing by remember { mutableStateOf(false) }
    var velocityUndoSaved by remember { mutableStateOf(false) }
    var pendingMidiExport by remember { mutableStateOf<ByteArray?>(null) }
    val importMidiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read MIDI file")
                decodeStandardMidi(bytes)
            }.onSuccess { imported ->
                if (imported.notes.isEmpty()) {
                    ctrl.toast("That MIDI file contains no readable notes.")
                } else {
                    pushUndo()
                    notes.clear()
                    notes.addAll(imported.notes)
                    selectedKey = null
                    bars = ((imported.notes.maxOfOrNull { it.step + it.length } ?: 16) + 15).div(16).coerceIn(1, 4)
                    ctrl.setBpm(imported.bpm)
                    ctrl.toast("Imported ${imported.notes.size} MIDI notes.")
                }
            }.onFailure {
                ctrl.toast("Couldn't import that MIDI file.")
            }
        }
    }
    val exportMidiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/midi")) { uri: Uri? ->
        val bytes = pendingMidiExport
        if (uri != null && bytes != null) {
            runCatching {
                val stream = context.contentResolver.openOutputStream(uri) ?: error("No output stream")
                stream.use { it.write(bytes) }
            }
                .onSuccess { ctrl.toast("MIDI file exported.") }
                .onFailure { ctrl.toast("Couldn't save MIDI file.") }
        }
        pendingMidiExport = null
    }
    val selectedNote = notes.firstOrNull { it.midi == selectedKey?.first && it.step == selectedKey?.second }
    val bpm = ctrl.project.bpm.coerceIn(30, 300)
    val steps = bars * 16
    val pitches = (48 + (octave - 4) * 12..71 + (octave - 4) * 12).reversed().toList()
    val blackKeys = setOf(1, 3, 6, 8, 10)
    val scalePitchClasses = remember(rootKey) { setOf(0, 2, 4, 5, 7, 9, 11).map { (it + rootKey) % 12 }.toSet() }

    LaunchedEffect(notes.toList(), sound, noteLength, velocity, swing, bars, octave, rootKey, cellWidth) {
        val saved = JSONArray()
        notes.forEach { n ->
            saved.put(JSONObject().put("midi", n.midi).put("step", n.step).put("length", n.length).put("velocity", n.velocity.toDouble()))
        }
        prefs.edit()
            .putString("notes", saved.toString())
            .putString("sound", sound.name)
            .putInt("noteLength", noteLength)
            .putFloat("velocity", velocity)
            .putFloat("swing", swing)
            .putInt("bars", bars)
            .putInt("octave", octave)
            .putInt("rootKey", rootKey)
            .putInt("cellWidth", cellWidth)
            .apply()
    }

    fun savePattern(slot: String) {
        val payload = JSONObject().apply {
            put("bpm", bpm)
            put("sound", sound.name)
            put("rootKey", rootKey)
            put("swing", swing.toDouble())
            put("notes", JSONArray().apply {
                notes.forEach { n ->
                    put(JSONObject().put("midi", n.midi).put("step", n.step).put("length", n.length).put("velocity", n.velocity.toDouble()))
                }
            })
        }
        prefs.edit().putString("pattern_$slot", payload.toString()).apply()
        ctrl.toast("Saved pattern $slot.")
    }

    fun loadPattern(slot: String) {
        val raw = prefs.getString("pattern_$slot", null)
        if (raw == null) {
            ctrl.toast("Pattern $slot is empty. Save a pattern there first.")
            return
        }
        runCatching {
            val payload = JSONObject(raw)
            val savedNotes = payload.optJSONArray("notes") ?: JSONArray()
            val decoded = buildList {
                for (i in 0 until savedNotes.length()) {
                    val item = savedNotes.getJSONObject(i)
                    add(RollNote(
                        midi = item.optInt("midi", 60).coerceIn(0, 127),
                        step = item.optInt("step", 0).coerceIn(0, 63),
                        length = item.optInt("length", 2).coerceIn(1, 16),
                        velocity = item.optDouble("velocity", 0.86).toFloat().coerceIn(0.05f, 1f),
                    ))
                }
            }
            require(decoded.isNotEmpty()) { "Empty pattern" }
            pushUndo()
            notes.clear()
            notes.addAll(decoded)
            selectedKey = null
            bars = ((decoded.maxOfOrNull { it.step + it.length } ?: 16) + 15).div(16).coerceIn(1, 4)
            rootKey = payload.optInt("rootKey", rootKey).coerceIn(0, 11)
            swing = payload.optDouble("swing", swing.toDouble()).toFloat().coerceIn(0f, 0.6f)
            val savedSound = runCatching { SynthSound.valueOf(payload.optString("sound", sound.name)) }.getOrDefault(sound)
            sound = savedSound
            ctrl.setBpm(payload.optInt("bpm", bpm).coerceIn(30, 300))
            ctrl.toast("Loaded pattern $slot.")
        }.onFailure {
            ctrl.toast("Couldn't load pattern $slot.")
        }
    }

    fun spec(label: String): StemSpec? {
        if (notes.isEmpty()) return null
        val events = notes.map { n ->
            val swingShift = if (n.step % 4 == 2) swing.toDouble() else 0.0
            MusicEvent.Note(sound, n.midi, (n.step.toDouble() + swingShift) * SongRender.stepDurSec(bpm), n.length * SongRender.stepDurSec(bpm) * 0.96, n.velocity)
        }
        val lane = when (sound) {
            SynthSound.BASS -> "Bass"
            SynthSound.PLUCK, SynthSound.LEAD -> "Lead"
            SynthSound.STRINGS, SynthSound.CHOIR -> "FX"
            else -> "Keys"
        }
        return StemSpec(sound.label, lane, label, 2, 0.85f, 0f, bpm, events)
    }

    Column(Modifier.fillMaxSize().background(Neo.Paper).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Piano, null, tint = Neo.Ink, modifier = Modifier.size(25.dp))
            Column {
                Text("PIANO ROLL", fontSize = 19.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
                Text("Tap an empty cell to add a note; tap a note to select it. Draft autosaves.", fontSize = 11.sp, color = Neo.InkSoft)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            rollSounds.forEach { s -> Chip(s.label.uppercase(), sound == s) { sound = s } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("BPM", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Chip("−5", false) { ctrl.setBpm(bpm - 5) }
            Text(bpm.toString(), fontSize = 14.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Chip("+5", false) { ctrl.setBpm(bpm + 5) }
            Spacer(Modifier.width(4.dp))
            Text("BARS", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            listOf(1, 2, 4).forEach { b -> Chip(b.toString(), bars == b) { bars = b } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("KEY", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B").forEachIndexed { index, label ->
                Chip(label, rootKey == index) { rootKey = index }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("CHORD BUILDER", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Chip("I–V–vi–IV", false) {
                pushUndo()
                val generated = makeChordPattern(60 + (octave - 4) * 12 + rootKey, bars, listOf(0, 4, 5, 3))
                notes.addAll(generated.filterNot { candidate -> notes.any { it.midi == candidate.midi && it.step == candidate.step } })
                ctrl.toast("Added a major-pop progression in ${listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[rootKey]}.")
            }
            Chip("ii–V–I", false) {
                pushUndo()
                val generated = makeChordPattern(60 + (octave - 4) * 12 + rootKey, bars, listOf(1, 4, 0, 0))
                notes.addAll(generated.filterNot { candidate -> notes.any { it.midi == candidate.midi && it.step == candidate.step } })
                ctrl.toast("Added a ii–V–I progression in ${listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[rootKey]}.")
            }
            Chip("MINOR", false) {
                pushUndo()
                val generated = makeChordPattern(60 + (octave - 4) * 12 + rootKey, bars, listOf(0, 5, 2, 6), minorKey = true)
                notes.addAll(generated.filterNot { candidate -> notes.any { it.midi == candidate.midi && it.step == candidate.step } })
                ctrl.toast("Added a minor progression in ${listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[rootKey]}.")
            }
            Chip("ARPEGGIATE", false) {
                val chordGroups = notes.groupBy { it.step }.filterValues { group -> group.size >= 3 && group.any { it.length >= 8 } }
                if (chordGroups.isEmpty()) {
                    ctrl.toast("Add a chord progression first.")
                } else {
                    pushUndo()
                    val chordStarts = chordGroups.keys
                    val sourceChords = chordGroups.values.map { group -> group.sortedBy { it.midi } }
                    notes.removeAll { it.step in chordStarts && it.length >= 8 }
                    sourceChords.forEach { chord ->
                        val baseStep = chord.first().step
                        val pitchesInChord = chord.map { it.midi }.distinct().sorted()
                        val pattern = listOf(0, 1, 2, 1, 0, 1, 2, 1)
                        pattern.forEachIndexed { index, pitchIndex ->
                            val midi = pitchesInChord[pitchIndex.coerceAtMost(pitchesInChord.lastIndex)]
                            notes.add(RollNote(midi, baseStep + index * 2, 2, chord.first().velocity))
                        }
                    }
                    ctrl.toast("Turned chords into an arpeggio.")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SWING", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Slider(
                value = swing,
                onValueChange = { swing = it },
                valueRange = 0f..0.6f,
                modifier = Modifier.weight(1f),
            )
            Text("${(swing * 100).toInt()}%", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Neo.Ink)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("MELODY", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Chip("GENERATE", false) {
                pushUndo()
                val scale = listOf(0, 2, 4, 5, 7, 9, 11)
                val rootMidi = 60 + (octave - 4) * 12 + rootKey - 12
                val degrees = listOf(0, 2, 4, 2, 5, 4, 2, 1)
                val generated = buildList {
                    repeat(bars) { bar ->
                        degrees.forEachIndexed { index, degree ->
                            val midi = (rootMidi + scale[degree]).coerceIn(0, 127)
                            val step = bar * 16 + index * 2
                            if (notes.none { it.midi == midi && it.step == step }) {
                                add(RollNote(midi, step, 2, listOf(0.78f, 0.58f, 0.7f, 0.62f)[index % 4]))
                            }
                        }
                    }
                }
                notes.addAll(generated)
                ctrl.toast("Generated a melody in ${listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[rootKey]}.")
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("PATTERN SLOTS", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            listOf("A", "B", "C").forEach { slot ->
                Chip("SAVE $slot", false) { savePattern(slot) }
                Chip("LOAD $slot", false) { loadPattern(slot) }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("LENGTH", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            listOf(1 to "1/16", 2 to "1/8", 4 to "1/4", 8 to "1/2").forEach { (n, label) -> Chip(label, noteLength == n) { noteLength = n } }
            Text("VEL", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            listOf(0.45f to "SOFT", 0.70f to "MID", 0.95f to "HARD").forEach { (v, label) ->
                Chip(label, velocity == v) {
                    if (selectedNote != null) pushUndo()
                    velocity = v
                    selectedNote?.let { n ->
                        val i = notes.indexOf(n)
                        if (i >= 0) notes[i] = n.copy(velocity = v)
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Text("OCT", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Chip("−", false) { octave = (octave - 1).coerceAtLeast(2) }
            Text(octave.toString(), color = Neo.Ink, fontWeight = FontWeight.Bold)
            Chip("+", false) { octave = (octave + 1).coerceAtMost(6) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(selectedNote?.let { "SELECTED  ${Notes.nameOf(it.midi)} · step ${it.step + 1}" } ?: "SELECT A NOTE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Chip("UNDO", false) {
                if (undoStack.isNotEmpty()) {
                    redoStack.add(notes.toList())
                    val previous = undoStack.removeAt(undoStack.lastIndex)
                    restoreNotes(previous)
                } else ctrl.toast("Nothing to undo yet.")
            }
            Chip("REDO", false) {
                if (redoStack.isNotEmpty()) {
                    undoStack.add(notes.toList())
                    val next = redoStack.removeAt(redoStack.lastIndex)
                    restoreNotes(next)
                } else ctrl.toast("Nothing to redo yet.")
            }
            Chip("− LEN", false) {
                selectedNote?.let { n ->
                    pushUndo()
                    val i = notes.indexOf(n)
                    if (i >= 0) notes[i] = n.copy(length = (n.length - 1).coerceAtLeast(1))
                }
            }
            Chip("+ LEN", false) {
                selectedNote?.let { n ->
                    pushUndo()
                    val i = notes.indexOf(n)
                    if (i >= 0) notes[i] = n.copy(length = (n.length + 1).coerceAtMost(16))
                }
            }
            Chip("DUPLICATE", false) {
                selectedNote?.let { n ->
                    val targetStep = n.step + n.length
                    if (targetStep < steps && notes.none { it.midi == n.midi && it.step == targetStep }) {
                        pushUndo()
                        val copy = n.copy(step = targetStep)
                        notes.add(copy)
                        selectedKey = copy.midi to copy.step
                    } else {
                        ctrl.toast("No room to duplicate that note here.")
                    }
                }
            }
            Chip("DELETE", false) {
                if (selectedNote != null) pushUndo()
                selectedNote?.let { notes.remove(it) }
                selectedKey = null
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VELOCITY", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Slider(
                value = selectedNote?.velocity ?: velocity,
                onValueChange = { v ->
                    if (!velocityUndoSaved && selectedNote != null) {
                        pushUndo()
                        velocityUndoSaved = true
                    }
                    velocity = v
                    selectedNote?.let { n ->
                        val idx = notes.indexOfFirst { it.midi == n.midi && it.step == n.step }
                        if (idx >= 0) notes[idx] = n.copy(velocity = v)
                    }
                },
                onValueChangeFinished = { velocityUndoSaved = false },
                enabled = selectedNote != null,
                valueRange = 0.05f..1f,
                modifier = Modifier.weight(1f)
            )
            Text(((selectedNote?.velocity ?: velocity) * 100).toInt().toString(), fontSize = 10.sp, color = Neo.Ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NOTES  " + notes.size, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Spacer(Modifier.weight(1f))
            Text("SNAP 1/16", fontSize = 10.sp, color = Neo.InkSoft)
            Chip("−", false) { cellWidth = (cellWidth - 4).coerceAtLeast(28) }
            Text("ZOOM", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Neo.InkSoft)
            Chip("+", false) { cellWidth = (cellWidth + 4).coerceAtMost(48) }
        }
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxWidth().background(Neo.PaperRaised).border(1.dp, Neo.Ink.copy(alpha = 0.25f)).padding(10.dp)) {
                Text("YOUR PATTERN IS EMPTY\nTap a pitch row and beat cell below to add your first note.", color = Neo.InkSoft, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Row {
                    Box(Modifier.width(48.dp).height(24.dp).background(Neo.Ink), contentAlignment = Alignment.Center) { Text("KEY", color = Neo.Paper, fontSize = 9.sp) }
                    repeat(steps) { step ->
                        Box(Modifier.width(cellWidth.dp).height(24.dp).background(if (step % 16 == 0) Neo.Accent else Neo.PaperRaised).border(0.5.dp, Neo.Ink.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                            Text(if (step % 16 == 0) "B" + (step / 16 + 1) else if (step % 4 == 0) (step % 16 / 4 + 1).toString() else "·", fontSize = 9.sp, color = Neo.Ink)
                        }
                    }
                }
                pitches.forEach { midi ->
                    val pc = midi % 12
                    Row {
                        Box(Modifier.width(48.dp).height(29.dp).background(if (pc in blackKeys) Color(0xFF30323A) else Color(0xFFF7F7FA)).border(0.5.dp, Neo.Ink.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                            Text(Notes.nameOf(midi), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (pc in blackKeys) Color.White else Neo.Ink)
                        }
                        repeat(steps) { step ->
                            val note = notes.firstOrNull { it.midi == midi && step >= it.step && step < it.step + it.length }
                            Box(
                                Modifier
                                    .width(cellWidth.dp)
                                    .height(29.dp)
                                    .background(
                                        if (note != null && selectedKey == (midi to note.step)) Neo.Accent
                                        else if (note != null) Neo.Ok.copy(alpha = note.velocity.coerceIn(0.25f, 1f))
                                        else if (step % 4 == 0) Neo.PaperRaised
                                        else if (pc in scalePitchClasses) Neo.PaperRaised.copy(alpha = 0.55f)
                                        else Neo.Paper
                                    )
                                    .border(0.5.dp, Neo.Ink.copy(alpha = 0.22f))
                                    .clickable {
                                        if (note != null) selectedKey = midi to note.step
                                        else {
                                            pushUndo()
                                            notes.add(RollNote(midi, step, noteLength, velocity))
                                            selectedKey = midi to step
                                            ctrl.playPreview(Synth.renderNote(sound, midi, ctrl.sampleRate, 0.18, velocity), loop = false)
                                        }
                                    }
                                    .pointerInput(midi, step, cellWidth) {
                                        val initialNote = note
                                        if (initialNote != null) {
                                            var startingNote = initialNote
                                            var dragX = 0f
                                            var dragY = 0f
                                            var currentMidi = initialNote.midi
                                            var currentStep = initialNote.step
                                            var currentLength = initialNote.length
                                            var resizeMode = false
                                            detectDragGestures(
                                                onDragStart = { offset ->
                                                    notes.firstOrNull { it.midi == midi && step >= it.step && step < it.step + it.length }?.let { startingNote = it }
                                                    currentMidi = startingNote.midi
                                                    currentStep = startingNote.step
                                                    currentLength = startingNote.length
                                                    pushUndo()
                                                    selectedKey = startingNote.midi to startingNote.step
                                                    val edgePx = with(density) { 10.dp.toPx() }
                                                    resizeMode = step == startingNote.step + startingNote.length - 1 &&
                                                        offset.x >= size.width - edgePx
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragX += amount.x
                                                    dragY += amount.y
                                                    val stepDelta = (dragX / with(density) { cellWidth.dp.toPx() }).toInt()
                                                    val pitchDelta = -(dragY / with(density) { 29.dp.toPx() }).toInt()
                                                    val idx = notes.indexOfFirst { it.midi == currentMidi && it.step == currentStep }
                                                    if (idx >= 0 && resizeMode) {
                                                        val targetLength = (startingNote.length + stepDelta).coerceIn(1, minOf(16, steps - startingNote.step))
                                                        if (targetLength != currentLength) {
                                                            notes[idx] = startingNote.copy(length = targetLength)
                                                            currentLength = targetLength
                                                            selectedKey = startingNote.midi to startingNote.step
                                                        }
                                                    } else if (idx >= 0) {
                                                        val targetStep = (startingNote.step + stepDelta).coerceIn(0, 63)
                                                        val targetMidi = (startingNote.midi + pitchDelta).coerceIn(0, 127)
                                                        if (targetStep != currentStep || targetMidi != currentMidi) {
                                                            val moved = startingNote.copy(step = targetStep, midi = targetMidi)
                                                            notes[idx] = moved
                                                            currentMidi = moved.midi
                                                            currentStep = moved.step
                                                            selectedKey = moved.midi to moved.step
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                            ) {
                                if (note != null && step == note.step + note.length - 1) {
                                    Text("↔", fontSize = 9.sp, color = Neo.Ink, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).background(Neo.PaperRaised).border(1.dp, Neo.Ink).clickable {
                importMidiLauncher.launch(arrayOf("audio/midi", "audio/x-midi", "application/octet-stream"))
            }.padding(12.dp), contentAlignment = Alignment.Center) {
                Text("⇧ IMPORT MIDI", color = Neo.Ink, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
            Box(Modifier.weight(1f).background(Neo.PaperRaised).border(1.dp, Neo.Ink).clickable {
                if (notes.isEmpty()) ctrl.toast("Add notes before exporting MIDI.")
                else {
                    pendingMidiExport = encodeStandardMidi(notes.toList(), bpm, swing)
                    exportMidiLauncher.launch("GIA-Pattern.mid")
                }
            }.padding(12.dp), contentAlignment = Alignment.Center) {
                Text("⇩ EXPORT MIDI", color = Neo.Ink, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).background(if (playing) Neo.Record else Neo.Ink).clickable {
                if (playing) { ctrl.stopPreview(); playing = false }
                else {
                    val s = spec("Piano Roll Preview")
                    if (s == null) ctrl.toast("Add a few notes first.")
                    else scope.launch {
                        val samples = withContext(Dispatchers.Default) { SongRender.renderStem(s, ctrl.sampleRate) }
                        ctrl.playPreview(samples, loop = true)
                        playing = true
                    }
                }
            }.padding(13.dp), contentAlignment = Alignment.Center) {
                Text(if (playing) "■ STOP" else "▶ PLAY PATTERN", color = Neo.Paper, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
            Box(Modifier.weight(1f).background(Neo.Ok).clickable {
                ctrl.stopPreview(); playing = false
                val s = spec("Piano Roll · " + bars + " bar" + if (bars == 1) "" else "s")
                if (s == null) ctrl.toast("Add some notes first.")
                else ctrl.addMusicStem(s)
            }.padding(13.dp), contentAlignment = Alignment.Center) {
                Text("＋ ADD TO SONG", color = Neo.Ink, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        }
        Box(Modifier.fillMaxWidth().background(Neo.PaperRaised).border(1.dp, Neo.Ink).clickable { if (notes.isNotEmpty()) pushUndo(); notes.clear(); selectedKey = null; ctrl.stopPreview(); playing = false }.padding(11.dp), contentAlignment = Alignment.Center) {
            Text("CLEAR NOTES", color = Neo.Ink, fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.background(if (active) Neo.Accent else Neo.PaperRaised).border(1.dp, Neo.Ink).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Neo.Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}



/** Generate triad voicings as MIDI notes, one chord per bar. */
private fun makeChordPattern(
    rootMidi: Int,
    bars: Int,
    degrees: List<Int>,
    minorKey: Boolean = false,
): List<RollNote> {
    val scale = if (minorKey) listOf(0, 2, 3, 5, 7, 8, 10) else listOf(0, 2, 4, 5, 7, 9, 11)
    return degrees.take(bars.coerceIn(1, 4)).flatMapIndexed { bar, rawDegree ->
        val degree = rawDegree.coerceIn(0, 6)
        val intervals = listOf(
            scale[degree],
            scale[(degree + 2) % 7] + if (degree + 2 >= 7) 12 else 0,
            scale[(degree + 4) % 7] + if (degree + 4 >= 7) 12 else 0,
        )
        intervals.map { interval ->
            RollNote(
                midi = (rootMidi + interval).coerceIn(0, 127),
                step = bar * 16,
                length = 16,
                velocity = 0.72f,
            )
        }
    }
}

/** Encode the current pattern as a standard single-track MIDI file (format 0). */
private fun encodeStandardMidi(notes: List<RollNote>, bpm: Int, swing: Float = 0f): ByteArray {
    data class MidiMessage(val tick: Int, val order: Int, val bytes: ByteArray)
    val messages = mutableListOf<MidiMessage>()
    val microsPerQuarter = (60_000_000 / bpm.coerceIn(30, 300))
    messages += MidiMessage(0, -2, byteArrayOf(0xFF.toByte(), 0x58, 0x04, 0x04, 0x02, 0x18, 0x08))
    messages += MidiMessage(0, -1, byteArrayOf(0xFF.toByte(), 0x51, 0x03, (microsPerQuarter shr 16).toByte(), (microsPerQuarter shr 8).toByte(), microsPerQuarter.toByte()))
    notes.forEach { n ->
        val swingTicks = if (n.step % 4 == 2) (120 * swing.coerceIn(0f, 0.6f)).toInt() else 0
        val start = n.step * 120 + swingTicks
        val end = (n.step + n.length) * 120 + swingTicks
        val pitch = n.midi.coerceIn(0, 127).toByte()
        val vel = (n.velocity * 127).toInt().coerceIn(1, 127).toByte()
        messages += MidiMessage(start, 1, byteArrayOf(0x90.toByte(), pitch, vel))
        messages += MidiMessage(end, 0, byteArrayOf(0x80.toByte(), pitch, 0))
    }
    messages.sortWith(compareBy<MidiMessage> { it.tick }.thenBy { it.order })
    val track = ByteArrayOutputStream()
    var previousTick = 0
    messages.forEach { event ->
        writeMidiVariableLength(track, (event.tick - previousTick).coerceAtLeast(0))
        track.write(event.bytes)
        previousTick = event.tick
    }
    writeMidiVariableLength(track, 0)
    track.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
    val trackBytes = track.toByteArray()
    val out = ByteArrayOutputStream()
    val data = DataOutputStream(out)
    data.writeBytes("MThd")
    data.writeInt(6)
    data.writeShort(0)
    data.writeShort(1)
    data.writeShort(480)
    data.writeBytes("MTrk")
    data.writeInt(trackBytes.size)
    data.write(trackBytes)
    data.flush()
    return out.toByteArray()
}

private fun writeMidiVariableLength(out: ByteArrayOutputStream, rawValue: Int) {
    var value = rawValue.coerceAtLeast(0)
    var buffer = value and 0x7F
    while ((value shr 7) > 0) {
        value = value shr 7
        buffer = (buffer shl 8) or ((value and 0x7F) or 0x80)
    }
    while (true) {
        out.write(buffer and 0xFF)
        if ((buffer and 0x80) != 0) buffer = buffer shr 8 else break
    }
}


private data class ImportedMidiPattern(val notes: List<RollNote>, val bpm: Int)

/** Read standard MIDI format 0/1 note events and quantize them to the 1/16 grid. */
private fun decodeStandardMidi(bytes: ByteArray): ImportedMidiPattern {
    class Reader(val data: ByteArray) {
        var p = 0
        fun u8(): Int {
            require(p < data.size) { "Unexpected end of MIDI data" }
            return data[p++].toInt() and 0xFF
        }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun vlq(): Int {
            var value = 0
            repeat(4) {
                val b = u8()
                value = (value shl 7) or (b and 0x7F)
                if ((b and 0x80) == 0) return value
            }
            error("Invalid MIDI variable-length value")
        }
        fun chunk(length: Int): ByteArray {
            require(length >= 0 && p + length <= data.size) { "Invalid MIDI chunk length" }
            return data.copyOfRange(p, p + length).also { p += length }
        }
        fun skip(length: Int) { require(length >= 0 && p + length <= data.size); p += length }
    }

    val file = Reader(bytes)
    require(String(file.chunk(4), Charsets.US_ASCII) == "MThd") { "Not a MIDI file" }
    val headerLength = file.u32()
    require(headerLength >= 6) { "Invalid MIDI header" }
    val format = file.u16()
    val trackCount = file.u16()
    val division = file.u16()
    require(format in 0..1 && trackCount > 0 && division in 1..0x7FFF) { "Unsupported MIDI format" }
    file.skip(headerLength - 6)
    data class Start(val tick: Int, val velocity: Int)
    data class RawNote(val start: Int, val end: Int, val midi: Int, val velocity: Int)
    val active = mutableMapOf<Pair<Int, Int>, ArrayDeque<Start>>()
    val rawNotes = mutableListOf<RawNote>()
    var microsPerQuarter = 500_000
    repeat(trackCount) {
        require(String(file.chunk(4), Charsets.US_ASCII) == "MTrk") { "Invalid MIDI track" }
        val trackLength = file.u32()
        val track = Reader(file.chunk(trackLength))
        var tick = 0
        var runningStatus = 0
        while (track.p < track.data.size) {
            tick += track.vlq()
            var status = track.data[track.p].toInt() and 0xFF
            if (status and 0x80 != 0) {
                track.p++
                if (status < 0xF0) runningStatus = status
            } else {
                status = runningStatus
                require(status >= 0x80) { "Invalid MIDI running status" }
            }
            when (status) {
                0xFF -> {
                    val type = track.u8()
                    val length = track.vlq()
                    if (type == 0x51 && length == 3) {
                        microsPerQuarter = (track.u8() shl 16) or (track.u8() shl 8) or track.u8()
                    } else track.skip(length)
                }
                0xF0, 0xF7 -> track.skip(track.vlq())
                else -> {
                    val kind = status and 0xF0
                    val channel = status and 0x0F
                    val a = track.u8()
                    val b = if (kind == 0xC0 || kind == 0xD0) 0 else track.u8()
                    if (kind == 0x90 && b > 0) {
                        active.getOrPut(channel to a) { ArrayDeque() }.addLast(Start(tick, b))
                    } else if (kind == 0x80 || (kind == 0x90 && b == 0)) {
                        val queue = active[channel to a]
                        if (queue != null && queue.isNotEmpty()) {
                            val start = queue.removeFirst()
                            rawNotes += RawNote(start.tick, tick.coerceAtLeast(start.tick + 1), a, start.velocity)
                        }
                    }
                }
            }
        }
    }
    val ticksPerStep = division / 4.0
    val imported = rawNotes.map { n ->
        val step = (n.start / ticksPerStep).toInt().let { if (n.start / ticksPerStep - it >= 0.5) it + 1 else it }.coerceIn(0, 63)
        val endStep = (n.end / ticksPerStep).toInt().let { if (n.end / ticksPerStep - it >= 0.5) it + 1 else it }
        RollNote(n.midi.coerceIn(0, 127), step, (endStep - step).coerceIn(1, 16), (n.velocity / 127f).coerceIn(0.05f, 1f))
    }.distinctBy { Triple(it.midi, it.step, it.length) }.sortedWith(compareBy<RollNote> { it.step }.thenBy { it.midi })
    val bpm = (60_000_000.0 / microsPerQuarter.coerceAtLeast(1)).toInt().coerceIn(30, 300)
    return ImportedMidiPattern(imported, bpm)
}
