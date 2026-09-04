package com.giastudio.app.music

import com.giastudio.app.audio.BiquadFilter
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * GIA Studio's built-in instrument bank.
 *
 * Every sound is synthesized from scratch in pure Kotlin — no sample packs,
 * no assets, nothing to download. Renders are deterministic (fixed noise
 * seeds) so the same pattern always produces the same audio. All buffers are
 * mono floats in [-1, 1] at whatever sample rate is requested, so stems drop
 * straight into the normal clip/recordings pipeline.
 */

/** A single percussive one-shot that can be sequenced. */
enum class DrumSound(val label: String) {
    KICK("Kick"),
    SNARE("Snare"),
    CLAP("Clap"),
    HAT("Closed Hat"),
    OPEN_HAT("Open Hat"),
    TOM_LO("Low Tom"),
    TOM_HI("High Tom"),
    RIM("Rim"),
    ;

    /** Full rendered length in seconds (sound decays to ~silence inside). */
    fun lenSec(): Double = when (this) {
        KICK -> 0.55
        SNARE -> 0.35
        CLAP -> 0.40
        HAT -> 0.13
        OPEN_HAT -> 0.50
        TOM_LO -> 0.55
        TOM_HI -> 0.55
        RIM -> 0.16
    }
}

/** A pitched playable voice. `tailSec` is the natural release added after the held note. */
enum class SynthSound(val label: String, val tailSec: Double) {
    EPIANO("E-Piano", 1.5),
    PLUCK("Pluck", 0.8),
    LEAD("Lead", 0.45),
    PAD("Pad", 1.1),
    BASS("Bass", 0.18),
}

object Synth {

    private const val PI2 = 2.0 * PI

    fun freqOf(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** Render one drum hit; peak is scaled to about `0.9 * vel`. */
    fun renderDrum(sound: DrumSound, sampleRate: Int, vel: Float): FloatArray {
        val v = vel.coerceIn(0.05f, 1f)
        val raw = when (sound) {
            DrumSound.KICK -> kick(sampleRate)
            DrumSound.SNARE -> snare(sampleRate)
            DrumSound.CLAP -> clap(sampleRate)
            DrumSound.HAT -> hat(sampleRate, open = false)
            DrumSound.OPEN_HAT -> hat(sampleRate, open = true)
            DrumSound.TOM_LO -> tom(sampleRate, 200.0, 88.0)
            DrumSound.TOM_HI -> tom(sampleRate, 330.0, 158.0)
            DrumSound.RIM -> rim(sampleRate)
        }
        return normalize(raw, 0.9f * v)
    }

    /** Render one pitched note held for [durSec] plus its natural release. */
    fun renderNote(sound: SynthSound, midi: Int, sampleRate: Int, durSec: Double, vel: Float): FloatArray {
        val v = vel.coerceIn(0.05f, 1f)
        val f = freqOf(midi)
        val raw = when (sound) {
            SynthSound.EPIANO -> epiano(f, sampleRate, durSec)
            SynthSound.PLUCK -> pluck(f, sampleRate, durSec)
            SynthSound.LEAD -> lead(f, sampleRate, durSec)
            SynthSound.PAD -> pad(f, sampleRate, durSec)
            SynthSound.BASS -> bass(f, sampleRate, durSec)
        }
        return normalize(raw, 0.88f * v)
    }

    // ------------------------------------------------------------------ kit

    private fun kick(sr: Int): FloatArray {
        val n = (sr * DrumSound.KICK.lenSec()).toInt()
        val out = FloatArray(n)
        val rnd = Random(7)
        val clickN = (sr * 0.004).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 46.0 + 150.0 * exp(-t / 0.045) // 150 Hz sweep down to 46 Hz
            phase += PI2 * f / sr
            var y = sin(phase) * exp(-t / 0.16)
            if (i < clickN) {
                val c = (rnd.nextFloat() * 2f - 1f) * exp(-i.toDouble() / (0.0012 * sr)).toFloat()
                y += c * 0.9
            }
            out[i] = y.toFloat()
        }
        return out
    }

    private fun snare(sr: Int): FloatArray {
        val n = (sr * DrumSound.SNARE.lenSec()).toInt()
        val out = FloatArray(n)
        val rnd = Random(11)
        val hp = BiquadFilter(BiquadFilter.Kind.HIGHPASS, 1900.0, sr, 0.8)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val noiseVal = (rnd.nextFloat() * 2f - 1f).toDouble()
            val tone = sin(PI2 * 190.0 * t) * exp(-t / 0.055) * 0.55
            val rattle = hp.process(noiseVal) * exp(-t / 0.045) * 0.85
            out[i] = (tone + rattle).toFloat()
        }
        return out
    }

    private fun clap(sr: Int): FloatArray {
        val n = (sr * DrumSound.CLAP.lenSec()).toInt()
        val out = FloatArray(n)
        val rnd = Random(13)
        val hp = BiquadFilter(BiquadFilter.Kind.HIGHPASS, 900.0, sr)
        val lp = BiquadFilter(BiquadFilter.Kind.LOWPASS, 5200.0, sr)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            // Three quick bursts then a decaying tail — the classic hand-clap shape.
            val env = when {
                t < 0.012 -> 1.0
                t < 0.022 -> 0.35
                t < 0.034 -> 1.0
                else -> exp(-(t - 0.034) / 0.085)
            }
            val x = lp.process(hp.process((rnd.nextFloat() * 2f - 1f).toDouble())) * env
            out[i] = x.toFloat()
        }
        return out
    }

    private fun hat(sr: Int, open: Boolean): FloatArray {
        val n = (sr * (if (open) DrumSound.OPEN_HAT.lenSec() else DrumSound.HAT.lenSec())).toInt()
        val out = FloatArray(n)
        val rnd = Random(if (open) 17 else 19)
        val hp = BiquadFilter(BiquadFilter.Kind.HIGHPASS, if (open) 6500.0 else 7600.0, sr)
        val tau = if (open) 0.16 else 0.028
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = exp(-t / tau)
            out[i] = (hp.process((rnd.nextFloat() * 2f - 1f).toDouble()) * env).toFloat()
        }
        return out
    }

    private fun tom(sr: Int, fStart: Double, fEnd: Double): FloatArray {
        val n = (sr * 0.55).toInt()
        val out = FloatArray(n)
        val rnd = Random(23)
        val clickN = (sr * 0.004).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = fEnd + (fStart - fEnd) * exp(-t / 0.05)
            phase += PI2 * f / sr
            val env = exp(-t / 0.13)
            var y = (sin(phase) + 0.14 * sin(2.0 * phase)) * env
            if (i < clickN) {
                val c = (rnd.nextFloat() * 2f - 1f) * exp(-i.toDouble() / (0.0015 * sr)).toFloat()
                y += c * 0.5
            }
            out[i] = y.toFloat()
        }
        return out
    }

    private fun rim(sr: Int): FloatArray {
        val n = (sr * DrumSound.RIM.lenSec()).toInt()
        val out = FloatArray(n)
        val rnd = Random(29)
        val hp = BiquadFilter(BiquadFilter.Kind.HIGHPASS, 2500.0, sr)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val knock = sin(PI2 * 1050.0 * t) * exp(-t / 0.011) * 0.6
            val click = hp.process((rnd.nextFloat() * 2f - 1f).toDouble()) * exp(-t / 0.004) * 0.5
            out[i] = (knock + click).toFloat()
        }
        return out
    }

    // ------------------------------------------------------------- melodic

    /** Rhodes-ish electric piano: detuned tine pair + ringing partials. */
    private fun epiano(f: Double, sr: Int, durSec: Double): FloatArray {
        val n = ((durSec.coerceIn(0.03, 3.0) + 1.5) * sr).toInt()
        val out = FloatArray(n)
        val durN = (durSec * sr).toInt()
        val w1 = PI2 * f * 0.9993
        val w2 = PI2 * f * 1.0007
        val w2p = PI2 * f * 2.0
        val w3p = PI2 * f * 3.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val held = i < durN
            val env = if (held) {
                1.0 - exp(-i.toDouble() / (0.004 * sr))
            } else {
                exp(-(i - durN).toDouble() / (0.55 * sr))
            }
            val tine = sin(w1 * t) * 0.5 + sin(w2 * t) * 0.5
            val p2 = sin(w2p * t) * 0.30 * exp(-t / 0.30)
            val p3 = sin(w3p * t) * 0.13 * exp(-t / 0.16)
            val trem = 1.0 + 0.055 * sin(PI2 * 5.1 * t)
            var y = (tine + p2 + p3) * env * trem
            y = tanh(y * 1.35)
            out[i] = y.toFloat()
        }
        return out
    }

    /** Karplus–Strong plucked string — bright, decaying, guitar-like. */
    private fun pluck(f: Double, sr: Int, durSec: Double): FloatArray {
        val n = ((durSec.coerceIn(0.03, 2.0) + 0.8) * sr).toInt()
        val out = FloatArray(n)
        val period = (sr / f).toInt().coerceIn(4, sr / 2)
        val buf = FloatArray(period)
        val rnd = Random(31)
        for (i in 0 until period) {
            buf[i] = (rnd.nextFloat() * 2f - 1f) * (1.0 - 0.72 * (i.toDouble() / period)).toFloat()
        }
        var idx = 0
        for (i in 0 until n) {
            val cur = buf[idx]
            val next = buf[(idx + 1) % period]
            out[i] = cur
            buf[idx] = (cur + next) * 0.5f * 0.9905f
            idx = (idx + 1) % period
        }
        return out
    }

    /** Saw lead: two detuned saws + sub square, soft vibrato, driven tone. */
    private fun lead(f: Double, sr: Int, durSec: Double): FloatArray {
        val n = ((durSec.coerceIn(0.03, 9.0) + 0.45) * sr).toInt()
        val out = FloatArray(n)
        val durN = (durSec * sr).toInt()
        val attackN = (sr * 0.008).toInt().coerceAtLeast(1)
        val releaseN = (sr * 0.45).toInt().coerceAtLeast(1)
        var p1 = 0.0
        var p2 = 0.0
        var pSub = 0.0
        val inc1 = f * 0.998 / sr
        val inc2 = f * 1.004 / sr
        val incSub = f * 0.5 / sr
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val vib = 1.0 + 0.0045 * sin(PI2 * 5.2 * t)
            p1 = (p1 + inc1 * vib) % 1.0
            p2 = (p2 + inc2 * vib) % 1.0
            pSub = (pSub + incSub * vib) % 1.0
            val saw1 = 2.0 * p1 - 1.0
            val saw2 = 2.0 * p2 - 1.0
            val sub = if (pSub < 0.5) 1.0 else -1.0
            val env = when {
                i < attackN -> i.toDouble() / attackN
                i > durN -> (1.0 - (i - durN).toDouble() / releaseN).coerceAtLeast(0.0)
                else -> 1.0
            }
            var y = ((saw1 + saw2) * 0.28 + sub * 0.30) * env
            y = tanh(y * 1.6)
            out[i] = y.toFloat()
        }
        return out
    }

    /** Warm pad: three detuned saws through a soft low-pass, slow attack/release. */
    private fun pad(f: Double, sr: Int, durSec: Double): FloatArray {
        val n = ((durSec.coerceIn(0.1, 9.0) + 1.1) * sr).toInt()
        val out = FloatArray(n)
        val durN = (durSec * sr).toInt()
        val attackN = (sr * 0.09).toInt().coerceAtLeast(1)
        val releaseN = (sr * 1.1).toInt().coerceAtLeast(1)
        val lp = BiquadFilter(BiquadFilter.Kind.LOWPASS, 2600.0, sr, 0.7)
        var p0 = 0.0
        var p1 = 0.0
        var p2 = 0.0
        val i0 = f * 0.996 / sr
        val i1 = f / sr
        val i2 = f * 1.004 / sr
        for (i in 0 until n) {
            p0 = (p0 + i0) % 1.0
            p1 = (p1 + i1) % 1.0
            p2 = (p2 + i2) % 1.0
            val saw = (2.0 * p0 - 1.0) * 0.36 + (2.0 * p1 - 1.0) * 0.36 + (2.0 * p2 - 1.0) * 0.36
            val env = when {
                i < attackN -> (i.toDouble() / attackN) * (i.toDouble() / attackN)
                i > durN -> (1.0 - (i - durN).toDouble() / releaseN).coerceAtLeast(0.0)
                else -> 1.0
            }
            out[i] = (lp.process(saw) * env).toFloat()
        }
        return out
    }

    /** Deep analog bass: fundamental + harmonics through a soft driver. */
    private fun bass(f: Double, sr: Int, durSec: Double): FloatArray {
        val n = ((durSec.coerceIn(0.03, 9.0) + 0.18) * sr).toInt()
        val out = FloatArray(n)
        val durN = (durSec * sr).toInt()
        val attackN = (sr * 0.006).toInt().coerceAtLeast(1)
        val releaseN = (sr * 0.18).toInt().coerceAtLeast(1)
        val w = PI2 * f
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val sig = sin(w * t) + 0.30 * sin(2.0 * w * t) + 0.12 * sin(3.0 * w * t)
            val env = when {
                i < attackN -> i.toDouble() / attackN
                i > durN -> (1.0 - (i - durN).toDouble() / releaseN).coerceAtLeast(0.0)
                else -> 1.0
            }
            val y = tanh(sig * 1.25) * env
            out[i] = y.toFloat()
        }
        return out
    }

    // --------------------------------------------------------------- helpers

    /** Scale the buffer so its peak hits [target]; handles silence safely. */
    private fun normalize(a: FloatArray, target: Float): FloatArray {
        var peak = 0.0
        for (v in a) {
            val x = abs(v.toDouble())
            if (x > peak) peak = x
        }
        if (peak > 1e-6 && peak != 1.0) {
            val g = (target.toDouble() / peak).toFloat()
            for (i in a.indices) a[i] = a[i] * g
        }
        return a
    }
}
