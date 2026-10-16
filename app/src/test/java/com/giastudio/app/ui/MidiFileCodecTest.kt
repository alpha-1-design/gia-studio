package com.giastudio.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiFileCodecTest {
    @Test
    fun exportedPatternCanBeImportedAgain() {
        val source = listOf(
            RollNote(midi = 60, step = 0, length = 4, velocity = 0.75f),
            RollNote(midi = 67, step = 6, length = 2, velocity = 0.55f),
        )

        val bytes = encodeStandardMidi(source, bpm = 120)
        val decoded = decodeStandardMidi(bytes)

        assertEquals(120, decoded.bpm)
        assertEquals(2, decoded.notes.size)

        val c4 = decoded.notes.single { it.midi == 60 }
        assertEquals(0, c4.step)
        assertEquals(4, c4.length)
        assertTrue(kotlin.math.abs(c4.velocity - 0.75f) < 0.02f)

        val g4 = decoded.notes.single { it.midi == 67 }
        assertEquals(6, g4.step)
        assertEquals(2, g4.length)
        assertTrue(kotlin.math.abs(g4.velocity - 0.55f) < 0.02f)
    }

    @Test
    fun malformedMidiIsRejected() {
        val result = runCatching { decodeStandardMidi(byteArrayOf(1, 2, 3, 4)) }
        assertTrue(result.isFailure)
    }
}
