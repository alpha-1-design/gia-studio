package com.giastudio.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A detected pitch with a 0..1 clarity score (low = unreliable). */
data class PitchResult(val frequency: Double, val clarity: Double)

/**
 * Rolling autocorrelation pitch detector tuned for voice + instruments in the
 * 60 Hz – 1.4 kHz range. Uses the last N samples for each detection.
 */
class PitchDetector(
    private val sampleRate: Int,
    private val minHz: Double = 60.0,
    private val maxHz: Double = 1400.0,
) {
    companion object {
        private const val WINDOW = 2048
        private const val MIN_CLARITY = 0.30
    }

    private val ring = FloatArray(WINDOW * 2)
    private var write = 0
    private val work = FloatArray(WINDOW)

    /** Feed new samples; returns a result when a fresh detection is ready. */
    fun push(samples: FloatArray, count: Int): PitchResult? {
        for (i in 0 until count) {
            ring[write] = samples[i]
            write = (write + 1) % ring.size
        }
        if (count <= 0) return null
        return detect()
    }

    private fun detect(): PitchResult? {
        // Rebuild a contiguous window of the last WINDOW samples.
        var idx = write - WINDOW
        if (idx < 0) idx += ring.size
        var energy = 0.0
        for (i in 0 until WINDOW) {
            val v = ring[(idx + i) % ring.size]
            work[i] = v
            energy += v * v
        }
        val rms = sqrt(energy / WINDOW)
        if (rms < 2e-3) return null // too quiet to trust

        val minLag = (sampleRate / maxHz).toInt().coerceAtLeast(4)
        val maxLag = (sampleRate / minHz).toInt().coerceAtMost(WINDOW / 2)

        var bestLag = -1
        var bestScore = 0.0
        var norm = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until WINDOW - lag) {
                sum += work[i].toDouble() * work[i + lag]
            }
            // Normalize by the energy of the window slice to keep scores
            // comparable across lags.
            var sliceEnergy = 0.0
            for (i in 0 until WINDOW - lag) {
                val v = work[i]
                sliceEnergy += v * v
            }
            val e = sum / (sliceEnergy + 1e-12)
            if (e > bestScore) {
                bestScore = e
                bestLag = lag
            }
        }
        norm = bestScore
        if (bestLag <= 0 || norm < MIN_CLARITY) return null

        // Refine around the coarse peak for sub-sample accuracy.
        val lagD = refine(bestLag)
        val freq = sampleRate / lagD
        return PitchResult(freq, norm)
    }

    private fun refine(center: Int): Double {
        val x0 = work[0].toDouble()
        var best = center.toDouble()
        var bestCorr = corr(center)
        for (delta in intArrayOf(-1, 1)) {
            val lag = center + delta
            if (lag <= 0) continue
            val c = corr(lag)
            if (c > bestCorr) {
                bestCorr = c
                best = lag.toDouble()
            }
        }
        if (best == center.toDouble()) {
            // parabolic interpolation around the integer peak
            val c0 = corr(center - 1)
            val c1 = corr(center)
            val c2 = corr(center + 1)
            val denom = (c0 - 2.0 * c1 + c2)
            if (abs(denom) > 1e-12) {
                val shift = 0.5 * (c0 - c2) / denom
                best = center + shift.coerceIn(-1.0, 1.0)
            }
        }
        return best
    }

    private fun corr(lag: Int): Double {
        if (lag < 1 || lag >= WINDOW) return 0.0
        var sum = 0.0
        for (i in 0 until WINDOW - lag) {
            sum += work[i].toDouble() * work[i + lag]
        }
        var sliceEnergy = 0.0
        for (i in 0 until WINDOW - lag) {
            val v = work[i].toDouble()
            sliceEnergy += v * v
        }
        return sum / (sliceEnergy + 1e-12)
    }

    fun reset() {
        ring.fill(0f)
        write = 0
    }
}

/** A440-based note utilities for the tuner UI. */
object Notes {
    private const val A4 = 440.0
    private const val A4_MIDI = 69
    private val SHARPS = listOf(
        "C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"
    )

    fun midiFor(freq: Double): Int =
        (A4_MIDI + 12.0 * (ln(freq / A4) / ln(2.0))).roundToInt()

    fun freqOfMidi(midi: Int): Double = A4 * Math.pow(2.0, (midi - A4_MIDI) / 12.0)

    fun nameOf(midi: Int): String {
        val octave = (midi / 12) - 1
        return SHARPS[midi % 12] + octave
    }

    /** Detuned cents from the nearest equal-tempered note, in -50..+50. */
    fun centsOff(freq: Double): Int {
        val midi = A4_MIDI + 12.0 * (ln(freq / A4) / ln(2.0))
        val nearest = midi.roundToInt()
        val target = freqOfMidi(nearest)
        return (1200.0 * ln(freq / target) / ln(2.0)).roundToInt()
    }

    /** Frequency in Hz of the nearest note (for cent-meter scaling). */
    fun nearestFreq(freq: Double): Double {
        val nearest = midiFor(freq)
        return freqOfMidi(nearest)
    }
}
