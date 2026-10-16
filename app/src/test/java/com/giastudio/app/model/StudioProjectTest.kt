package com.giastudio.app.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StudioProjectTest {
    @Test
    fun pianoRollPatternsRoundTripThroughProjectJson() {
        val original = StudioProject(
            name = "MIDI round trip",
            bpm = 108,
            pianoRollPatterns = mutableListOf(
                PianoRollPattern(
                    id = "piano_roll_slot_A",
                    name = "Piano Roll A",
                    bpm = 108,
                    instrument = "EPIANO",
                    rootKey = 7,
                    minorScale = true,
                    swing = 0.24f,
                    notes = mutableListOf(
                        PianoRollNote(midi = 60, step = 0, length = 4, velocity = 0.75f),
                        PianoRollNote(midi = 67, step = 8, length = 2, velocity = 0.55f),
                    ),
                ),
            ),
        )

        val restored = projectFromJson(JSONObject(original.toJson().toString()))
        val pattern = restored.pianoRollPatterns.single()

        assertEquals("MIDI round trip", restored.name)
        assertEquals("piano_roll_slot_A", pattern.id)
        assertEquals("Piano Roll A", pattern.name)
        assertEquals(108, pattern.bpm)
        assertEquals("EPIANO", pattern.instrument)
        assertEquals(7, pattern.rootKey)
        assertEquals(true, pattern.minorScale)
        assertEquals(0.24f, pattern.swing, 0.001f)
        assertEquals(2, pattern.notes.size)
        assertEquals(67, pattern.notes[1].midi)
        assertEquals(8, pattern.notes[1].step)
        assertEquals(0.55f, pattern.notes[1].velocity, 0.001f)
    }

    @Test
    fun legacyProjectWithoutPianoRollPatternsStillLoads() {
        val legacy = JSONObject()
            .put("name", "Older session")
            .put("bpm", 120)

        val restored = projectFromJson(legacy)

        assertEquals("Older session", restored.name)
        assertEquals(120, restored.bpm)
        assertEquals(0, restored.pianoRollPatterns.size)
        assertFalse(restored.tracks.isEmpty())
    }
}
