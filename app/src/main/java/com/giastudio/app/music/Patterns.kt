package com.giastudio.app.music

import com.giastudio.app.audio.Notes

const val STEPS_PER_BAR = 16
const val MAX_PATTERN_BARS = 8

/**
 * A 16th-note step grid used by the Create tab. One pattern is a set of rows
 * (each row is a drum or a melodic pitch) across up to [MAX_PATTERN_BARS]
 * bars. Cells are plain on/off hits — the arrangement of rows is up to the UI.
 */
class StepPattern(val rowCount: Int, barCount: Int) {
    init {
        require(rowCount in 1..16) { "1..16 rows allowed" }
    }

    var barCount: Int = barCount.coerceIn(1, MAX_PATTERN_BARS)

    private val hits = BooleanArray(rowCount * STEPS_PER_BAR * MAX_PATTERN_BARS)

    fun index(row: Int, bar: Int, step: Int): Int =
        row * (STEPS_PER_BAR * MAX_PATTERN_BARS) + bar * STEPS_PER_BAR + step

    fun get(row: Int, bar: Int, step: Int): Boolean = hits[index(row, bar, step)]

    fun set(row: Int, bar: Int, step: Int, on: Boolean) {
        hits[index(row, bar, step)] = on
    }

    fun anyHit(): Boolean = hits.any { it }

    fun hitCount(): Int = hits.count { it }

    fun clear() {
        hits.fill(false)
    }
}

/** A single sound placed at an exact time inside a rendered stem. */
sealed class MusicEvent(val timeSec: Double) {
    data class Drum(val sound: DrumSound, timeSec: Double, val vel: Float) : MusicEvent(timeSec)
    data class Note(
        val sound: SynthSound,
        val midi: Int,
        timeSec: Double,
        val durSec: Double,
        val vel: Float,
    ) : MusicEvent(timeSec)
}

/**
 * One layer to render into the arrangement. `startSec == null` means "place
 * per the lane rules in StudioController"; the demo uses explicit times.
 */
class StemSpec(
    val kindName: String,
    val trackName: String,
    val label: String,
    val color: Int,
    val volume: Float,
    val pan: Float,
    val bpm: Int,
    val events: List<MusicEvent>,
    val startSec: Double? = null,
)

/** Turns events into a single mono mixdown at the engine's sample rate. */
object SongRender {

    /** One 16th-note at [bpm] in seconds. */
    fun stepDurSec(bpm: Int): Double = 60.0 / bpm.coerceIn(30, 300) / 4.0

    /**
     * Mix [spec.events] into one float buffer. Length covers every event plus
     * its natural tail, plus a small silence pad so clips never cut off tails.
     */
    fun renderStem(spec: StemSpec, sampleRate: Int): FloatArray {
        var endSec = 0.25
        for (e in spec.events) {
            val len = when (e) {
                is MusicEvent.Drum -> e.sound.lenSec()
                is MusicEvent.Note -> (e.durSec + e.sound.tailSec).coerceAtMost(10.0)
            }
            val end = e.timeSec + len
            if (end > endSec) endSec = end
        }
        val n = (endSec * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        for (e in spec.events) {
            val buf = when (e) {
                is MusicEvent.Drum -> Synth.renderDrum(e.sound, sampleRate, e.vel)
                is MusicEvent.Note -> Synth.renderNote(e.sound, e.midi, sampleRate, e.durSec, e.vel)
            }
            val off = (e.timeSec * sampleRate).toInt()
            if (off >= n) continue
            val take = minOf(buf.size, n - off)
            for (i in 0 until take) {
                out[off + i] += buf[i]
            }
        }
        // One consistent ceiling per stem keeps stacking layers clean.
        var peak = 0.0
        for (v in out) {
            val a = kotlin.math.abs(v.toDouble())
            if (a > peak) peak = a
        }
        if (peak > 1e-4 && peak > 1.0) {
            val g = (0.97 / peak).toFloat()
            for (i in out.indices) out[i] = out[i] * g
        }
        return out
    }

    /** Expand a drum StepPattern into time-placed events for [bars] bars. */
    fun drumEvents(
        sounds: List<DrumSound>,
        pattern: StepPattern,
        bpm: Int,
        bars: Int = pattern.barCount,
        vel: Float = 0.92f,
    ): List<MusicEvent> {
        val sd = stepDurSec(bpm)
        val events = ArrayList<MusicEvent>()
        val rowCount = minOf(sounds.size, pattern.rowCount)
        val nBars = bars.coerceIn(1, MAX_PATTERN_BARS)
        for (row in 0 until rowCount) {
            for (bar in 0 until nBars) {
                for (step in 0 until STEPS_PER_BAR) {
                    if (pattern.get(row, bar, step)) {
                        val time = ((bar * STEPS_PER_BAR + step).toDouble()) * sd
                        events.add(MusicEvent.Drum(sounds[row], time, vel))
                    }
                }
            }
        }
        return events
    }

    /** Expand a melodic StepPattern (one pitch per row) into events for [bars] bars. */
    fun noteEvents(
        sound: SynthSound,
        midis: List<Int>,
        pattern: StepPattern,
        bpm: Int,
        bars: Int = pattern.barCount,
        vel: Float = 0.9f,
    ): List<MusicEvent> {
        val sd = stepDurSec(bpm)
        val events = ArrayList<MusicEvent>()
        val rowCount = minOf(midis.size, pattern.rowCount)
        val nBars = bars.coerceIn(1, MAX_PATTERN_BARS)
        for (row in 0 until rowCount) {
            for (bar in 0 until nBars) {
                for (step in 0 until STEPS_PER_BAR) {
                    if (pattern.get(row, bar, step)) {
                        val time = ((bar * STEPS_PER_BAR + step).toDouble()) * sd
                        events.add(
                            MusicEvent.Note(
                                sound = sound,
                                midi = midis[row],
                                timeSec = time,
                                durSec = sd * 0.95,
                                vel = vel,
                            )
                        )
                    }
                }
            }
        }
        return events
    }
}

/**
 * Simple theory helpers for the melody grid: scale spellings and note rows.
 * Everything is relative to a chosen root note so beginners never hit a wrong
 * note — the grid only ever shows notes inside the selected scale.
 */
object MusicTheory {
    val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val MINOR = intArrayOf(0, 2, 3, 5, 7, 8, 10)
    val PENTATONIC = intArrayOf(0, 2, 4, 7, 9)

    private val LETTERS = listOf("C", "D", "E", "F", "G", "A", "B")

    /** Semitone offset of a root letter from C. */
    fun letterSemitone(letterIndex: Int): Int = intArrayOf(0, 2, 4, 5, 7, 9, 11)[letterIndex.coerceIn(0, 6)]

    fun letterName(letterIndex: Int): String = LETTERS[letterIndex.coerceIn(0, 6)]

    /**
     * The note rows (low → high) for two octaves of [scale] starting at the
     * given root [startMidi].
     */
    fun noteRows(startMidi: Int, scale: IntArray): List<Int> {
        val rows = ArrayList<Int>(scale.size * 2)
        for (oct in 0 until 2) {
            for (d in scale) {
                rows.add(startMidi + oct * 12 + d)
            }
        }
        return rows
    }

    fun label(midi: Int): String = Notes.nameOf(midi)
}
