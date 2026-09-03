package com.giastudio.app.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Offline "Clean Up" tools for recorded clips. Each function is pure:
 * it takes a mono float buffer and returns a new one, so the UI can preview
 * results trivially.
 */

object AudioCleanup {

    /** Remove DC offset + sub-30 Hz rumble with a one-pole DC blocker. */
    fun removeDc(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        val r = 0.9995
        var prevX = 0.0
        var prevY = 0.0
        for (i in input.indices) {
            val x = input[i].toDouble()
            val y = x - prevX + r * prevY
            prevX = x
            prevY = y
            out[i] = y.toFloat()
        }
        return out
    }

    /** Peak normalize to -0.5 dBFS (0.944). */
    fun normalizePeak(input: FloatArray): FloatArray {
        var peak = 0.0
        for (v in input) {
            val a = abs(v.toDouble())
            if (a > peak) peak = a
        }
        if (peak < 1e-6) return input.copyOf()
        val gain = (0.944 / peak).coerceAtMost(16.0)
        val out = FloatArray(input.size)
        for (i in input.indices) out[i] = (input[i] * gain).toFloat()
        return out
    }

    /**
     * Adaptive noise gate: estimates the noise floor from the quietest RMS
     * windows and silences anything below it with smooth edges.
     */
    fun noiseGate(input: FloatArray, sampleRate: Int): FloatArray {
        val out = FloatArray(input.size)
        val window = sampleRate / 40 // 25 ms
        if (input.size < window * 2) {
            input.copyInto(out)
            return out
        }
        // Window RMS levels
        val levels = ArrayList<Double>((input.size + window - 1) / window)
        var i = 0
        while (i < input.size) {
            val n = minOf(window, input.size - i)
            var sum = 0.0
            for (j in 0 until n) {
                val v = input[i + j].toDouble()
                sum += v * v
            }
            levels.add(sqrt(sum / n))
            i += n
        }
        levels.sort()
        val noiseFloor = levels[max(0, levels.size / 6)] * 1.6
        val threshold = max(noiseFloor * 3.0, 1e-4)

        // Envelope follower
        val attackCoef = exp(-1.0 / (sampleRate * 0.002))
        val releaseCoef = exp(-1.0 / (sampleRate * 0.10))
        var env = 0.0
        var gate = 0.0
        for (n in 0 until input.size) {
            val v = abs(input[n].toDouble())
            env = if (v > env) v + (env - v) * attackCoef else v + (env - v) * releaseCoef
            val target = if (env > threshold) 1.0 else 0.0
            gate = if (target > gate) {
                gate + (target - gate) * 0.35
            } else {
                gate + (target - gate) * 0.0008
            }
            out[n] = (input[n] * gate).toFloat()
        }
        return out
    }

    /**
     * Trim silence at the start and end. Returns the trimmed buffer; pads a
     * small attack/release so the edit never clicks.
     */
    fun trimSilence(input: FloatArray, sampleRate: Int): FloatArray {
        val window = sampleRate / 40
        if (input.size < window * 2) return input.copyOf()
        val levels = DoubleArray(input.size / window + 1)
        for (wi in levels.indices) {
            val base = wi * window
            val n = minOf(window, input.size - base)
            var sum = 0.0
            for (j in 0 until n) {
                val v = input[base + j].toDouble()
                sum += v * v
            }
            levels[wi] = sqrt(sum / n)
        }
        val sorted = levels.sorted()
        val floor = sorted[max(0, sorted.size / 6)] * 1.6
        val threshold = max(floor * 3.0, 1e-4)
        var first = levels.indexOfFirst { it > threshold }
        var last = levels.indexOfLast { it > threshold }
        if (first < 0 || last < 0) return FloatArray(0)
        val pad = (sampleRate * 0.02).toInt() / window + 1
        first = max(0, first - pad)
        last = minOf(levels.lastIndex, last + pad)
        val from = first * window
        val to = minOf(input.size, (last + 1) * window)
        if (to <= from) return FloatArray(0)
        return input.copyOfRange(from, to)
    }

    /** Peak level in dBFS (0 dB = full scale). */
    fun peakDb(input: FloatArray): Double {
        var peak = 0.0
        for (v in input) peak = max(peak, abs(v.toDouble()))
        return if (peak < 1e-9) -120.0 else 20.0 * kotlin.math.log10(peak)
    }

    fun durationText(seconds: Double): String {
        val total = seconds.toInt()
        val m = total / 60
        val s = total % 60
        return "${m}:${s.toString().padStart(2, '0')}.${((seconds - total) * 10).toInt()}"
    }
}
