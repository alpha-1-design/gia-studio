# GIA Studio

**A pocket music studio for Android — landscape-first, open source (GPLv3).**

GIA Studio turns your phone into a small recording studio **and** a music
making machine. Build a beat and a melody from scratch with built-in
instruments — a drum kit plus five synthesized voices (e-piano, pluck, lead,
pad, bass) — drop them onto their own lanes, record your voice or an
instrument over the top, clean up takes, shape every layer's sound with
per-track effects, tune yourself against a live tuner, and export a finished
stereo WAV you can share anywhere.

Built entirely with **Kotlin + Jetpack Compose** and the Android platform audio
APIs (low-latency `AudioTrack`/`AudioRecord`). No web app, no external
services, no dependencies to manage — every instrument is synthesized on your
device from pure Kotlin DSP, so everything works offline with zero downloads.

> Version 0.4.0 — production studio step: **clip editing + Undo/Redo**,
> **velocity / swing / humanize**, **Acoustic + 808 kits**, nine synth voices
> (including Strings, Choir, Organ, Bell), and **parametric EQ + compressor**.
> Everything still runs offline on-device. Next: timeline drag handles,
> pattern chaining, more FX, native audio path, stem export.

---

## What it does (all real, none of it is a stub)

| Area | Features |
| --- | --- |
| **Create** | Step-grid beat maker with **Acoustic + 808 kits**, velocity (tap to cycle), **swing & humanize**, and melody grid with 9 synth voices (E-Piano, Pluck, Lead, Pad, Bass, **Strings, Choir, Organ, Bell**) locked to scale (incl. Dorian/Mixolydian) |
| **Built-in demo** | “Afterglow” — a finished 16-bar song (beat, bass, e-piano chords, pluck melody) generated from the synth bank; load it, play it, mute lanes, delete clips, sing over it, re-mix it |
| **Timeline** | Up to 8 tracks, multiple clips per track, **move / trim / split / duplicate**, Undo/Redo (40 steps), per-lane tap-to-seek, looping, BPM grid |
| **Recording** | Microphone input with optional live monitoring, recording-level meter, per-track **auto-clean** on capture |
| **Audio cleanup** | Offline DSP tools per clip: normalize, remove DC/rumble, adaptive noise gate (denoise), silence trim, fades |
| **Mixer** | Per-track fader, pan, mute/solo/arm, master fader + peak limiter, input monitoring toggle |
| **Effects** | Per-track insert chain: HP/LP, **parametric EQ**, **soft-knee compressor**, drive, delay, reverb — identical live and in exports |
| **Tuner** | Real autocorrelation pitch detection (60 Hz–1.4 kHz), note name + cents meter for tuning your voice/instrument |
| **Export** | Offline mixdown through the same DSP graph → stereo WAV (16 or 24-bit, optional normalize), share sheet |
| **Sessions** | JSON session files (arrangement + mixer + FX state) saved/opened from the app |
| **OTA updates** | Settings → Check for updates: fetches the latest signed APK from the project's GitHub Releases and installs it in-place, keeping all sessions |
| **Native core** | First slice of the C++ engine: Oboe output stream + plugin ABI v1 + a polyphonic native test synth, hosted in a real-time render loop (Settings → Audio Core) |
| **UX** | Landscape-only, neobrutal-minimal UI, built-in help sections, guided first-run, inline "?" explanations on every tool |
| **Import** | Bring your own WAV files into any track |

### Architecture

```
app/src/main/java/com/giastudio/app/
├── MainActivity.kt            entry point (owns the audio engine lifecycle)
├── StudioController.kt        session state, file IO, transport/record/export orchestration
├── model/StudioProject.kt     session model + JSON persistence (org.json)
├── audio/
│   ├── AudioEngine.kt         real-time mixer thread, recorder, tuner, pattern preview channel
│   ├── Render.kt              thread-safe session snapshot + the one DSP render path
│   ├── Dsp.kt                 biquads, noise gate, drive, delay, reverb, peak limiter
│   ├── WavCodec.kt            WAV read (8/16/24/32-bit PCM + float) / streaming write
│   ├── Cleanup.kt             offline cleanup tools
│   ├── Mixdown.kt             offline stereo render (same code as live playback)
│   └── Pitch.kt               autocorrelation pitch detector + note helpers
├── music/
│   ├── Instruments.kt         the synth bank: 8 drum one-shots + 5 voices, pure Kotlin DSP
│   ├── Patterns.kt            step grids, stem/event model + renderer, scale theory
│   └── DemoSongs.kt           “Afterglow”: 16-bar demo composition (rendered at load)
├── update/UpdateManager.kt    OTA: checks GitHub Releases, downloads + installs the new APK
└── ui/                        Compose screens: Studio, Create, Mixer, Tuner, Settings, Help + kit
```

```
app/src/main/cpp/              the native core (built with -PwithNative)
├── CMakeLists.txt             Oboe 1.9.3 via FetchContent
├── giastudio_plugin.h         plugin ABI v1 (CLAP-like descriptor, stereo-interleaved)
├── plugins/TestSynth.cpp/.h   built-in polyphonic saw+filter+ADSR synth
├── engine/NativeEngine.cpp/.h Oboe stream, plugin chain, low-latency buffer recipe
└── jni_bridge.cpp             JNI_OnLoad + RegisterNatives → Kotlin NativeCore
```

Key engineering decisions:

- **One render path.** Live playback and offline export both call
  `Render.renderBlock`, so what you hear is exactly what you export
  (deterministic FX chains, tails included).
- **Create = stems into clips.** Pattern grids and the demo render through
  `SongRender` into ordinary mono WAV clips on the timeline — so everything
  you already know (mixer, FX, cleanup, export) applies to generated music.
- **Audio-thread safety.** The session is snapshotted into immutable
  `SnapProject` structs; the render thread never touches live UI state.
- **Latency-minded.** Uses the device's native output sample rate,
  float PCM and `PERFORMANCE_MODE_LOW_LATENCY` output. The C++ core (built
  with `-PwithNative`) adds [Oboe](https://github.com/google/oboe) AAudio
  output with an exclusive low-latency stream and a 2-burst buffer recipe.
- **OTA updates.** Every successful CI run publishes the signed release APK
  to GitHub Releases; the app checks that channel in Settings. The release
  signing key is generated on first CI run and cached, so updates install
  over the previous version without uninstalling.
- **No backend.** Recordings and generated stems live in the app's private
  storage; the only network permission is for update checks/downloads.

## Build it yourself

Requirements: JDK 17, Android SDK (platform 35). The Gradle wrapper is
included.

```bash
./gradlew assembleDebug          # debug APK (installable)
./gradlew assembleRelease -PwithNative   # release incl. the C++ core
# Without -PwithNative the C++ core is skipped — the Kotlin engine runs
# everything and no NDK is required. APKs land in app/build/outputs/apk/{debug,release}/
```

### Building the APK on GitHub

`.github/workflows/build-apk.yml` builds both APKs (with the native core) on
every push to `main` (and on manual `workflow_dispatch`), uploads them as an
artifact, **and publishes the signed release APK as a GitHub Release** — that
release is the app's over-the-air update channel (Settings → Check for
updates).

The release is signed with a keystore generated on the first CI run and kept
in the workflow's cache, so every build shares one stable key and updates
install over the previous version. (If the cache is ever lost, uninstall the
app once and reinstall — sessions are in private storage and are lost only
if you uninstall, so back up with Export/SAVE first.)

## Using it (60 seconds)

1. Hold your phone **sideways** (the studio is landscape-first).
2. **CREATE → LOAD DEMO SONG** — hear a finished song, then pull it apart on
   the Studio tab (mute the Beat lane, delete the melody, re-mix it).
3. **CREATE → DRUMS**: tap pads to build a beat → **＋ ADD TO SONG**. Switch to
   **MELODY**, pick a sound (try E-Piano or Pluck), paint notes → **＋ ADD**.
4. Arm the red ● on the Vocal track, press **REC** on the Studio tab, and sing
   over your track.
5. Tap a clip → **Clean Up Audio** + fades. **Mixer** shapes each layer.
   **Tuner** checks your pitch. **Export** renders the WAV and opens the share
   sheet.

Everything is explained in plain language inside the app (**Help** tab), with
a guided first-run on your very first launch.

## Roadmap (v0.3 → v1.0)

- [x] C++ core started: Oboe + plugin ABI v1 + native test synth (v0.3)
- [x] Over-the-air updates via GitHub Releases (v0.3)
- [x] Clip editing + Undo/Redo (v0.4)
- [x] Velocity, swing, humanize; Acoustic + 808 kits; 9 synth voices (v0.4)
- [x] Parametric EQ, compressor, chorus (v0.4)
- [x] Timeline drag move / edge trim (v0.4)
- [x] Stem export + project package ZIP (v0.4)
- [ ] Route Create instruments through the native/Oboe core
- [ ] External plugin loading (AAP/CLAP/VST3)
- [ ] Pattern chaining & scene launcher
- [ ] Piano-roll / MIDI editor
- [ ] De-esser, sidechain ducking
- [ ] MP3 + FLAC export
- [ ] Sub-20 ms latency target
- [ ] On-device AI: stem separation + pitch correction (real models)

## License

GPLv3 — see [LICENSE](LICENSE). Free software: use it, study it, fork it,
build on it.
