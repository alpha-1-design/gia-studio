package com.giastudio.app.music

import com.giastudio.app.audio.Notes
import kotlin.math.roundToInt

const val STEPS_PER_BAR = 16
const val MAX_PATTERN_BARS = 8

/**
 * A 16th-note step grid used by the Create tab.
 *
 * Each cell stores a velocity in 0..1 (0 = off). Legacy boolean grids are
 * still readable via [get]/[set]; the richer API is [getVel]/[setVel].
 */
class StepPattern(val rowCount: Int, barCount: Int) {
    init {
        require(rowCount in 1..16) { "1..16 rows allowed" }
    }

    var barCount: Int = barCount.coerceIn(1, MAX_PATTERN_BARS)

    /** Velocity 0..1 per cell. 0 = empty. */
    private val vels = FloatArray(rowCount * STEPS_PER_BAR * MAX_PATTERN_BARS)

    fun index(row: Int, bar: Int, step: Int): Int =
        row * (STEPS_PER_BAR * MAX_PATTERN_BARS) + bar * STEPS_PER_BAR + step

    fun get(row: Int, bar: Int, step: Int): Boolean = vels[index(row, bar, step)] > 0.01f

    fun set(row: Int, bar: Int, step: Int, on: Boolean) {
        vels[index(row, bar, step)] = if (on) 0.92f else 0f
    }

    fun getVel(row: Int, bar: Int, step: Int): Float = vels[index(row, bar, step)]

    fun setVel(row: Int, bar: Int, step: Int, vel: Float) {
        vels[index(row, bar, step)] = vel.coerceIn(0f, 1f)
    }

    /** Cycle velocity: off → full → medium → soft → off. Great for finger painting. */
    fun cycleVel(row: Int, bar: Int, step: Int) {
        val i = index(row, bar, step)
        val cur = vels[i]
        vels[i] = when {
            cur < 0.05f -> 0.95f
            cur >= 0.85f -> 0.62f
            cur >= 0.45f -> 0.32f
            else -> 0f
        }
    }

    fun anyHit(): Boolean = vels.any { it > 0.01f }

    fun hitCount(): Int = vels.count { it > 0.01f }

    fun clear() {
        vels.fill(0f)
    }
}

/** A single sound placed at an exact time inside a rendered stem. */
sealed class MusicEvent(open val timeSec: Double) {
    data class Drum(val sound: DrumSound, override val timeSec: Double, val vel: Float) : MusicEvent(timeSec)
    data class Note(
        val sound: SynthSound,
        val midi: Int,
        override val timeSec: Double,
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
     * Swing amount 0..1. At 0.5 the off-beats are delayed by ~33% of a step
     * (classic "shuffle" feel). Applied only to odd 16th notes.
     */
    fun swungTime(stepIndex: Int, stepDur: Double, swing: Float): Double {
        val base = stepIndex * stepDur
        if (swing <= 0.001f || stepIndex % 2 == 0) return base
        // Max delay = 2/3 of a step at swing=1 (extreme), musical sweet spot ~0.5
        return base + stepDur * (2.0 / 3.0) * swing.coerceIn(0f, 1f).toDouble()
    }

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

    /**
     * Expand a drum StepPattern into time-placed events.
     * @param swing 0..1 groove amount applied to off-beats
     * @param humanize 0..1 random timing + velocity jitter for a live feel
     */
    fun drumEvents(
        sounds: List<DrumSound>,
        pattern: StepPattern,
        bpm: Int,
        bars: Int = pattern.barCount,
        defaultVel: Float = 0.92f,
        swing: Float = 0f,
        humanize: Float = 0f,
    ): List<MusicEvent> {
        val sd = stepDurSec(bpm)
        val events = ArrayList<MusicEvent>()
        val rowCount = minOf(sounds.size, pattern.rowCount)
        val nBars = bars.coerceIn(1, MAX_PATTERN_BARS)
        val rnd = java.util.Random(42)
        for (row in 0 until rowCount) {
            for (bar in 0 until nBars) {
                for (step in 0 until STEPS_PER_BAR) {
                    val cellVel = pattern.getVel(row, bar, step)
                    if (cellVel <= 0.01f) continue
                    val stepIdx = bar * STEPS_PER_BAR + step
                    var time = swungTime(stepIdx, sd, swing)
                    var vel = cellVel.coerceIn(0.05f, 1f)
                    if (humanize > 0.001f) {
                        val j = humanize.coerceIn(0f, 1f)
                        time += (rnd.nextDouble() - 0.5) * sd * 0.18 * j
                        vel = (vel + (rnd.nextFloat() - 0.5f) * 0.22f * j).coerceIn(0.08f, 1f)
                    }
                    // Kick/snare slightly stronger by default when full velocity
                    if (sounds[row] == DrumSound.KICK || sounds[row] == DrumSound.SNARE) {
                        if (vel > 0.85f) vel = (vel * 1.05f).coerceAtMost(1f)
                    }
                    events.add(MusicEvent.Drum(sounds[row], time.coerceAtLeast(0.0), vel))
                }
            }
        }
        return events
    }

    /**
     * Expand a melodic StepPattern (one pitch per row) into events.
     */
    fun noteEvents(
        sound: SynthSound,
        midis: List<Int>,
        pattern: StepPattern,
        bpm: Int,
        bars: Int = pattern.barCount,
        defaultVel: Float = 0.9f,
        swing: Float = 0f,
        humanize: Float = 0f,
        gate: Float = 0.95f,
    ): List<MusicEvent> {
        val sd = stepDurSec(bpm)
        val events = ArrayList<MusicEvent>()
        val rowCount = minOf(midis.size, pattern.rowCount)
        val nBars = bars.coerceIn(1, MAX_PATTERN_BARS)
        val rnd = java.util.Random(43)
        for (row in 0 until rowCount) {
            for (bar in 0 until nBars) {
                for (step in 0 until STEPS_PER_BAR) {
                    val cellVel = pattern.getVel(row, bar, step)
                    if (cellVel <= 0.01f) continue
                    val stepIdx = bar * STEPS_PER_BAR + step
                    var time = swungTime(stepIdx, sd, swing)
                    var vel = cellVel.coerceIn(0.05f, 1f)
                    if (humanize > 0.001f) {
                        val j = humanize.coerceIn(0f, 1f)
                        time += (rnd.nextDouble() - 0.5) * sd * 0.15 * j
                        vel = (vel + (rnd.nextFloat() - 0.5f) * 0.18f * j).coerceIn(0.08f, 1f)
                    }
                    events.add(
                        MusicEvent.Note(
                            sound = sound,
                            midi = midis[row],
                            timeSec = time.coerceAtLeast(0.0),
                            durSec = sd * gate.coerceIn(0.15f, 2.5f).toDouble(),
                            vel = vel,
                        )
                    )
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
    val DORIAN = intArrayOf(0, 2, 3, 5, 7, 9, 10)
    val MIXOLYDIAN = intArrayOf(0, 2, 4, 5, 7, 9, 10)

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
