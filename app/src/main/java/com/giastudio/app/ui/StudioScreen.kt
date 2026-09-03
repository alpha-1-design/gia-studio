package com.giastudio.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giastudio.app.CleanOp
import com.giastudio.app.StudioController
import com.giastudio.app.audio.AudioCleanup
import com.giastudio.app.model.Clip
import com.giastudio.app.model.Track
import com.giastudio.app.ui.theme.Neo
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private val LaneH = 46.dp
private val RulerH = 26.dp
private val PpsDp = 62f // dp per second

@Composable
fun StudioScreen(ctrl: StudioController, micGuard: (() -> Unit) -> Unit = { it() }) {
    Column(Modifier.fillMaxSize().background(Neo.Paper)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            TrackHeaders(ctrl, Modifier.width(172.dp).fillMaxHeight())
            Timeline(ctrl, Modifier.weight(1f).fillMaxHeight())
            val sel = selectedClipOf(ctrl)
            if (sel != null) {
                ClipInspector(ctrl, sel.first, sel.second, Modifier.width(290.dp).fillMaxHeight())
            }
        }
        TransportBar(ctrl, micGuard)
    }
}

private fun selectedClipOf(ctrl: StudioController): Pair<Track, Clip>? {
    val id = ctrl.selectedClipId ?: return null
    for (t in ctrl.project.tracks) {
        val c = t.clips.firstOrNull { it.id == id } ?: continue
        return t to c
    }
    return null
}

// ---------------------------------------------------------------------------
// Left: track headers
// ---------------------------------------------------------------------------

@Composable
private fun TrackHeaders(ctrl: StudioController, modifier: Modifier = Modifier) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .border(2.dp, Neo.Ink)
            .background(Neo.PaperRaised),
    ) {
        Box(Modifier.fillMaxWidth().height(RulerH).background(Neo.Ink), contentAlignment = Alignment.Center) {
            Text("TRACKS", color = Neo.Paper, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
        ctrl.project.tracks.forEach { t ->
            TrackHeaderRow(ctrl, t, Modifier.fillMaxWidth().height(LaneH))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            NeoButton("+ Track", onClick = { ctrl.addTrack() }, container = Neo.Paper, contentColor = Neo.Ink, modifier = Modifier.weight(1f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TrackHeaderRow(ctrl: StudioController, t: Track, modifier: Modifier = Modifier) {
    val selected = ctrl.selectedTrackId == t.id
    val color = Neo.trackColor(t.color)
    Box(
        modifier
            .background(if (selected) Neo.Accent.copy(alpha = 0.30f) else Neo.PaperRaised)
            .border(width = 1.dp, color = Neo.Ink.copy(alpha = 0.45f))
            .clickable { ctrl.selectTrack(t.id) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(color).border(2.dp, Neo.Ink))
                Spacer(Modifier.width(5.dp))
                Text(
                    t.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    color = Neo.Ink,
                )
                if (t.armed) {
                    Box(Modifier.size(11.dp).background(Neo.Record).border(2.dp, Neo.Ink))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NeoChip("M", active = t.muted, onClick = { ctrl.toggleMute(t.id) }, activeColor = Neo.Record)
                NeoChip("S", active = t.soloed, onClick = { ctrl.toggleSolo(t.id) }, activeColor = Neo.Ok)
                NeoChip("●", active = t.armed, onClick = { ctrl.setArmed(t.id) }, activeColor = Neo.RecordDark)
                Spacer(Modifier.weight(1f))
                val fxOn = t.fx.hpOn || t.fx.lpOn || t.fx.driveOn || t.fx.delayOn || t.fx.reverbOn
                if (fxOn) {
                    Text("FX", color = Neo.InkSoft, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Middle: ruler + lanes
// ---------------------------------------------------------------------------

private data class RulerLabel(val sec: Double, val layout: TextLayoutResult)

@Composable
private fun Timeline(ctrl: StudioController, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val ppsPx = with(density) { PpsDp.dp.toPx() }
    val endSec = ctrl.project.arrangementEndSec().coerceAtLeast(8.0)
    val contentWidth = (PpsDp * (endSec + 4.0).toFloat()).dp
    val bpm = ctrl.project.bpm.coerceIn(40, 300)
    val beatSec = 60.0 / bpm
    val gridStep = chooseGridStep(beatSec, ppsPx)
    val labelStep = pickLabelStep(gridStep)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Black, color = Neo.Paper)

    val labels: List<RulerLabel> = remember(endSec, gridStep, labelStep, measurer, labelStyle) {
        buildList {
            var s = 0.0
            while (s <= endSec + gridStep + 0.001) {
                if (isLabelSec(s, labelStep)) {
                    add(RulerLabel(s, measurer.measure(AnnotatedString(fmtClock(s)), style = labelStyle)))
                }
                s += gridStep
            }
        }
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .border(2.dp, Neo.Ink),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(contentWidth)) {
                // Ruler
                Box(Modifier.fillMaxWidth().height(RulerH).background(Neo.Ink)) {
                    Canvas(Modifier.fillMaxSize()) {
                        for (l in labels) {
                            val x = (l.sec * ppsPx).toFloat()
                            drawLine(
                                color = Neo.Paper.copy(alpha = 0.85f),
                                start = Offset(x, size.height),
                                end = Offset(x, if (l.sec % 4.0 == 0.0) size.height * 0.30f else size.height * 0.55f),
                                strokeWidth = if (l.sec % 4.0 == 0.0) 3f else 1f,
                            )
                            drawText(l.layout, topLeft = Offset(x + 4f, 3f))
                        }
                    }
                    PlayheadFragment(ctrl, ppsPx, RulerH, isRuler = true)
                }
                // Lanes
                ctrl.project.tracks.forEach { t ->
                    Lane(ctrl, t, LaneH, ppsPx, gridStep, density)
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private fun isLabelSec(s: Double, step: Double): Boolean {
    val r = s % step
    return r < 1e-6 || step - r < 1e-6
}

private fun chooseGridStep(beatSec: Double, ppsPx: Float): Double {
    var step = beatSec
    while (step * ppsPx < 24.0) step *= 2.0
    while (step * ppsPx > 260.0) step /= 2.0
    return step
}

private fun pickLabelStep(gridStep: Double): Double {
    var s = 1.0
    while (gridStep > s) s *= 2.0
    return s
}

private fun fmtClock(sec: Double): String {
    val total = sec.toInt().coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** Red playhead fragment spanning one lane or the ruler. */
@Composable
private fun PlayheadFragment(ctrl: StudioController, ppsPx: Float, height: Dp, isRuler: Boolean) {
    val xPx = (ctrl.playheadSec * ppsPx).roundToInt()
    Box(
        Modifier
            .offset { IntOffset(xPx - 1, 0) }
            .width(if (isRuler) 4.dp else 3.dp)
            .height(height)
            .background(if (isRuler) Neo.Accent else Neo.Record)
    )
}

@Composable
private fun Lane(
    ctrl: StudioController,
    t: Track,
    laneH: Dp,
    ppsPx: Float,
    gridStep: Double,
    density: androidx.compose.ui.density.Density,
) {
    val canSeek = !ctrl.playing && !ctrl.recording
    Box(
        Modifier
            .fillMaxWidth()
            .height(laneH)
            .background(Neo.Paper)
            .border(width = 1.dp, color = Neo.Ink.copy(alpha = 0.35f))
            .pointerInput(canSeek) {
                detectTapGestures {
                    if (canSeek) ctrl.seekTo((it.x / ppsPx).toDouble())
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            var g = 0.0
            while (true) {
                val x = (g * ppsPx).toFloat()
                if (x > size.width) break
                drawLine(
                    color = Neo.Disabled.copy(alpha = 0.45f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
                g += gridStep
            }
        }
        t.clips.forEach { c ->
            ClipBlock(ctrl, t, c, ppsPx, density)
        }
        PlayheadFragment(ctrl, ppsPx, laneH, isRuler = false)
    }
}

@Composable
private fun ClipBlock(
    ctrl: StudioController,
    t: Track,
    c: Clip,
    ppsPx: Float,
    density: androidx.compose.ui.density.Density,
) {
    val xPx = (c.startSec * ppsPx).roundToInt()
    val wPx = (c.lengthSec * ppsPx).roundToInt().coerceAtLeast(8)
    val selected = ctrl.selectedClipId == c.id
    val color = Neo.trackColor(t.color)
    val clipColor = if (selected) color.copy(alpha = 1f) else color.copy(alpha = 0.78f)
    val showLabel = wPx > 70
    Box(
        Modifier
            .offset { IntOffset(xPx, 0) }
            .width(with(density) { wPx.toDp() })
            .fillMaxHeight()
            .padding(vertical = 3.dp)
            .shadow(3.dp, RectangleShape, spotColor = Neo.Ink, ambientColor = Neo.Ink)
            .background(clipColor)
            .border(2.dp, if (selected) Neo.Accent else Neo.Ink)
            .clickable { ctrl.selectClip(t.id, c.id) }
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (showLabel) {
            Column {
                Text(c.name, color = Neo.Paper, fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text("${c.lengthSec.toInt()}s", color = Neo.Paper.copy(alpha = 0.85f), fontSize = 8.sp, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Right: clip inspector
// ---------------------------------------------------------------------------

@Composable
private fun ClipInspector(ctrl: StudioController, t: Track, c: Clip, modifier: Modifier = Modifier) {
    val color = Neo.trackColor(t.color)
    Column(
        modifier
            .border(2.dp, Neo.Ink)
            .background(Neo.PaperRaised)
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).background(color).border(2.dp, Neo.Ink))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("CLIP", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Neo.InkSoft, letterSpacing = 1.sp)
                Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.Black, color = Neo.Ink, maxLines = 1)
            }
            NeoIconButton(Icons.Filled.Close, onClick = { ctrl.clearClipSelection() })
            Spacer(Modifier.width(4.dp))
            NeoIconButton(
                Icons.Filled.Delete,
                onClick = { ctrl.deleteClip(t.id, c.id) },
                contentColor = Neo.RecordDark,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "On ${t.name}  •  ${AudioCleanup.durationText(c.lengthSec)}  •  starts at ${fmtClock(c.startSec)}",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Neo.InkSoft,
        )
        Spacer(Modifier.height(10.dp))

        val gainDb = (20.0 * log10(c.gain.toDouble().coerceAtLeast(1e-4))).toFloat()
        NeoLabeledSlider(
            label = "GAIN",
            value = gainDb,
            onValueChange = { db -> ctrl.setClipGain(t.id, c.id, 10f.pow(db / 20f)) },
            min = -18f,
            max = 6f,
            readout = "${gainDb.roundToInt()} dB",
            help = "Loudness of this clip inside its track. Try to keep the loudest parts near 0 dB.",
        )
        NeoLabeledSlider(
            label = "FADE IN",
            value = c.fadeInSec.toFloat(),
            onValueChange = { s -> ctrl.setClipFadeIn(t.id, c.id, s.toDouble()) },
            max = 4f,
            readout = String.format(Locale.US, "%.1fs", c.fadeInSec),
            help = "Smooth ramp from silence at the start — removes clicks and breath pops.",
        )
        NeoLabeledSlider(
            label = "FADE OUT",
            value = c.fadeOutSec.toFloat(),
            onValueChange = { s -> ctrl.setClipFadeOut(t.id, c.id, s.toDouble()) },
            max = 4f,
            readout = String.format(Locale.US, "%.1fs", c.fadeOutSec),
            help = "Smooth ramp to silence at the end — avoids hard, clipped endings.",
        )

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CLEAN UP AUDIO", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
            Spacer(Modifier.weight(1f))
            Explain(
                "Fixes baked into the take itself: loudness, hiss, rumble and dead air. " +
                    "Each runs instantly and is saved straight into the clip.",
            )
        }
        Spacer(Modifier.height(6.dp))
        CleanOp.entries.forEach { op ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                NeoButton(
                    op.label,
                    onClick = { ctrl.cleanClip(t.id, c.id, op) },
                    modifier = Modifier.width(132.dp),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(op.blurb, fontSize = 10.sp, color = Neo.InkSoft, modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(10.dp))
        NeoLabel("FX on this track")
        Spacer(Modifier.height(4.dp))
        val activeFx = buildList {
            if (t.fx.hpOn) add("High-pass ${t.fx.hpHz} Hz")
            if (t.fx.lpOn) add("Low-pass ${t.fx.lpHz} Hz")
            if (t.fx.driveOn) add("Drive")
            if (t.fx.delayOn) add("Delay ${t.fx.delayMs.toInt()} ms")
            if (t.fx.reverbOn) add("Reverb")
        }
        Text(
            if (activeFx.isEmpty()) "No effects here yet. Open the MIXER tab to add EQ, drive, delay or reverb."
            else activeFx.joinToString("  •  "),
            fontSize = 10.sp,
            color = Neo.InkSoft,
        )
        Spacer(Modifier.height(8.dp))
        NeoButton(
            "Remove this clip",
            onClick = { ctrl.deleteClip(t.id, c.id) },
            modifier = Modifier.fillMaxWidth(),
            container = Neo.PaperRaised,
            contentColor = Neo.RecordDark,
            fontSize = 12.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

@Composable
private fun TransportBar(ctrl: StudioController, micGuard: (() -> Unit) -> Unit) {
    val endSec = ctrl.project.arrangementEndSec().coerceAtLeast(8.0)
    Row(
        Modifier
            .fillMaxWidth()
            .border(3.dp, Neo.Ink)
            .background(Neo.Ink)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ctrl.recording) {
            NeoButton("STOP REC", onClick = { ctrl.stopRecording() }, container = Neo.Record, modifier = Modifier.width(104.dp), fontSize = 13.sp)
        } else {
            NeoButton(
                "REC",
                onClick = { micGuard { ctrl.startRecording() } },
                container = if (ctrl.anyArmed) Neo.RecordDark else Neo.Disabled,
                modifier = Modifier.width(80.dp),
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        NeoIconButton(
            if (ctrl.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            onClick = { ctrl.togglePlayPause() },
            container = Neo.Accent,
            iconSize = 30,
        )
        Spacer(Modifier.width(4.dp))
        NeoIconButton(Icons.Filled.Stop, onClick = { ctrl.stopTransport() })
        Spacer(Modifier.width(4.dp))
        NeoChip(
            if (ctrl.loopEnabled) "LOOP" else "ONE",
            active = ctrl.loopEnabled,
            onClick = { ctrl.toggleLoop() },
            activeColor = Neo.Ok,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            when {
                ctrl.recording -> "● RECORDING — mic input ${if (ctrl.monitorOn) "(monitor on)" else ""}"
                ctrl.playing -> "▶ PLAYING"
                else -> "STOPPED"
            },
            color = when {
                ctrl.recording -> Neo.Record
                ctrl.playing -> Neo.Ok
                else -> Neo.Paper.copy(alpha = 0.75f)
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.weight(1f))

        Column(Modifier.width(300.dp)) {
            NeoSlider(
                value = ctrl.playheadSec.toFloat(),
                onValueChange = { ctrl.seekTo(it.toDouble()) },
                max = endSec.toFloat(),
                fill = Neo.Accent,
                modifier = Modifier.height(22.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtClock(ctrl.playheadSec), color = Neo.Paper, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(fmtClock(endSec), color = Neo.Paper.copy(alpha = 0.6f), fontSize = 10.sp)
            }
        }

        Spacer(Modifier.width(14.dp))
        Column(Modifier.width(80.dp), horizontalAlignment = Alignment.End) {
            Text("BPM", color = Neo.Paper.copy(alpha = 0.6f), fontSize = 9.sp, fontWeight = FontWeight.Black)
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeoChip("−", active = false, onClick = { ctrl.setBpm(ctrl.project.bpm - 1) })
                Text(
                    "${ctrl.project.bpm}",
                    color = Neo.Paper,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                NeoChip("+", active = false, onClick = { ctrl.setBpm(ctrl.project.bpm + 1) })
            }
        }
    }
}
