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
 * mono floats in [-1, 1] at whatever sample rate is requested.
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
    // Extended kit
    KICK_808("808 Kick"),
    SNARE_808("808 Snare"),
    HAT_808("808 Hat"),
    COWBELL("Cowbell"),
    SHAKER("Shaker"),
    ;

    /** Full rendered length in seconds (sound decays to ~silence inside). */
    fun lenSec(): Double = when (this) {
        KICK -> 0.55
        SNARE -> 0.38
        CLAP -> 0.42
        HAT -> 0.12
        OPEN_HAT -> 0.55
        TOM_LO -> 0.55
        TOM_HI -> 0.55
        RIM -> 0.16
        KICK_808 -> 0.85
        SNARE_808 -> 0.45
        HAT_808 -> 0.10
        COWBELL -> 0.28
        SHAKER -> 0.18
    }
}

/** A pitched playable voice. `tailSec` is the natural release added after the held note. */
enum class SynthSound(val label: String, val tailSec: Double) {
    EPIANO("E-Piano", 1.5),
    PLUCK("Pluck", 0.8),
    LEAD("Lead", 0.45),
    PAD("Pad", 1.3),
    BASS("Bass", 0.22),
    STRINGS("Strings", 1.4),
    CHOIR("Choir", 1.6),
    ORGAN("Organ", 0.35),
    BELL("Bell", 2.2),
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
            DrumSound.KICK_808 -> kick808(sampleRate)
            DrumSound.SNARE_808 -> snare808(sampleRate)
            DrumSound.HAT_808 -> hat808(sampleRate)
            DrumSound.COWBELL -> cowbell(sampleRate)
            DrumSound.SHAKER -> shaker(sampleRate)
        }
        // Velocity curves: softer hits are darker / shorter for realism
        val shaped = if (v < 0.99f) applyVelocityShape(raw, sampleRate, v) else raw
        return normalize(shaped, 0.9f * v)
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
            SynthSound.STRINGS -> strings(f, sampleRate, durSec)
            SynthSound.CHOIR -> choir(f, sampleRate, durSec)
            SynthSound.ORGAN -> organ(f, sampleRate, durSec)
            SynthSound.BELL -> bell(f, sampleRate, durSec)
        }
        return normalize(raw, 0.88f * v)
    }

    /** Softer velocity → slightly shorter + darker (simple low-pass). */
    private fun applyVelocityShape(src: FloatArray, sr: Int, vel: Float): FloatArray {
        val out = src.copyOf()
        val cutoff = 4000.0 + 10000.0 * vel // quieter = darker
        val lp = BiquadFilter(BiquadFilter.Kind.LOWPASS, cutoff, sr, 0.7)
        for (i in out.indices) {
            out[i] = lp.process(out[i].toDouble()).toFloat()
        }
        // Soften the tail a bit more at low velocity
        val shorten = 0.55 + 0.45 * vel
        val keep = (out.size * shorten).toInt().coerceIn(1, out.size)
        if (keep < out.size) {
            val fade = (sr * 0.02).toInt().coerceAtLeast(1)
            for (i in keep until out.size) {
                val t = (i - keep).toFloat() / fade
                out[i] = if (t >= 1f) 0f else out[i] * (1f - t)
            }
        }
        return out
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
            val f = 46.0 + 150.0 * exp(-t / 0.045)
            phase += PI2 * f / sr
            var y = sin(phase) * exp(-t / 0.16)
            if (i < clickN) {
                val c = (rnd.nextFloat() * 2f - 1f) * exp(-i.toDouble() / (0.0012 * sr)).toFloat()
                y += c * 0.9
            }
            // Subtle body harmonics
            y += 0.12 * sin(phase * 2.0) * exp(-t / 0.08)
            out[i] = y.toFloat()
        }
        return out
    }

    /** Classic 808 boom: long sine with pitch sweep and soft distortion. */
    private fun kick808(sr: Int): FloatArray {
        val n = (sr * DrumSound.KICK_808.lenSec()).toInt()
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 38.0 + 120.0 * exp(-t / 0.08)
            phase += PI2 * f / sr
            val env = exp(-t / 0.32)
            var y = sin(phase) * env
            y = tanh(y * 1.8) // soft saturation for the classic 808 growl
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
            val rattle = hp.process(noiseVal) * exp(-t / 0.05) * 0.88
            out[i] = (tone + rattle).toFloat()
        }
        return out
    }

    private fun snare808(sr: Int): FloatArray {
        val n = (sr * DrumSound.SNARE_808.lenSec()).toInt()
        val out = FloatArray(n)
        val rnd = Random(37)
        val bp = BiquadFilter(BiquadFilter.Kind.HIGHPASS, 800.0, sr, 1.2)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val tone = sin(PI2 * 180.0 * t) * exp(-t / 0.04) * 0.45
            val noise = bp.process((rnd.nextFloat() * 2f - 1f).toDouble()) * exp(-t / 0.12) * 0.9
            out[i] = (tone + noise).toFloat()
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
            val env = when {
                t < 0.012 -> 1.0
                t < 0.022 -> 0.35
                t < 0.034 -> 1.0
                else -> exp(-(t - 0.034) / 0.09)
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
        val hp = BiquadFilter(BiquadFilter.Kind.HIGHPASS, if (open) 6200.0 else 7800.0, sr)
        val tau = if (open) 0.18 else 0.026
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = exp(-t / tau)
            // Metallic sheen via a few detuned partials mixed with noise
            val metal = sin(PI2 * 9400.0 * t) * 0.15 + sin(PI2 * 11200.0 * t) * 0.1
            out[i] = (hp.process((rnd.nextFloat() * 2f - 1f).toDouble()) * env + metal * env * 0.4).toFloat()
        }
        return out
    }

    private fun hat808(sr: Int): FloatArray {
        val n = (sr * DrumSound.HAT_808.lenSec()).toInt()
        val out = FloatArray(n)
        val rnd = Random(41)
        val hp = BiquadFilter(BiquadFilter.Kind.HIGHPASS, 9000.0, sr)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            out[i] = (hp.process((rnd.nextFloat() * 2f - 1f).toDouble()) * exp(-t / 0.018)).toFloat()
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

    private fun cowbell(sr: Int): FloatArray {
        val n = (sr * DrumSound.COWBELL.lenSec()).toInt()
        val out = FloatArray(n)
        // Two inharmonic partials (classic cowbell)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = exp(-t / 0.09)
            val y = (sin(PI2 * 587.0 * t) + 0.7 * sin(PI2 * 845.0 * t)) * env
            out[i] = tanh(y * 1.3).toFloat()
        }
        return out
    }

    private fun shaker(sr: Int): FloatArray {
        val n = (sr * DrumSound.SHAKER.lenSec()).toInt()
        val out = FloatArray(n)
        val rnd = Random(47)
        val hp = BiquadFilter(BiquadFilter.Kind.HIGHPASS, 4500.0, sr)
        val lp = BiquadFilter(BiquadFilter.Kind.LOWPASS, 12000.0, sr)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = when {
                t < 0.02 -> t / 0.02
                else -> exp(-(t - 0.02) / 0.06)
            }
            out[i] = (lp.process(hp.process((rnd.nextFloat() * 2f - 1f).toDouble())) * env).toFloat()
        }
        return out
    }

    // ------------------------------------------------------------- melodic

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
            buf[idx] = (cur + next) * 0.5f * 0.9912f
            idx = (idx + 1) % period
        }
        return out
    }

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

    private fun pad(f: Double, sr: Int, durSec: Double): FloatArray {
        val n = ((durSec.coerceIn(0.1, 9.0) + 1.3) * sr).toInt()
        val out = FloatArray(n)
        val durN = (durSec * sr).toInt()
        val attackN = (sr * 0.12).toInt().coerceAtLeast(1)
        val releaseN = (sr * 1.3).toInt().coerceAtLeast(1)
        val lp = BiquadFilter(BiquadFilter.Kind.LOWPASS, 2400.0, sr, 0.65)
        var p0 = 0.0; var p1 = 0.0; var p2 = 0.0; var p3 = 0.0
        val i0 = f * 0.995 / sr
        val i1 = f / sr
        val i2 = f * 1.005 / sr
        val i3 = f * 1.012 / sr
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            p0 = (p0 + i0) % 1.0
            p1 = (p1 + i1) % 1.0
            p2 = (p2 + i2) % 1.0
            p3 = (p3 + i3) % 1.0
            val saw = (2.0 * p0 - 1.0) * 0.28 + (2.0 * p1 - 1.0) * 0.28 +
                (2.0 * p2 - 1.0) * 0.28 + (2.0 * p3 - 1.0) * 0.16
            val chorus = 1.0 + 0.03 * sin(PI2 * 0.35 * t)
            val env = when {
                i < attackN -> {
                    val a = i.toDouble() / attackN
                    a * a
                }
                i > durN -> (1.0 - (i - durN).toDouble() / releaseN).coerceAtLeast(0.0)
                else -> 1.0
            }
            out[i] = (lp.process(saw * chorus) * env).toFloat()
        }
        return out
    }

    private fun bass(f: Double, sr: Int, durSec: Double): FloatArray {
        val n = ((durSec.coerceIn(0.03, 9.0) + 0.22) * sr).toInt()
        val out = FloatArray(n)
        val durN = (durSec * sr).toInt()
        val attackN = (sr * 0.005).toInt().coerceAtLeast(1)
        val releaseN = (sr * 0.22).toInt().coerceAtLeast(1)
        val w = PI2 * f
        val lp = BiquadFilter(BiquadFilter.Kind.LOWPASS, 3200.0, sr, 0.8)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            // Punchy: fundamental + odd harmonics + mild drive
            val sig = sin(w * t) + 0.35 * sin(2.0 * w * t) + 0.18 * sin(3.0 * w * t) +
                0.08 * sin(5.0 * w * t)
            val env = when {
                i < attackN -> i.toDouble() / attackN
                i > durN -> (1.0 - (i - durN).toDouble() / releaseN).coerceAtLeast(0.0)
                else -> 1.0
            }
            val y = lp.process(tanh(sig * 1.35)) * env
            out[i] = y.toFloat()
        }
        return out
    }

    /** Lush ensemble strings — many detuned saws + slow attack. */
    private fun strings(f: Double, sr: Int, durSec: Double): FloatArray {
        val n = ((durSec.coerceIn(0.1, 9.0) + 1.4) * sr).toInt()
        val out = FloatArray(n)
        val durN = (durSec * sr).toInt()
        val attackN = (sr * 0.18).toInt().coerceAtLeast(1)
        val releaseN = (sr * 1.4).toInt().coerceAtLeast(1)
        val lp = BiquadFilter(BiquadFilter.Kind.LOWPASS, 4200.0, sr, 0.6)
        val detunes = doubleArrayOf(0.991, 0.996, 1.0, 1.004, 1.009, 1.015)
        val phases = DoubleArray(detunes.size)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var sum = 0.0
            for (k in detunes.indices) {
                phases[k] = (phases[k] + f * detunes[k] / sr) % 1.0
                sum += 2.0 * phases[k] - 1.0
            }
            sum /= detunes.size
            val vibrato = 1.0 + 0.006 * sin(PI2 * 4.8 * t)
            val env = when {
                i < attackN -> {
                    val a = i.toDouble() / attackN
                    a * a * (3.0 - 2.0 * a) // smoothstep
                }
                i > durN -> (1.0 - (i - durN).toDouble() / releaseN).coerceAtLeast(0.0)
                else -> 1.0
            }
            out[i] = (lp.process(sum * vibrato) * env * 0.85).toFloat()
        }
        return out
    }

    /** Soft choir-like formant pad. */
    private fun choir(f: Double, sr: Int, durSec: Double): FloatArray {
        val n = ((durSec.coerceIn(0.1, 9.0) + 1.6) * sr).toInt()
        val out = FloatArray(n)
        val durN = (durSec * sr).toInt()
        val attackN = (sr * 0.22).toInt().coerceAtLeast(1)
        val releaseN = (sr * 1.6).toInt().coerceAtLeast(1)
        // Formant-ish band emphasis around 700 / 1200 / 2500 Hz via additive partials
        val partials = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val gains = doubleArrayOf(0.55, 0.35, 0.28, 0.18, 0.10)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var y = 0.0
            for (k in partials.indices) {
                val ff = f * partials[k]
                // Gentle formant bump
                val formant = 1.0 + 0.4 * exp(-((ff - 700.0) / 400.0).pow(2.0)) +
                    0.3 * exp(-((ff - 1200.0) / 500.0).pow(2.0))
                y += sin(PI2 * ff * t) * gains[k] * formant
            }
            // Slight ensemble detune
            y += 0.2 * sin(PI2 * f * 1.003 * t)
            y += 0.15 * sin(PI2 * f * 0.997 * t)
            val env = when {
                i < attackN -> {
                    val a = i.toDouble() / attackN
                    a * a
                }
                i > durN -> (1.0 - (i - durN).toDouble() / releaseN).coerceAtLeast(0.0)
                else -> 1.0
            }
            out[i] = (tanh(y * 0.9) * env).toFloat()
        }
        return out
    }

    /** Drawbar-style organ (Hammond-ish additive). */
    private fun organ(f: Double, sr: Int, durSec: Double): FloatArray {
        val n = ((durSec.coerceIn(0.03, 9.0) + 0.35) * sr).toInt()
        val out = FloatArray(n)
        val durN = (durSec * sr).toInt()
        val attackN = (sr * 0.004).toInt().coerceAtLeast(1)
        val releaseN = (sr * 0.35).toInt().coerceAtLeast(1)
        // Classic drawbar ratios (approx 16', 8', 4', 2⅔', 2')
        val ratios = doubleArrayOf(0.5, 1.0, 2.0, 3.0, 4.0)
        val draws = doubleArrayOf(0.7, 1.0, 0.55, 0.35, 0.25)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var y = 0.0
            for (k in ratios.indices) {
                y += sin(PI2 * f * ratios[k] * t) * draws[k]
            }
            // Leslie-ish slow AM
            val leslie = 1.0 + 0.08 * sin(PI2 * 0.8 * t)
            val env = when {
                i < attackN -> i.toDouble() / attackN
                i > durN -> (1.0 - (i - durN).toDouble() / releaseN).coerceAtLeast(0.0)
                else -> 1.0
            }
            out[i] = (tanh(y * 0.55) * leslie * env).toFloat()
        }
        return out
    }

    /** Bright FM-ish bell. */
    private fun bell(f: Double, sr: Int, durSec: Double): FloatArray {
        val n = ((durSec.coerceIn(0.05, 4.0) + 2.2) * sr).toInt()
        val out = FloatArray(n)
        val durN = (durSec * sr).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            // Simple FM: carrier + modulator
            val mod = sin(PI2 * f * 3.5 * t) * exp(-t / 0.4) * 1.8
            val car = sin(PI2 * f * t + mod)
            val partial = 0.35 * sin(PI2 * f * 2.7 * t) * exp(-t / 0.5)
            val env = if (i < durN) {
                exp(-t / 1.1)
            } else {
                exp(-t / 1.1) * exp(-(i - durN).toDouble() / (0.8 * sr))
            }
            out[i] = ((car + partial) * env * 0.7).toFloat()
        }
        return out
    }

    // --------------------------------------------------------------- helpers

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
