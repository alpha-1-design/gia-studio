package com.giastudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giastudio.app.StudioController
import com.giastudio.app.music.DrumSound
import com.giastudio.app.music.MusicTheory
import com.giastudio.app.music.STEPS_PER_BAR
import com.giastudio.app.music.StepPattern
import com.giastudio.app.music.SongRender
import com.giastudio.app.music.StemSpec
import com.giastudio.app.music.Synth
import com.giastudio.app.music.SynthSound
import com.giastudio.app.ui.theme.Neo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val KIT_SOUNDS = listOf(
    DrumSound.KICK, DrumSound.SNARE, DrumSound.CLAP, DrumSound.HAT,
    DrumSound.OPEN_HAT, DrumSound.TOM_LO, DrumSound.TOM_HI, DrumSound.RIM,
)

private val SCALES = listOf(
    "Pentatonic" to MusicTheory.PENTATONIC,
    "Major" to MusicTheory.MAJOR,
    "Minor" to MusicTheory.MINOR,
)

private val OCTAVES = listOf(-1, 0, 1)

private data class KeysCfg(
    val sound: SynthSound = SynthSound.EPIANO,
    val scaleIdx: Int = 0,
    val rootIdx: Int = 0,
    val octIdx: Int = 1, // middle octave by default
)

private fun laneFor(sound: SynthSound): String = when (sound) {
    SynthSound.EPIANO, SynthSound.PAD -> "Keys"
    SynthSound.PLUCK, SynthSound.LEAD -> "Lead"
    SynthSound.BASS -> "Bass"
}

private fun colorFor(sound: SynthSound): Int = when (sound) {
    SynthSound.EPIANO, SynthSound.PAD -> 2
    SynthSound.PLUCK, SynthSound.LEAD -> 4
    SynthSound.BASS -> 3
}

@Composable
fun CreateScreen(ctrl: StudioController) {
    var isBeats by remember { mutableStateOf(true) }
    var beatPat by remember { mutableStateOf(StepPattern(KIT_SOUNDS.size, 2)) }
    var bars by remember { mutableStateOf(2) }
    var keysCfg by remember { mutableStateOf(KeysCfg()) }

    val keysMidis = remember(keysCfg) {
        val startMidi = (4 + OCTAVES[keysCfg.octIdx]) * 12 +
            MusicTheory.letterSemitone(keysCfg.rootIdx)
        MusicTheory.noteRows(startMidi, SCALES[keysCfg.scaleIdx].second)
    }
    var keyPat by remember(keysCfg) { mutableStateOf(StepPattern(keysMidis.size, 2)) }

    var playing by remember { mutableStateOf(false) }
    var stepIdx by remember { mutableStateOf(-1) }
    var showDemoAsk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val bpm = ctrl.project.bpm.coerceIn(30, 300)
    val stepMs = (60_000.0 / bpm / 4.0).toLong()
    val totalSteps = bars * STEPS_PER_BAR
    val pattern = if (isBeats) beatPat else keyPat

    fun stopPlay() {
        ctrl.stopPreview()
        playing = false
        stepIdx = -1
    }

    fun playRowSound(row: Int) {
        val samples = if (isBeats) {
            Synth.renderDrum(KIT_SOUNDS[row], ctrl.sampleRate, 0.9f)
        } else {
            Synth.renderNote(keysCfg.sound, keysMidis[row], ctrl.sampleRate, 0.4, 0.9f)
        }
        ctrl.playPreview(samples, loop = false)
    }

    fun toggleCell(row: Int, col: Int) {
        val bar = col / STEPS_PER_BAR
        val step = col % STEPS_PER_BAR
        val on = !pattern.get(row, bar, step)
        pattern.set(row, bar, step, on)
        if (on && !playing) playRowSound(row)
    }

    fun currentSpec(label: String): StemSpec? {
        val events = if (isBeats) {
            SongRender.drumEvents(KIT_SOUNDS, beatPat, bpm, bars)
        } else {
            SongRender.noteEvents(keysCfg.sound, keysMidis, keyPat, bpm, bars)
        }
        if (events.isEmpty()) return null
        val kind = if (isBeats) "Drums" else keysCfg.sound.label
        val name = if (isBeats) "Beat" else laneFor(keysCfg.sound)
        val color = if (isBeats) 1 else colorFor(keysCfg.sound)
        return StemSpec(
            kindName = kind,
            trackName = name,
            label = label,
            color = color,
            volume = 0.8f,
            pan = 0f,
            bpm = bpm,
            events = events,
        )
    }

    LaunchedEffect(playing, bars, isBeats) {
        if (!playing) {
            stepIdx = -1
            return@LaunchedEffect
        }
        stepIdx = 0
        while (playing) {
            delay(stepMs)
            stepIdx = (stepIdx + 1) % totalSteps
        }
    }

    Row(Modifier.fillMaxSize().background(Neo.Paper)) {
        // ----------------------------------------------------- left controls
        Column(
            Modifier
                .width(238.dp)
                .fillMaxHeight()
                .border(2.dp, Neo.Ink)
                .background(Neo.PaperRaised)
                .padding(6.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
            Text("CREATE", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Text(
                if (isBeats) {
                    "Tap pads to build a drum beat, then ADD TO SONG drops it on its own lane."
                } else {
                    "Paint notes inside one scale — you can't hit a wrong note. Every row is a pad you can hear."
                },
                fontSize = 10.sp,
                color = Neo.InkSoft,
                lineHeight = 13.sp,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NeoChip("DRUMS", active = isBeats, onClick = {
                    isBeats = true
                    stopPlay()
                })
                NeoChip("MELODY", active = !isBeats, onClick = {
                    isBeats = false
                    stopPlay()
                })
            }

            if (!isBeats) {
                SectionTitle("SOUND")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SynthSound.entries.take(3).forEach { s ->
                        NeoChip(s.label, active = keysCfg.sound == s, onClick = {
                            keysCfg = keysCfg.copy(sound = s)
                        }, fontSize = 10.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SynthSound.entries.drop(3).forEach { s ->
                        NeoChip(s.label, active = keysCfg.sound == s, onClick = {
                            keysCfg = keysCfg.copy(sound = s)
                        }, fontSize = 10.sp)
                    }
                }
                SectionTitle("SCALE")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SCALES.forEachIndexed { i, (name, _) ->
                        NeoChip(name, active = keysCfg.scaleIdx == i, onClick = {
                            keysCfg = keysCfg.copy(scaleIdx = i)
                        })
                    }
                }
                SectionTitle("ROOT NOTE")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(4) { i ->
                        NeoChip(MusicTheory.letterName(i), active = keysCfg.rootIdx == i, onClick = {
                            keysCfg = keysCfg.copy(rootIdx = i)
                        })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { i ->
                        val letter = i + 4
                        NeoChip(MusicTheory.letterName(letter), active = keysCfg.rootIdx == letter, onClick = {
                            keysCfg = keysCfg.copy(rootIdx = letter)
                        })
                    }
                }
                SectionTitle("PITCH RANGE")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OCTAVES.forEachIndexed { i, rel ->
                        val startMidi = (4 + rel) * 12 + MusicTheory.letterSemitone(keysCfg.rootIdx)
                        NeoChip(
                            "from ${MusicTheory.label(startMidi)}",
                            active = keysCfg.octIdx == i,
                            onClick = { keysCfg = keysCfg.copy(octIdx = i) },
                            fontSize = 9.sp,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TEMPO", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
                Spacer(Modifier.weight(1f))
                NeoChip("−", active = false, onClick = { ctrl.setBpm(bpm - 1) })
                Text("$bpm BPM", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Neo.Ink, modifier = Modifier.padding(horizontal = 6.dp))
                NeoChip("+", active = false, onClick = { ctrl.setBpm(bpm + 1) })
            }

            NeoButton(
                "CLEAR PATTERN",
                onClick = {
                    pattern.clear()
                    stopPlay()
                },
                container = Neo.Paper,
                contentColor = Neo.Ink,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 12.sp,
            )

            }

            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Neo.Accent.copy(alpha = 0.35f))
                    .border(2.dp, Neo.Ink)
                    .padding(8.dp),
            ) {
                Text("SONG DEMO", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
                Text(
                    "A complete built-in song — beat, bass, e-piano & melody — you can play, pull apart, re-mix and sing over.",
                    fontSize = 9.sp,
                    color = Neo.InkSoft,
                    lineHeight = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                NeoButton(
                    "LOAD DEMO SONG",
                    onClick = { showDemoAsk = true },
                    container = Neo.Ok,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(2.dp))
        }

        // --------------------------------------------------------- grid side
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NeoButton(
                    if (playing) "STOP" else "▶ HEAR IT",
                    onClick = {
                        if (playing) {
                            stopPlay()
                        } else {
                            val spec = currentSpec("Preview")
                            if (spec == null) {
                                ctrl.toast("Tap some pads first — then press ▶ HEAR IT.")
                            } else {
                                scope.launch {
                                    val samples = withContext(Dispatchers.Default) {
                                        SongRender.renderStem(spec, ctrl.sampleRate)
                                    }
                                    ctrl.playPreview(samples, loop = true)
                                    playing = true
                                    stepIdx = 0
                                }
                            }
                        }
                    },
                    container = if (playing) Neo.Record else Neo.Accent,
                    fontSize = 13.sp,
                )
                NeoButton(
                    "＋ ADD TO SONG",
                    onClick = {
                        val barWord = if (bars == 1) "bar" else "bars"
                        val spec = currentSpec(
                            "${if (isBeats) "Beat" else keysCfg.sound.label} · ${bars} $barWord"
                        )
                        if (spec == null) {
                            ctrl.toast("Tap some pads first — that pattern is empty.")
                            return@NeoButton
                        }
                        stopPlay()
                        ctrl.addMusicStem(spec)
                    },
                    container = Neo.Ok,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BARS", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.InkSoft)
                    Spacer(Modifier.width(6.dp))
                    listOf(1, 2, 4, 8).forEach { b ->
                        NeoChip("$b", active = bars == b, onClick = {
                            bars = b
                            stopPlay()
                        }, activeColor = Neo.Ink)
                        Spacer(Modifier.width(3.dp))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                if (isBeats) {
                    "DRUM KIT — ${KIT_SOUNDS.size} sounds · tap a row pad to hear one"
                } else {
                    "${SCALES[keysCfg.scaleIdx].first} on ${MusicTheory.letterName(keysCfg.rootIdx)} · ${keysMidis.size} pads · ${bars * 4} beats"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = Neo.Ink,
            )

            StepGrid(
                pattern = pattern,
                bars = bars,
                rowLabels = if (isBeats) {
                    KIT_SOUNDS.map { it.label }
                } else {
                    keysMidis.map { MusicTheory.label(it) }
                },
                rowColors = if (isBeats) {
                    KIT_SOUNDS.indices.map { Neo.trackColor(it) }
                } else {
                    keysMidis.indices.map { Neo.trackColor(it % 8) }
                },
                playingCol = if (playing) stepIdx else -1,
                onCell = ::toggleCell,
                onRowTap = ::playRowSound,
            )
        }
    }

    if (showDemoAsk) {
        DemoAskDialog(
            onConfirm = {
                showDemoAsk = false
                stopPlay()
                ctrl.loadDemoSong()
            },
            onDismiss = { showDemoAsk = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 9.sp, fontWeight = FontWeight.Black, color = Neo.InkSoft, letterSpacing = 1.sp)
}

@Composable
private fun StepGrid(
    pattern: StepPattern,
    bars: Int,
    rowLabels: List<String>,
    rowColors: List<Color>,
    playingCol: Int,
    onCell: (row: Int, col: Int) -> Unit,
    onRowTap: (row: Int) -> Unit,
) {
    val totalSteps = bars * STEPS_PER_BAR
    val cellWidth = 28.dp
    val labelWidth = 86.dp
    val rowHeight = 40.dp
    val gridWidth = labelWidth + cellWidth * totalSteps.toFloat()
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .border(2.dp, Neo.Ink)
            .background(Neo.Paper),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(vScroll),
        ) {
            Row(Modifier.horizontalScroll(hScroll)) {
                Column(Modifier.width(gridWidth)) {
                    // Beat-number header.
                    Row(Modifier.fillMaxWidth().height(20.dp).background(Neo.Ink)) {
                        Spacer(Modifier.width(labelWidth).fillMaxHeight())
                        for (col in 0 until totalSteps) {
                            Box(
                                Modifier
                                    .width(cellWidth)
                                    .fillMaxHeight()
                                    .background(if (col % STEPS_PER_BAR == 0) Neo.Accent.copy(alpha = 0.25f) else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (col % 4 == 0 && bars <= 4) {
                                    Text(
                                        "${(col / 4) + 1}",
                                        color = Neo.Paper,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black,
                                    )
                                }
                            }
                        }
                    }
                    for (row in rowLabels.indices) {
                        Row(Modifier.fillMaxWidth().height(rowHeight)) {
                            // Row pad — tap to hear this sound/note.
                            Box(
                                Modifier
                                    .width(labelWidth)
                                    .fillMaxHeight()
                                    .background(rowColors[row])
                                    .border(1.dp, Neo.Ink)
                                    .clickable { onRowTap(row) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    rowLabels[row],
                                    color = Neo.Paper,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                )
                            }
                            for (col in 0 until totalSteps) {
                                val on = pattern.get(row, col / STEPS_PER_BAR, col % STEPS_PER_BAR)
                                val isPlayCol = playingCol == col
                                Box(
                                    Modifier
                                        .width(cellWidth)
                                        .fillMaxHeight()
                                        .background(
                                            when {
                                                on -> rowColors[row]
                                                isPlayCol -> Neo.Accent
                                                else -> Neo.Paper
                                            }
                                        )
                                        .border(1.dp, Neo.Ink.copy(alpha = 0.22f))
                                        .clickable { onCell(row, col) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun DemoAskDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Neo.Ink.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(330.dp)
                .background(Neo.PaperRaised)
                .border(3.dp, Neo.Ink)
                .padding(14.dp),
        ) {
            Text("Load the demo song?", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "This replaces the current session with “Afterglow” — a finished 16-bar song (beat, bass, e-piano chords, melody) you can play, mute, edit and sing over. Your saved sessions are untouched.",
                fontSize = 11.sp,
                color = Neo.InkSoft,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeoButton("CANCEL", onClick = onDismiss, container = Neo.PaperRaised, contentColor = Neo.Ink)
                NeoButton("LOAD DEMO", onClick = onConfirm, container = Neo.Ok)
            }
        }
    }
}
