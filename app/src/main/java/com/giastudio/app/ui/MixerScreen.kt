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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giastudio.app.StudioController
import com.giastudio.app.model.Track
import com.giastudio.app.ui.theme.Neo
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

private const val MASTER = -1
private val StripH = 208.dp

@Composable
fun MixerScreen(ctrl: StudioController) {
    var focus by remember { mutableStateOf(0) }
    val focusTrack = ctrl.project.tracks.firstOrNull { it.id == focus }
    val focusMaster = focus == MASTER || focusTrack == null

    Column(Modifier.fillMaxSize().background(Neo.Paper)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoLabel("MIXER", color = Neo.Ink)
            Spacer(Modifier.width(10.dp))
            Text(
                "Faders = loudness. PAN places the sound left/right. Strip FX: HP LP DRV DLY RV.",
                fontSize = 10.sp,
                color = Neo.InkSoft,
                modifier = Modifier.weight(1f),
            )
            NeoChip("MON", active = ctrl.monitorOn, onClick = { ctrl.toggleMonitor() }, activeColor = Neo.Ok)
            Spacer(Modifier.width(4.dp))
            Explain("Monitor plays the mic through the speaker while recording — use headphones, or feedback will howl.")
        }

        Row(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(0.62f)
                    .fillMaxHeight()
                    .horizontalScroll(rememberScrollState())
                    .border(2.dp, Neo.Ink)
                    .background(Neo.PaperRaised)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ctrl.project.tracks.forEach { t ->
                    TrackStrip(ctrl, t, focused = focus == t.id, onClick = { focus = t.id })
                    Spacer(Modifier.width(8.dp))
                }
                MasterStrip(ctrl, focused = focusMaster, onClick = { focus = MASTER })
            }
            Spacer(Modifier.width(8.dp))
            if (focusMaster) {
                MasterPanel(ctrl)
            } else if (focusTrack != null) {
                FxPanel(ctrl, focusTrack)
            } else {
                PanelShell {
                    Text("Tap a strip on the left to edit its effects.", fontSize = 11.sp, color = Neo.InkSoft, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun PanelShell(content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(330.dp)
            .fillMaxHeight()
            .border(2.dp, Neo.Ink)
            .background(Neo.PaperRaised)
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Strips
// ---------------------------------------------------------------------------

@Composable
private fun TrackStrip(
    ctrl: StudioController,
    t: Track,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val color = Neo.trackColor(t.color)
    Column(
        Modifier
            .width(108.dp)
            .height(StripH)
            .border(3.dp, if (focused) Neo.Accent else Neo.Ink)
            .background(Neo.Paper)
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(color).border(1.dp, Neo.Ink))
            Spacer(Modifier.width(4.dp))
            Text(t.name.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1, modifier = Modifier.weight(1f))
            if (t.armed) Box(Modifier.size(8.dp).background(Neo.Record).border(1.dp, Neo.Ink))
        }
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            NeoChip("M", active = t.muted, onClick = { ctrl.toggleMute(t.id) }, activeColor = Neo.Record)
            NeoChip("S", active = t.soloed, onClick = { ctrl.toggleSolo(t.id) }, activeColor = Neo.Ok)
            NeoChip("R", active = t.armed, onClick = { ctrl.setArmed(t.id) }, activeColor = Neo.RecordDark)
        }
        Spacer(Modifier.height(2.dp))
        NeoFader(
            value = t.volume,
            onValueChange = { ctrl.setVolume(t.id, it) },
            max = 1.5f,
            modifier = Modifier.width(40.dp).height(96.dp),
        )
        Text("${dbOf(t.volume).roundToInt()} dB", fontSize = 8.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            FxDot("HP", t.fx.hpOn) { ctrl.setFx(t.id) { it.copy(hpOn = !it.hpOn) } }
            FxDot("LP", t.fx.lpOn) { ctrl.setFx(t.id) { it.copy(lpOn = !it.lpOn) } }
            FxDot("DRV", t.fx.driveOn) { ctrl.setFx(t.id) { it.copy(driveOn = !it.driveOn) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            FxDot("DLY", t.fx.delayOn) { ctrl.setFx(t.id) { it.copy(delayOn = !it.delayOn) } }
            FxDot("RV", t.fx.reverbOn) { ctrl.setFx(t.id) { it.copy(reverbOn = !it.reverbOn) } }
            Spacer(Modifier.width(1.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PAN", fontSize = 7.sp, fontWeight = FontWeight.Black, color = Neo.InkSoft)
            Spacer(Modifier.width(2.dp))
            NeoSlider(
                value = t.pan,
                onValueChange = { ctrl.setPan(t.id, it) },
                min = -1f,
                max = 1f,
                fill = color,
                modifier = Modifier.weight(1f).height(18.dp),
            )
        }
    }
}

@Composable
private fun FxDot(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) Neo.Ink else Neo.PaperRaised)
            .border(2.dp, Neo.Ink)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(label, color = if (active) Neo.Paper else Neo.Ink, fontSize = 8.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun MasterStrip(ctrl: StudioController, focused: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(126.dp)
            .height(StripH)
            .border(3.dp, if (focused) Neo.Accent else Neo.Ink)
            .background(Neo.Accent.copy(alpha = 0.2f))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MASTER", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            NeoChip("LIM", active = ctrl.project.masterLimiter, onClick = { ctrl.toggleMasterLimiter() }, activeColor = Neo.Ink)
            NeoChip("MON", active = ctrl.monitorOn, onClick = { ctrl.toggleMonitor() }, activeColor = Neo.Ok)
        }
        Spacer(Modifier.height(2.dp))
        NeoFader(
            value = ctrl.project.masterVolume,
            onValueChange = { ctrl.setMasterVolume(it) },
            max = 1.5f,
            fill = Neo.AccentHot,
            modifier = Modifier.width(40.dp).height(96.dp),
        )
        Text("${dbOf(ctrl.project.masterVolume).roundToInt()} dB", fontSize = 8.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
        Spacer(Modifier.height(2.dp))
        NeoMeter(if (ctrl.recording) ctrl.recLevel else 0f, Modifier.fillMaxWidth().height(12.dp))
        Text("REC LEVEL", fontSize = 7.sp, fontWeight = FontWeight.Black, color = Neo.InkSoft)
    }
}

private fun dbOf(linear: Float): Float =
    if (linear <= 1e-4f) -60f else 20f * (ln(linear.toDouble()) / ln(10.0)).toFloat()

// ---------------------------------------------------------------------------
// FX panel
// ---------------------------------------------------------------------------

@Composable
private fun FxPanel(ctrl: StudioController, t: Track) {
    val color = Neo.trackColor(t.color)
    PanelShell {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).background(color).border(2.dp, Neo.Ink))
            Spacer(Modifier.width(8.dp))
            Text("${t.name.uppercase()} — EFFECTS", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Chain: High-pass → Low-pass → Drive → Delay → Reverb. Sliders below appear when a stage is switched on.",
            fontSize = 10.sp,
            color = Neo.InkSoft,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoChip(
                if (t.inputGate) "AUTO-CLEAN ON" else "AUTO-CLEAN OFF",
                active = t.inputGate,
                onClick = { ctrl.toggleInputGate(t.id) },
                activeColor = Neo.Ok,
            )
            Spacer(Modifier.width(8.dp))
            Explain("New recordings on this track get cleaned automatically (rumble removed + hiss silenced). Turn off to keep the raw take.")
        }
        Spacer(Modifier.height(8.dp))

        val anyOn = t.fx.hpOn || t.fx.lpOn || t.fx.driveOn || t.fx.delayOn || t.fx.reverbOn
        if (!anyOn) {
            Text(
                "Nothing switched on for this track.\nVocals: HP ~90 Hz clears mud. Bass/drums: a touch of DRV adds punch. DLY/RV add space.",
                fontSize = 11.sp,
                color = Neo.InkSoft,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
        }

        SectionHeader("HIGH-PASS EQ", t.fx.hpOn) { ctrl.setFx(t.id) { it.copy(hpOn = !it.hpOn) } }
        if (t.fx.hpOn) {
            ExpoSlider(
                label = "CUT BELOW",
                valueHz = t.fx.hpHz.toFloat(),
                setHz = { hz -> ctrl.setFx(t.id) { it.copy(hpHz = hz) } },
            )
        }
        SectionHeader("LOW-PASS EQ", t.fx.lpOn) { ctrl.setFx(t.id) { it.copy(lpOn = !it.lpOn) } }
        if (t.fx.lpOn) {
            ExpoSlider(
                label = "CUT ABOVE",
                valueHz = t.fx.lpHz.toFloat(),
                setHz = { hz -> ctrl.setFx(t.id) { it.copy(lpHz = hz) } },
            )
        }
        SectionHeader("DRIVE", t.fx.driveOn) { ctrl.setFx(t.id) { it.copy(driveOn = !it.driveOn) } }
        if (t.fx.driveOn) {
            NeoLabeledSlider(
                label = "AMOUNT",
                value = t.fx.drive,
                onValueChange = { v -> ctrl.setFx(t.id) { it.copy(drive = v) } },
                max = 1f,
                readout = "${(t.fx.drive * 100).roundToInt()}%",
                help = "Analog-style saturation. A little adds body; more gets crunchy.",
            )
        }
        SectionHeader("DELAY", t.fx.delayOn) { ctrl.setFx(t.id) { it.copy(delayOn = !it.delayOn) } }
        if (t.fx.delayOn) {
            NeoLabeledSlider(
                label = "TIME",
                value = t.fx.delayMs,
                onValueChange = { v -> ctrl.setFx(t.id) { it.copy(delayMs = v) } },
                min = 30f,
                max = 1500f,
                readout = "${t.fx.delayMs.roundToInt()} ms",
                help = "Echo spacing. ~250–500 ms suits vocal phrases.",
            )
            NeoLabeledSlider(
                label = "REPEATS",
                value = t.fx.delayFeedback,
                onValueChange = { v -> ctrl.setFx(t.id) { it.copy(delayFeedback = v) } },
                max = 0.9f,
                readout = "${(t.fx.delayFeedback * 100).roundToInt()}%",
            )
            NeoLabeledSlider(
                label = "ECHO MIX",
                value = t.fx.delayMix,
                onValueChange = { v -> ctrl.setFx(t.id) { it.copy(delayMix = v) } },
                max = 1f,
                readout = "${(t.fx.delayMix * 100).roundToInt()}%",
            )
        }
        SectionHeader("REVERB", t.fx.reverbOn) { ctrl.setFx(t.id) { it.copy(reverbOn = !it.reverbOn) } }
        if (t.fx.reverbOn) {
            NeoLabeledSlider(
                label = "SPACE",
                value = t.fx.reverbMix,
                onValueChange = { v -> ctrl.setFx(t.id) { it.copy(reverbMix = v) } },
                max = 1f,
                readout = "${(t.fx.reverbMix * 100).roundToInt()}%",
                help = "Room size feel. Start ~20% so the dry sound stays clear.",
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SectionHeader(title: String, on: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NeoLabel(title, color = Neo.Ink)
        Spacer(Modifier.weight(1f))
        NeoChip("ON", active = on, onClick = onToggle, activeColor = Neo.Ok)
    }
    Spacer(Modifier.height(4.dp))
}

/** Log-frequency slider for filter cutoffs (fixed ranges for HP/LP). */
@Composable
private fun ExpoSlider(
    label: String,
    valueHz: Float,
    setHz: (Int) -> Unit,
) {
    val min = if (label == "CUT BELOW") 40f else 300f
    val max = if (label == "CUT BELOW") 3000f else 20000f
    val lmin = ln(min.toDouble())
    val lmax = ln(max.toDouble())
    val clamped = valueHz.coerceIn(min, max)
    val frac = ((ln(clamped.toDouble()) - lmin) / (lmax - lmin)).toFloat()
    val readout = String.format(Locale.US, "%.0f Hz", clamped)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(76.dp), maxLines = 1)
        NeoSlider(
            value = frac,
            onValueChange = { f ->
                val hz = Math.exp(lmin + (lmax - lmin) * f.toDouble()).toInt()
                setHz(hz.coerceIn(min.toInt(), max.toInt()))
            },
            modifier = Modifier.weight(1f),
        )
        Text(readout, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Neo.InkSoft, modifier = Modifier.width(62.dp))
    }
}

@Composable
private fun MasterPanel(ctrl: StudioController) {
    PanelShell {
        Text("MASTER BUS", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Everything is mixed through here. The limiter catches peaks so exports never clip — leave it ON.",
            fontSize = 10.sp,
            color = Neo.InkSoft,
        )
        Spacer(Modifier.height(10.dp))
        NeoLabeledSlider(
            label = "LEVEL",
            value = ctrl.project.masterVolume,
            onValueChange = { ctrl.setMasterVolume(it) },
            max = 1.5f,
            readout = "${dbOf(ctrl.project.masterVolume).roundToInt()} dB",
        )
        Spacer(Modifier.height(6.dp))
        NeoChip("LIMITER", active = ctrl.project.masterLimiter, onClick = { ctrl.toggleMasterLimiter() }, activeColor = Neo.Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Input monitoring: ${if (ctrl.monitorOn) "ON — you hear the mic while recording (headphones advised)." else "OFF — record silently, then play back."}",
            fontSize = 10.sp,
            color = Neo.InkSoft,
        )
        Spacer(Modifier.height(8.dp))
        NeoMeter(if (ctrl.recording) ctrl.recLevel else 0f, Modifier.fillMaxWidth().height(14.dp))
        Text(
            if (ctrl.recording) "Recording level — aim for yellow, never pinned red."
            else "Recording level shows here while recording.",
            fontSize = 9.sp,
            color = Neo.InkSoft,
        )
    }
}
