package com.giastudio.app.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.Icon
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
    val scope = rememberCoroutineScope()
    var sound by remember {
        mutableStateOf(runCatching {
            SynthSound.valueOf(prefs.getString("sound", "EPIANO") ?: "EPIANO")
        }.getOrDefault(SynthSound.EPIANO))
    }
    var noteLength by remember { mutableIntStateOf(prefs.getInt("noteLength", 2).coerceIn(1, 8)) }
    var velocity by remember { mutableFloatStateOf(prefs.getFloat("velocity", 0.86f).coerceIn(0.05f, 1f)) }
    var bars by remember { mutableIntStateOf(prefs.getInt("bars", 2).coerceIn(1, 4)) }
    var octave by remember { mutableIntStateOf(prefs.getInt("octave", 4).coerceIn(2, 6)) }
    var playing by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val selectedNote = notes.firstOrNull { it.midi == selectedKey?.first && it.step == selectedKey?.second }
    val bpm = ctrl.project.bpm.coerceIn(30, 300)
    val steps = bars * 16
    val pitches = (48 + (octave - 4) * 12..71 + (octave - 4) * 12).reversed().toList()
    val blackKeys = setOf(1, 3, 6, 8, 10)

    LaunchedEffect(notes.toList(), sound, noteLength, velocity, bars, octave) {
        val saved = JSONArray()
        notes.forEach { n ->
            saved.put(JSONObject().put("midi", n.midi).put("step", n.step).put("length", n.length).put("velocity", n.velocity.toDouble()))
        }
        prefs.edit()
            .putString("notes", saved.toString())
            .putString("sound", sound.name)
            .putInt("noteLength", noteLength)
            .putFloat("velocity", velocity)
            .putInt("bars", bars)
            .putInt("octave", octave)
            .apply()
    }

    fun spec(label: String): StemSpec? {
        if (notes.isEmpty()) return null
        val events = notes.map { n ->
            MusicEvent.Note(sound, n.midi, n.step * SongRender.stepDurSec(bpm), n.length * SongRender.stepDurSec(bpm) * 0.96, n.velocity)
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("BPM", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Chip("−5", false) { ctrl.setBpm(bpm - 5) }
            Text(bpm.toString(), fontSize = 14.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Chip("+5", false) { ctrl.setBpm(bpm + 5) }
            Spacer(Modifier.width(4.dp))
            Text("BARS", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            listOf(1, 2, 4).forEach { b -> Chip(b.toString(), bars == b) { bars = b } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("LENGTH", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            listOf(1 to "1/16", 2 to "1/8", 4 to "1/4", 8 to "1/2").forEach { (n, label) -> Chip(label, noteLength == n) { noteLength = n } }
            Text("VEL", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            listOf(0.45f to "SOFT", 0.70f to "MID", 0.95f to "HARD").forEach { (v, label) ->
                Chip(label, velocity == v) {
                    velocity = v
                    selectedNote?.let { n ->
                        val i = notes.indexOf(n)
                        if (i >= 0) notes[i] = n.copy(velocity = v)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("OCT", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Chip("−", false) { octave = (octave - 1).coerceAtLeast(2) }
            Text(octave.toString(), color = Neo.Ink, fontWeight = FontWeight.Bold)
            Chip("+", false) { octave = (octave + 1).coerceAtMost(6) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(selectedNote?.let { "SELECTED  ${Notes.nameOf(it.midi)} · step ${it.step + 1}" } ?: "SELECT A NOTE", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Spacer(Modifier.weight(1f))
            Chip("− LEN", false) {
                selectedNote?.let { n ->
                    val i = notes.indexOf(n)
                    if (i >= 0) notes[i] = n.copy(length = (n.length - 1).coerceAtLeast(1))
                }
            }
            Chip("+ LEN", false) {
                selectedNote?.let { n ->
                    val i = notes.indexOf(n)
                    if (i >= 0) notes[i] = n.copy(length = (n.length + 1).coerceAtMost(16))
                }
            }
            Chip("DELETE", false) {
                selectedNote?.let { notes.remove(it) }
                selectedKey = null
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NOTES  " + notes.size, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Spacer(Modifier.weight(1f))
            Text("Snap: 1/16", fontSize = 10.sp, color = Neo.InkSoft)
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
                        Box(Modifier.width(38.dp).height(24.dp).background(if (step % 16 == 0) Neo.Accent else Neo.PaperRaised).border(0.5.dp, Neo.Ink.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
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
                            Box(Modifier.width(38.dp).height(29.dp).background(
                                if (note != null && selectedKey == (midi to note.step)) Neo.Accent
                                else if (note != null) Neo.Ok
                                else if (step % 4 == 0) Neo.PaperRaised else Neo.Paper
                            ).border(0.5.dp, Neo.Ink.copy(alpha = 0.22f)).clickable {
                                if (note != null) selectedKey = midi to note.step
                                else {
                                    notes.add(RollNote(midi, step, noteLength, velocity))
                                    selectedKey = midi to step
                                    ctrl.playPreview(Synth.renderNote(sound, midi, ctrl.sampleRate, 0.18, velocity), loop = false)
                                }
                            })
                        }
                    }
                }
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
        Box(Modifier.fillMaxWidth().background(Neo.PaperRaised).border(1.dp, Neo.Ink).clickable { notes.clear(); ctrl.stopPreview(); playing = false }.padding(11.dp), contentAlignment = Alignment.Center) {
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
