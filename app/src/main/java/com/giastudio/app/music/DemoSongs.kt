package com.giastudio.app.music

/**
 * Built-in demo song — a complete, editable reference arrangement so new
 * users can hear what GIA Studio can do and then pull it apart: delete clips,
 * re-mix it, mute lanes, sing over it.
 *
 * "Afterglow" — 100 BPM, C-major feel, 16 bars (~40 s). Rendered entirely
 * from the [Synth] bank at first load; no audio assets are shipped.
 */
object DemoSongs {

    const val BPM = 100
    const val NAME = "Afterglow (Demo)"

    fun afterglow(): List<StemSpec> {
        val sd = SongRender.stepDurSec(BPM)
        val time = { bar: Int, step: Int -> ((bar * STEPS_PER_BAR + step).toDouble()) * sd }

        // ---------------------------------------------------------- drums
        val drums = ArrayList<MusicEvent>()
        for (bar in 0 until 16) {
            // Hats: steady eighths, accented quarters, every bar.
            for (step in 0 until 16 step 2) {
                val acc = step % 4 == 0
                drums.add(MusicEvent.Drum(DrumSound.HAT, time(bar, step), if (acc) 0.85f else 0.55f))
            }
            // Open hat spilling into the even bars for lift.
            if (bar % 2 == 0) {
                drums.add(MusicEvent.Drum(DrumSound.OPEN_HAT, time(bar, 14), 0.7f))
            }
            val fill = bar == 7 || bar == 15
            if (fill) {
                drums.add(MusicEvent.Drum(DrumSound.KICK, time(bar, 0), 1f))
                drums.add(MusicEvent.Drum(DrumSound.KICK, time(bar, 4), 0.9f))
                drums.add(MusicEvent.Drum(DrumSound.KICK, time(bar, 8), 0.9f))
                drums.add(MusicEvent.Drum(DrumSound.SNARE, time(bar, 12), 0.95f))
                drums.add(MusicEvent.Drum(DrumSound.TOM_LO, time(bar, 13), 0.85f))
                drums.add(MusicEvent.Drum(DrumSound.TOM_HI, time(bar, 14), 0.85f))
            } else {
                drums.add(MusicEvent.Drum(DrumSound.KICK, time(bar, 0), 1f))
                drums.add(MusicEvent.Drum(DrumSound.KICK, time(bar, 4), 0.9f))
                drums.add(MusicEvent.Drum(DrumSound.KICK, time(bar, 8), 0.9f))
                drums.add(MusicEvent.Drum(DrumSound.KICK, time(bar, 12), 0.95f))
                drums.add(MusicEvent.Drum(DrumSound.SNARE, time(bar, 4), 0.95f))
                drums.add(MusicEvent.Drum(DrumSound.SNARE, time(bar, 12), 0.98f))
            }
        }

        // ---------------------------------------------------------- chords
        // One chord every two bars; C C G G Am Am F F x2, closing on C.
        val chords = listOf("C", "C", "G", "G", "Am", "Am", "F", "F",
                            "C", "C", "G", "G", "Am", "Am", "F", "C")
        val keys = ArrayList<MusicEvent>()
        for (bar in 0 until 16) {
            val name = chords[bar]
            // Chords every two beats, alternating a low then a high voicing.
            keys.addAll(chord(name, low = true).map {
                MusicEvent.Note(SynthSound.EPIANO, it, time(bar, 0), sd * 10.0, 0.62f)
            })
            keys.addAll(chord(name, low = false).map {
                MusicEvent.Note(SynthSound.EPIANO, it, time(bar, 8), sd * 10.0, 0.58f)
            })
        }

        // ---------------------------------------------------------- bass
        val bassRoot = mapOf("C" to 48, "G" to 43, "Am" to 45, "F" to 41)
        val bass = ArrayList<MusicEvent>()
        for (bar in 0 until 16) {
            val r = bassRoot[chords[bar]] ?: 48
            // Pulsing eighths: root / fifth / root octave groove.
            val steps = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14)
            val notes = intArrayOf(r, r + 7, r, r + 12, r, r + 7, r, r + 12)
            val vels = floatArrayOf(1f, 0.75f, 0.95f, 0.8f, 0.9f, 0.72f, 0.95f, 0.8f)
            for (i in steps.indices) {
                bass.add(MusicEvent.Note(SynthSound.BASS, notes[i], time(bar, steps[i]), sd * 1.8, vels[i]))
            }
        }

        // ---------------------------------------------------------- lead
        val lead = ArrayList<MusicEvent>()
        val m = { bar: Int, step: Int, midi: Int, vel: Float, durSteps: Int ->
            lead.add(MusicEvent.Note(SynthSound.PLUCK, midi, time(bar, step), sd * durSteps, vel))
        }
        // Phrase A (bars 1-8)
        m(1, 0, 76, 0.85f, 2); m(1, 2, 79, 0.7f, 2); m(1, 4, 81, 0.8f, 4)
        m(1, 8, 79, 0.65f, 2); m(1, 10, 76, 0.65f, 2); m(1, 12, 72, 0.75f, 4)
        m(2, 0, 74, 0.75f, 2); m(2, 2, 72, 0.7f, 2); m(2, 4, 76, 0.8f, 4)
        m(2, 8, 74, 0.65f, 2); m(2, 10, 72, 0.7f, 4)
        m(3, 0, 79, 0.8f, 2); m(3, 2, 76, 0.6f, 2); m(3, 4, 79, 0.75f, 2)
        m(3, 6, 81, 0.6f, 2); m(3, 8, 79, 0.8f, 4); m(3, 12, 74, 0.75f, 4)
        m(4, 0, 72, 0.7f, 2); m(4, 2, 74, 0.65f, 2); m(4, 4, 76, 0.75f, 4)
        m(4, 8, 74, 0.7f, 2); m(4, 10, 72, 0.7f, 2); m(4, 12, 69, 0.75f, 4)
        m(5, 0, 72, 0.85f, 4); m(5, 4, 74, 0.65f, 2); m(5, 6, 76, 0.7f, 2)
        m(5, 8, 69, 0.8f, 4); m(5, 12, 72, 0.7f, 4)
        m(6, 0, 76, 0.8f, 2); m(6, 2, 74, 0.65f, 2); m(6, 4, 72, 0.7f, 4)
        m(6, 8, 74, 0.7f, 2); m(6, 10, 76, 0.65f, 2); m(6, 12, 81, 0.8f, 4)
        m(7, 0, 77, 0.8f, 4); m(7, 4, 76, 0.6f, 2); m(7, 6, 74, 0.6f, 2)
        m(7, 8, 76, 0.75f, 4); m(7, 12, 77, 0.75f, 4)
        m(8, 0, 76, 0.7f, 2); m(8, 2, 74, 0.6f, 2); m(8, 4, 72, 0.8f, 6)
        m(8, 12, 74, 0.6f, 2); m(8, 14, 76, 0.7f, 2)
        // Phrase B (bars 9-15) — brighter, then a settled ending.
        m(9, 0, 84, 0.8f, 4); m(9, 4, 81, 0.6f, 2); m(9, 6, 79, 0.7f, 2)
        m(9, 8, 81, 0.8f, 4); m(9, 12, 79, 0.65f, 2); m(9, 14, 76, 0.65f, 2)
        m(10, 0, 74, 0.7f, 2); m(10, 2, 72, 0.6f, 2); m(10, 4, 76, 0.8f, 4)
        m(10, 8, 74, 0.7f, 2); m(10, 10, 76, 0.65f, 2); m(10, 12, 79, 0.7f, 4)
        m(11, 0, 79, 0.8f, 4); m(11, 4, 81, 0.65f, 2); m(11, 6, 79, 0.6f, 2)
        m(11, 8, 76, 0.75f, 4); m(11, 12, 74, 0.7f, 4)
        m(12, 0, 72, 0.75f, 4); m(12, 4, 74, 0.6f, 2); m(12, 6, 76, 0.6f, 2)
        m(12, 8, 79, 0.75f, 4); m(12, 12, 81, 0.8f, 4)
        m(13, 0, 81, 0.85f, 4); m(13, 4, 79, 0.6f, 2); m(13, 6, 76, 0.65f, 2)
        m(13, 8, 79, 0.75f, 2); m(13, 10, 81, 0.7f, 2); m(13, 12, 84, 0.85f, 6)
        m(14, 0, 84, 0.7f, 2); m(14, 2, 81, 0.6f, 2); m(14, 4, 77, 0.75f, 4)
        m(14, 8, 79, 0.7f, 2); m(14, 10, 81, 0.65f, 2); m(14, 12, 84, 0.7f, 2)
        m(15, 0, 84, 0.6f, 2); m(15, 2, 81, 0.65f, 2); m(15, 4, 79, 0.7f, 4)
        m(15, 8, 76, 0.8f, 6); m(15, 14, 72, 0.95f, 6)

        return listOf(
            StemSpec(
                kindName = "Drums", trackName = "Beat", label = "Beat groove",
                color = 1, volume = 0.8f, pan = 0f, bpm = BPM, events = drums, startSec = 0.0,
            ),
            StemSpec(
                kindName = "Keys", trackName = "Keys", label = "E-Piano chords",
                color = 2, volume = 0.62f, pan = -0.18f, bpm = BPM, events = keys, startSec = 0.0,
            ),
            StemSpec(
                kindName = "Bass", trackName = "Bass", label = "Bass line",
                color = 3, volume = 0.8f, pan = 0.05f, bpm = BPM, events = bass, startSec = 0.0,
            ),
            StemSpec(
                kindName = "Lead", trackName = "Lead", label = "Pluck melody",
                color = 4, volume = 0.72f, pan = 0.16f, bpm = BPM, events = lead, startSec = 0.0,
            ),
        )
    }

    private fun chord(name: String, low: Boolean): List<Int> = when (name) {
        "C" -> if (low) listOf(60, 64, 67) else listOf(64, 67, 72)
        "G" -> if (low) listOf(55, 59, 62) else listOf(59, 62, 67)
        "Am" -> if (low) listOf(57, 60, 64) else listOf(60, 64, 69)
        "F" -> if (low) listOf(53, 57, 60) else listOf(57, 60, 65)
        else -> listOf(60, 64, 67)
    }
}
