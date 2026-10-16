package com.giastudio.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Small, dependency-free DSP building blocks used by both the real-time
 * engine and the offline mixdown renderer (so exports sound identical to
 * playback). All processors are stateful and mono.
 */

// ---------------------------------------------------------------------------
// Biquad (RBJ cookbook) — gentle low/high-pass "tone shaping" filters
// ---------------------------------------------------------------------------

class BiquadFilter(
    kind: Kind,
    freqHz: Double,
    private val sampleRate: Int,
    private var q: Double = 0.7071,
) {
    enum class Kind { LOWPASS, HIGHPASS }

    private var normB0 = 0.0
    private var normB1 = 0.0
    private var normB2 = 0.0
    private var normA1 = 0.0
    private var normA2 = 0.0

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    private var lastKind = kind
    private var lastFreq = freqHz

    init {
        design()
    }

    private fun design() {
        val f = lastFreq.coerceIn(20.0, sampleRate * 0.45)
        val w0 = 2.0 * PI * f / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        var b0: Double
        var b1: Double
        var b2: Double
        val a0: Double
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha
        when (lastKind) {
            Kind.LOWPASS -> {
                b0 = (1.0 - cosW) / 2.0
                b1 = 1.0 - cosW
                b2 = (1.0 - cosW) / 2.0
                a0 = 1.0 + alpha
            }
            Kind.HIGHPASS -> {
                b0 = (1.0 + cosW) / 2.0
                b1 = -(1.0 + cosW)
                b2 = (1.0 + cosW) / 2.0
                a0 = 1.0 + alpha
            }
        }
        val k = 1.0 / a0
        normB0 = b0 * k
        normB1 = b1 * k
        normB2 = b2 * k
        normA1 = a1 * k
        normA2 = a2 * k
    }

    /** Update cutoff/type and clear history (used when params change live). */
    fun reconfigure(kind: Kind = lastKind, freqHz: Double = lastFreq, reset: Boolean = false) {
        lastKind = kind
        lastFreq = freqHz
        design()
        if (reset) reset()
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    fun process(x: Double): Double {
        val y = normB0 * x + normB1 * x1 + normB2 * x2 - normA1 * y1 - normA2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

// ---------------------------------------------------------------------------
// Noise gate — used on mic input while recording to silence quiet hiss/room
// tone between phrases. Smooth attack/release so it never clicks.
// ---------------------------------------------------------------------------

class NoiseGate(
    sampleRate: Int,
    var thresholdDb: Double = -46.0,
    private val attackSec: Double = 0.004,
    private val releaseSec: Double = 0.14,
) {
    private val attackCoef = if (attackSec <= 0.0) 1.0 else exp(-1.0 / (sampleRate * attackSec))
    private val releaseCoef = if (releaseSec <= 0.0) 0.0 else exp(-1.0 / (sampleRate * releaseSec))
    private var envelope = 0.0
    private var gate = 0.0
    private var hysteresis = 1.0

    fun reset() {
        envelope = 0.0; gate = 0.0; hysteresis = 1.0
    }

    fun processBlock(input: FloatArray, output: FloatArray, n: Int, sampleRate: Int, thresholdDbOverride: Double? = null) {
        val thr = thresholdDbOverride ?: thresholdDb
        // Convert dB threshold to a linear amplitude reference and give the
        // follower headroom above it.
        val openLevel = pow10(thr / 20.0) * 3.0
        val closeLevel = openLevel * 0.5
        var env = envelope
        var g = gate
        var hys = hysteresis
        for (i in 0 until n) {
            val v = abs(input[i].toDouble())
            env = if (v > env) v + (env - v) * attackCoef else v + (env - v) * releaseCoef
            if (env > openLevel) hys = 1.0
            else if (env < closeLevel) hys = 0.0
            g = if (hys > 0.5) {
                if (g < 1.0) g + (1.0 - g) * 0.15 else 1.0
            } else {
                g * 0.9992
            }
            output[i] = (input[i] * g.toFloat())
        }
        envelope = env; gate = g; hysteresis = hys
    }

    private fun pow10(x: Double): Double = exp(x * 2.302585092994046)
}

// ---------------------------------------------------------------------------
// Drive — asymmetric-ish tanh saturation for warmth ("GIA Drive")
// ---------------------------------------------------------------------------

object Drive {
    /** amount 0..1 maps to input gain 1x..16x before tanh. */
    fun processBlock(input: FloatArray, out: FloatArray, n: Int, amount: Float) {
        val gain = 1.0 + amount * 15.0
        for (i in 0 until n) {
            out[i] = tanh(input[i].toDouble() * gain).toFloat()
        }
    }
}

// ---------------------------------------------------------------------------
// Delay line — single tap, feedback, wet/dry mix
// ---------------------------------------------------------------------------

class DelayLine(sampleRate: Int, maxSeconds: Double = 2.5) {
    private val buffer = DoubleArray((sampleRate * maxSeconds).toInt() + 8)
    private val size = buffer.size
    private var head = 0

    fun reset() {
        buffer.fill(0.0)
        head = 0
    }

    fun process(x: Double, delaySamples: Int, feedback: Double, mix: Double): Double {
        if (delaySamples < 2) return x
        val readIndex = (head - delaySamples + size) % size
        val wet = buffer[readIndex]
        buffer[head] = x + wet * feedback.coerceIn(0.0, 0.92)
        head = (head + 1) % size
        return x * (1.0 - mix.coerceIn(0.0, 1.0)) + wet * mix.coerceIn(0.0, 1.0)
    }
}

// ---------------------------------------------------------------------------
// Reverb — Schroeder/Moorer: 4 parallel combs + 4 series allpasses.
// Freeverb-derived structure, public-domain style algorithm.
// ---------------------------------------------------------------------------

class Reverb(sampleRate: Int) {
    private val ratio = sampleRate / 44100.0
    private val combLen = intArrayOf(1116, 1188, 1277, 1356)
    private val allpassLen = intArrayOf(556, 441, 341, 225)
    private val combs = Array(combLen.size) { Comb(sampleRate, combLen[it]) }
    private val allpasses = Array(allpassLen.size) { Allpass(sampleRate, allpassLen[it]) }
    private var wet = 1.0
    private var dry = 1.0

    init {
        val w = 0.22 * ratio
        wet = w / (0.22 + 0.84) * 2.2
        dry = 1.0
    }

    private class Comb(sr: Int, lenSamples: Int) {
        private val buf = DoubleArray((lenSamples * sr / 44100.0).toInt().coerceAtLeast(64))
        private var idx = 0
        private var lp = 0.0
        private val feedback = 0.84
        private val damping = 0.2

        fun process(x: Double): Double {
            val out = buf[idx]
            lp = out * (1.0 - damping) + lp * damping
            buf[idx] = x + lp * feedback
            idx = (idx + 1) % buf.size
            return out
        }

        fun reset() {
            buf.fill(0.0); lp = 0.0; idx = 0
        }
    }

    private class Allpass(sr: Int, lenSamples: Int) {
        private val buf = DoubleArray((lenSamples * sr / 44100.0).toInt().coerceAtLeast(16))
        private var idx = 0

        fun process(x: Double): Double {
            val bufout = buf[idx]
            val output = -x + bufout
            buf[idx] = x + bufout * 0.5
            idx = (idx + 1) % buf.size
            return output
        }

        fun reset() {
            buf.fill(0.0); idx = 0
        }
    }

    fun reset() {
        combs.forEach { it.reset() }
        allpasses.forEach { it.reset() }
    }

    /** x = dry input. Returns the wet reverb signal; caller blends. */
    fun process(x: Double): Double {
        var out = 0.0
        for (c in combs) out += c.process(x)
        for (a in allpasses) out = a.process(out)
        return out * wet * 1.7
    }
}

// ---------------------------------------------------------------------------
// Peak limiter (master bus) — catches overs, gently
// ---------------------------------------------------------------------------

class PeakLimiter(sampleRate: Int) {
    private val attackCoef = exp(-1.0 / (sampleRate * 0.0012))
    private val releaseCoef = exp(-1.0 / (sampleRate * 0.12))
    private var peak = 0.0
    private var gain = 1.0

    fun reset() {
        peak = 0.0
        gain = 1.0
    }

    fun process(x: Double): Double {
        val a = abs(x)
        peak = if (a > peak) a + (peak - a) * attackCoef else a + (peak - a) * releaseCoef
        val target = if (peak > 1.0) 1.0 / peak else 1.0
        gain = if (target < gain) {
            target + (gain - target) * attackCoef
        } else {
            target + (gain - target) * releaseCoef
        }
        val y = x * gain
        return y.coerceIn(-1.0, 1.0)
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Equal-power pan: pan -1 = hard left, 0 = centre, +1 = hard right. */
fun panGains(pan: Float): Pair<Double, Double> {
    val p = pan.coerceIn(-1f, 1f).toDouble()
    val angle = (p + 1.0) * 0.25 * PI
    return Pair(cos(angle), sin(angle))
}

fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

// ---------------------------------------------------------------------------
// Compressor — soft-knee feed-forward, musical defaults for vocals / drums
// ---------------------------------------------------------------------------

class Compressor(sampleRate: Int) {
    private val attackCoef = exp(-1.0 / (sampleRate * 0.008))
    private val releaseCoef = exp(-1.0 / (sampleRate * 0.18))
    private var envelope = 0.0
    private var gain = 1.0

    /** thresholdDb typically -24..-6, ratio 1.5..8, makeup 0..12 dB */
    fun process(x: Double, thresholdDb: Double, ratio: Double, makeupDb: Double): Double {
        val a = abs(x)
        envelope = if (a > envelope) {
            a + (envelope - a) * attackCoef
        } else {
            a + (envelope - a) * releaseCoef
        }
        val envDb = if (envelope > 1e-8) 20.0 * kotlin.math.log10(envelope) else -120.0
        val over = envDb - thresholdDb
        val grDb = if (over <= 0.0) 0.0 else {
            // Soft knee ~6 dB
            val knee = 6.0
            if (over < knee) {
                val xk = over / knee
                over * xk * 0.5 * (1.0 - 1.0 / ratio.coerceAtLeast(1.0))
            } else {
                over * (1.0 - 1.0 / ratio.coerceAtLeast(1.0))
            }
        }
        val targetGain = exp((-grDb + makeupDb) * 0.11512925) // 20*log10 inverse
        gain = if (targetGain < gain) {
            targetGain + (gain - targetGain) * attackCoef
        } else {
            targetGain + (gain - targetGain) * releaseCoef
        }
        return x * gain
    }

    fun reset() {
        envelope = 0.0
        gain = 1.0
    }
}

// ---------------------------------------------------------------------------
// Peaking EQ (RBJ cookbook) — one musical mid band + optional shelf helpers
// ---------------------------------------------------------------------------

class PeakingEq(
    private val sampleRate: Int,
    freqHz: Double = 1000.0,
    private var q: Double = 1.0,
    private var gainDb: Double = 0.0,
) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var lastFreq = freqHz

    init {
        design()
    }

    fun reconfigure(freqHz: Double = lastFreq, q: Double = this.q, gainDb: Double = this.gainDb) {
        lastFreq = freqHz
        this.q = q
        this.gainDb = gainDb
        design()
    }

    private fun design() {
        val f = lastFreq.coerceIn(40.0, sampleRate * 0.45)
        val A = exp(gainDb * 0.057762265) // 10^(dB/40)
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q.coerceIn(0.3, 8.0))
        val cosW = cos(w0)
        val b0r = 1.0 + alpha * A
        val b1r = -2.0 * cosW
        val b2r = 1.0 - alpha * A
        val a0r = 1.0 + alpha / A
        val a1r = -2.0 * cosW
        val a2r = 1.0 - alpha / A
        val k = 1.0 / a0r
        b0 = b0r * k
        b1 = b1r * k
        b2 = b2r * k
        a1 = a1r * k
        a2 = a2r * k
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
}


// ---------------------------------------------------------------------------
// Chorus — 3 modulated delay lines for width / lushness
// ---------------------------------------------------------------------------

class Chorus(sampleRate: Int) {
    private val sr = sampleRate.toDouble()
    private val maxDelay = (sampleRate * 0.04).toInt() + 8 // up to 40 ms
    private val buf = DoubleArray(maxDelay)
    private var write = 0
    private var phase = 0.0

    fun reset() {
        buf.fill(0.0)
        write = 0
        phase = 0.0
    }

    /**
     * @param rateHz LFO rate (~0.2–2 Hz)
     * @param depthMs modulation depth in ms
     * @param mix wet amount 0..1
     */
    fun process(x: Double, rateHz: Double, depthMs: Double, mix: Double): Double {
        buf[write] = x
        val baseDelay = 0.012 * sr // 12 ms centre
        val depth = depthMs.coerceIn(0.5, 12.0) * 0.001 * sr
        phase += 2.0 * PI * rateHz.coerceIn(0.05, 4.0) / sr
        if (phase > 2.0 * PI) phase -= 2.0 * PI

        fun tap(offset: Double): Double {
            val d = (baseDelay + offset).coerceIn(1.0, (maxDelay - 2).toDouble())
            val readPos = write - d
            val i0 = Math.floor(readPos).toInt()
            val frac = readPos - i0
            val a = buf[((i0 % maxDelay) + maxDelay) % maxDelay]
            val b = buf[(((i0 + 1) % maxDelay) + maxDelay) % maxDelay]
            return a + (b - a) * frac
        }

        val wet = (
            tap(depth * sin(phase)) +
                tap(depth * sin(phase + 2.094)) + // +120°
                tap(depth * sin(phase + 4.189))   // +240°
            ) / 3.0
        write = (write + 1) % maxDelay
        val m = mix.coerceIn(0.0, 1.0)
        return x * (1.0 - m) + wet * m
    }
}
