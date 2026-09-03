# GIA Studio

**A pocket digital audio workstation for Android — landscape-first, open source (GPLv3).**

GIA Studio turns your phone into a small recording studio. Record your voice or
instrument, arrange takes on a track timeline, clean up the audio, shape the
sound with per-track effects, tune yourself against a live tuner, and export a
finished stereo WAV you can share anywhere.

Built entirely with **Kotlin + Jetpack Compose** and the Android platform audio
APIs (low-latency `AudioTrack`/`AudioRecord`). No web app, no external
services, no dependencies to manage — everything runs on your device.

> Version 0.1.0 — this is the *first sprint* of a bigger studio: a complete,
> honest core loop (record → arrange → clean → mix → tune → export). The
> roadmap for deeper DAW features (MIDI/piano roll, plugin formats like
> AAP/CLAP/VST3 via a C++ core, MP3/FLAC export) is at the bottom of this file
> and inside the app under **Help**.

---

## What it does (all real, none of it is a stub)

| Area | Features |
| --- | --- |
| **Timeline** | Up to 8 tracks, clips placed along a shared time ruler, per-lane tap-to-seek, looping, BPM grid |
| **Recording** | Microphone input with optional live monitoring, recording-level meter, per-track **auto-clean** on capture |
| **Audio cleanup** | Offline DSP tools per clip: normalize, remove DC/rumble, adaptive noise gate (denoise), silence trim, fades |
| **Mixer** | Per-track fader, pan, mute/solo/arm, master fader + peak limiter, input monitoring toggle |
| **Effects** | Per-track insert chain: high-pass EQ, low-pass EQ, drive (tanh saturation), delay (feedback + mix), Schroeder reverb — identical live and in exports |
| **Tuner** | Real autocorrelation pitch detection (60 Hz–1.4 kHz), note name + cents meter for tuning your voice/instrument |
| **Export** | Offline mixdown through the same DSP graph → stereo WAV (16 or 24-bit, optional normalize), share sheet |
| **Sessions** | JSON session files (arrangement + mixer + FX state) saved/opened from the app |
| **UX** | Landscape-only, neobrutal-minimal UI, built-in help sections, guided first-run, inline "?" explanations on every tool |
| **Import** | Bring your own WAV files into any track |

### Architecture

```
app/src/main/java/com/giastudio/app/
├── MainActivity.kt            entry point (owns the audio engine lifecycle)
├── StudioController.kt        session state, file IO, transport/record/export orchestration
├── model/StudioProject.kt     session model + JSON persistence (org.json)
├── audio/
│   ├── AudioEngine.kt         real-time mixer thread, recorder, tuner capture
│   ├── Render.kt              thread-safe session snapshot + the one DSP render path
│   ├── Dsp.kt                 biquads, noise gate, drive, delay, reverb, peak limiter
│   ├── WavCodec.kt            WAV read (8/16/24/32-bit PCM + float) / streaming write
│   ├── Cleanup.kt             offline cleanup tools
│   ├── Mixdown.kt             offline stereo render (same code as live playback)
│   └── Pitch.kt               autocorrelation pitch detector + note helpers
└── ui/                        Compose screens: Studio, Mixer, Tuner, Help + neobrutal kit
```

Key engineering decisions:

- **One render path.** Live playback and offline export both call
  `Render.renderBlock`, so what you hear is exactly what you export
  (deterministic FX chains, tails included).
- **Audio-thread safety.** The session is snapshotted into immutable
  `SnapProject` structs; the render thread never touches live UI state.
- **Latency-minded.** Uses the device's native output sample rate,
  float PCM and `PERFORMANCE_MODE_LOW_LATENCY` output. (A C++ core with
  [Oboe](https://github.com/google/oboe) is the roadmap path to the lowest
  possible latency and plugin hosting.)
- **No backend.** No permissions except the microphone. Recordings live in
  the app's private storage.

## Build it yourself

Requirements: JDK 17, Android SDK (platform 35). The Gradle wrapper is
included.

```bash
./gradlew assembleDebug          # debug APK (installable)
./gradlew assembleRelease        # release; falls back to debug signing
# APKs land in app/build/outputs/apk/{debug,release}/
```

### Building the APK on GitHub

`.github/workflows/build-apk.yml` builds both APKs on every push to `main`
(and on manual `workflow_dispatch`) and uploads them as a downloadable
artifact. Install the artifact APK on your phone — you may need to allow
"install unknown apps" for your file manager/browser.

To produce a properly release-signed APK later, add repository secrets and a
`app/keystore.properties` in CI (see `app/build.gradle.kts`); the build
already supports it and gracefully falls back to the debug key.

## Using it (30 seconds)

1. Hold your phone **sideways** (the studio is landscape-first).
2. Tap the red **●** on a track to *arm* it, then press **REC**. Sing or play.
3. Stop, tap your new clip, and use **Clean Up Audio** + fades.
4. **Mixer** tab: faders, pan, EQ, drive, delay, reverb. **Tuner** tab: check
   your pitch. Top-bar **Export** renders the WAV and opens the share sheet.

Everything is explained in plain language inside the app (**Help** tab), with
a guided first-run on your very first launch.

## Roadmap (v0.2 → v1.0)

- [ ] MIDI tracks + piano-roll editor with quantized notes and velocities
- [ ] Built-in instruments (sampler + synths)
- [ ] Clip editing: move/crop/split/overlap, drag arrangement
- [ ] More FX: compressor, chorus, parametric EQ, de-esser
- [ ] MP3 + FLAC export, project backup/export of sessions
- [ ] C++ core with Oboe (sub-20 ms round-trip) + UAPMD/AAP/CLAP/VST3 plugin
      hosting, so third-party instruments and effects install like a desktop DAW
- [ ] Auto-tune-style vocal pitch correction built on the roadmap DSP core

## License

GPLv3 — see [LICENSE](LICENSE). Free software: use it, study it, fork it,
build on it.
