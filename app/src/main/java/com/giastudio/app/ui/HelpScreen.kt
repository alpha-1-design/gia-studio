package com.giastudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giastudio.app.StudioController
import com.giastudio.app.ui.theme.Neo

@Composable
fun HelpScreen(ctrl: StudioController) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Neo.Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoLabel("HELP & GUIDE", color = Neo.Ink)
            Spacer(Modifier.weight(1f))
            Text("GIA Studio 0.3.0 — open source (GPLv3)", fontSize = 9.sp, color = Neo.InkSoft, fontWeight = FontWeight.Bold)
        }

        Section("GIA Studio in 60 seconds") {
            Bullet("Everything is a TRACK — a lane on the timeline. Recordings, built-in beats and melodies each get their own lane, and the MIXER mixes all of them.")
            Bullet("CREATE tab makes music from thin air: a built-in drum kit and synth sounds (e-piano, pluck, lead, pad, bass) — no instruments or samples needed.")
            Bullet("Landscape studio: hold your phone sideways — the app is designed for that.")
            Bullet("Work stays on your device until you export: top bar → SAVE keeps the session, EXPORT makes a WAV you can share.")
        }

        Section("Making music without playing anything (CREATE tab)") {
            Bullet("DRUMS: 8 pads (kick, snare, clap, closed & open hat, toms, rim) over a 16-step grid. Tap pads on = a beat. Longer in BARS (1–8), faster/slower in TEMPO.")
            Bullet("MELODY: same grid, but rows are notes inside a SCALE — choose Pentatonic/Major/Minor, a root note and a pitch range, and you literally cannot tap a wrong note.")
            Bullet("Pick the SOUND per melody: E-Piano, Pluck, Lead, Pad or Bass — five built-in instruments, synthesized live.")
            Bullet("▶ HEAR IT loops the pattern so you can judge it; tap any row pad to hear that sound alone; ＋ ADD TO SONG renders it to its own lane.")
            Bullet("First instrument added starts a new lane at 0:00; adding another to the same lane chains a new section after it (snapped to the bar).")
            Bullet("LOAD DEMO SONG replaces the session with “Afterglow” — a full 16-bar song you can play, mute lanes, delete clips, re-mix, and record over. It is the fastest way to learn.")
        }

        Section("Keeping sounds separate (voice vs music)") {
            Bullet("Sources never get glued together: voice you record lands on its own clip; generated beats/melodies each get their own lane (Beat, Keys, Bass, Lead...).")
            Bullet("Each lane has independent volume, pan, MUTE and SOLO in the MIXER, plus its own effects — so you can re-balance or silence any part without touching the rest.")
            Bullet("Demo loaded? Mute the Beat lane and sing over just keys + bass. That is how a studio separates sound from voice: clean layers, mixed by you.")
            Bullet("Splitting one already-mixed recording into stems (AI “remove the vocals”) needs an on-device neural network and its model — that is on the roadmap, and GIA won't fake it.")
        }

        Section("Recording your voice or an instrument") {
            Bullet("Pick a track and tap the red ● (ARM). Its take will land there.")
            Bullet("Press REC. Sing or play; the red level meter on the MIXER shows your input — keep it out of the red.")
            Bullet("Press STOP REC. GIA auto-cleans the take (removes rumble + hiss between phrases) — you can turn that off per track under AUTO-CLEAN in the mixer's effect panel.")
            Bullet("MON in the mixer plays your mic back live while recording (feedback risk — headphones help).")
            Bullet("Tap a clip afterwards to fine-tune it: gain, fades, and CLEAN UP tools.")
        }

        Section("Cleaning up a take (CLEAN UP AUDIO)") {
            Bullet("Normalize — sets the loudest moment to a strong level so the clip isn't too quiet.")
            Bullet("Fix DC — removes low rumble/offset that makes audio feel muddy or thumpy.")
            Bullet("Denoise — quiets hiss and room tone between sounds.")
            Bullet("Trim silence — cuts dead air from the start and end.")
            Bullet("Fade in/out — removes clicks and makes entrances/exits smooth.")
            Bullet("Every tool is instant and non-destructive in spirit: it writes a new take. If a result is bad, delete the clip and re-record.")
        }

        Section("Mixing (the MIXER tab)") {
            Bullet("Fader = loudness of that track. PAN moves it left/right in the stereo image.")
            Bullet("M mute, S solo (only soloed tracks play), R arm for recording.")
            Bullet("Effects per track — tap a strip, then toggle stages:")
            Bullet("• HIGH-PASS EQ cuts mud below X Hz (vocals ~80–120 Hz).", indent = true)
            Bullet("• LOW-PASS EQ cuts harshness above X Hz.", indent = true)
            Bullet("• DRIVE adds warm saturation/edge.", indent = true)
            Bullet("• DELAY echoes the sound in time (great for vocals/guitar).", indent = true)
            Bullet("• REVERB puts the track in a room — a little goes far.", indent = true)
            Bullet("MASTER: the final limiter catches peaks so your export never distorts. Leave it on.")
        }

        Section("Tuning your voice (the TUNER tab)") {
            Bullet("Press START TUNING and sing one steady note.")
            Bullet("The big letter is the nearest note; the meter shows how far off you are in cents (100 cents = one semitone).")
            Bullet("±5 cents is in tune — professional singers sit inside that window.")
            Bullet("Tune your take before recording; pitch-correcting recorded audio (auto-tune style) is on the roadmap.")
        }

        Section("Sessions, saving and export") {
            Bullet("SAVE writes the session JSON into the app's private storage (it opens instantly next time).")
            Bullet("OPEN lists saved sessions. NEW starts fresh.")
            Bullet("EXPORT renders the whole arrangement through your mixer settings into a stereo WAV — choose 16-bit for sharing, 24-bit for further editing/mastering.")
            Bullet("Exports are shareable: WhatsApp, Drive, email — whatever your phone offers.")
        }

        Section("Updating the app (Settings tab)") {
            Bullet("GIA can update itself: Settings → CHECK FOR UPDATES compares your version with the project's GitHub Releases and downloads a newer build straight to your phone.")
            Bullet("When the download finishes, tap INSTALL NOW and follow the system prompt — allow \"install unknown apps\" for GIA Studio if your phone asks.")
            Bullet("Updates keep your sessions and recordings — only the app itself is replaced. Your work lives in the app's private storage.")
            Bullet("No internet? The app still works fully offline — updates simply wait until you're connected.")
        }

        Section("The C++ audio core (Settings tab)") {
            Bullet("Version 0.3 starts the real engine: a C++ core built on Oboe (Google's low-latency audio library) that hosts plugins in a real-time render loop.")
            Bullet("In Settings you can START ENGINE and PLAY TEST NOTE to hear it — a small native synthesizer hosted through the plugin system.")
            Bullet("It is deliberately experimental: the normal Kotlin engine keeps doing all session work, and the app falls back to it automatically if the native core isn't present.")
            Bullet("This is the foundation for the big milestone: installing third-party instruments and effects (AAP/CLAP/VST3-style plugins) like a desktop DAW.")
        }

        Section("Open roadmap (honest notes)") {
            Bullet("Version 0.2 added the built-in instrument engine (drum kit + 5 synth voices), the beat/melody pattern grids, and a full demo song. Every one of them renders to real clips you can edit.")
            Bullet("Version 0.3 added over-the-air updates (Settings → Check for updates) and started the C++ core: Oboe + a real plugin ABI with the first native synthesizer, hosted in a real-time render loop.")
            Bullet("Planned next: route the Create tab's instruments through the native core, clip move/crop/split on the timeline, more drums & voices (808 kit, strings, choir), velocity & swing, more FX (compressor, chorus, parametric EQ), MP3/FLAC export.")
            Bullet("Planned engine work: external plugin loading — installing AAP/CLAP/VST3-format instruments & effects like a desktop DAW (the plugin ABI in 0.3 was designed for this).")
            Bullet("Planned AI: on-device stem separation (vocal removal from a mixed recording) and auto-tune-style pitch correction — both need a bundled neural model, so they arrive as a real milestone, not a stub.")
            Bullet("GIA Studio is free software under the GNU GPL v3 — fork it, learn from it, build on it.")
        }

        Section("Problems?") {
            Bullet("No sound / can't record: allow the microphone permission when asked; check MON is off if you hear feedback.")
            Bullet("App feels quiet: raise the MASTER fader and your phone volume; turn the LIMITER off only if you understand clipping.")
            Bullet("A clip is missing after OPEN: its audio file wasn't in this install's storage — re-record or re-import.")
            Bullet("Only WAV imports/exports in this version: convert MP3s with a free converter first (roadmap adds MP3/FLAC).")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Neo.PaperRaised)
            .border(2.dp, Neo.Ink)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(14.dp).height(14.dp).background(Neo.Accent).border(2.dp, Neo.Ink))
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Black, color = Neo.Ink)
        }
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
private fun Bullet(text: String, indent: Boolean = false) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = Neo.Ink,
        lineHeight = 15.sp,
        modifier = Modifier.padding(start = if (indent) 16.dp else 0.dp),
    )
}
