package com.giastudio.app.audio

import com.giastudio.app.model.StudioProject

/**
 * The one render path used everywhere.
 *
 * Before audio starts (live playback or offline mixdown), the current session
 * is copied into an immutable [SnapProject]. A render loop then advances a
 * global frame position and asks [renderBlock] to mix one block of audio.
 * Per-track FX chains hold their state across blocks so filters, delays and
 * reverb tails behave identically live and in exports.
 */

data class SnapFx(
    val hpOn: Boolean,
    val hpHz: Int,
    val lpOn: Boolean,
    val lpHz: Int,
    val driveOn: Boolean,
    val drive: Float,
    val delayOn: Boolean,
    val delayMs: Float,
    val delayFeedback: Float,
    val delayMix: Float,
    val reverbOn: Boolean,
    val reverbMix: Float,
)

data class SnapClip(
    val id: String,
    val startFrame: Long,
    val lengthFrames: Int,
    val gain: Float,
    val fadeInFrames: Int,
    val fadeOutFrames: Int,
    /** Mono PCM samples at the project sample rate. */
    val samples: FloatArray,
)

data class SnapTrack(
    val id: Int,
    val name: String,
    val volume: Float,
    val pan: Float,
    val audible: Boolean,
    val fx: SnapFx,
    val clips: List<SnapClip>,
)

data class SnapProject(
    val sampleRate: Int,
    val masterVolume: Float,
    val masterLimiter: Boolean,
    val tracks: List<SnapTrack>,
)

/**
 * @param sampleProvider returns the mono sample buffer for a clip, or null if
 * the clip's audio file is missing (the clip is then skipped).
 */
fun buildSnap(
    project: StudioProject,
    sampleRate: Int,
    sampleProvider: (clipId: String) -> FloatArray?,
): SnapProject {
    val anySolo = project.tracks.any { it.soloed }
    val tracks = project.tracks.map { t ->
        val audible = !t.muted && (!anySolo || t.soloed)
        val clips = t.clips
            .sortedBy { it.startSec }
            .mapNotNull { c ->
                val samples = sampleProvider(c.id) ?: return@mapNotNull null
                if (samples.isEmpty()) return@mapNotNull null
                SnapClip(
                    id = c.id,
                    startFrame = (c.startSec * sampleRate).toLong(),
                    lengthFrames = samples.size,
                    gain = c.gain,
                    fadeInFrames = (c.fadeInSec * sampleRate).toInt(),
                    fadeOutFrames = (c.fadeOutSec * sampleRate).toInt(),
                    samples = samples,
                )
            }
        SnapTrack(
            id = t.id,
            name = t.name,
            volume = t.volume,
            pan = t.pan,
            audible = audible,
            fx = SnapFx(
                hpOn = t.fx.hpOn, hpHz = t.fx.hpHz,
                lpOn = t.fx.lpOn, lpHz = t.fx.lpHz,
                driveOn = t.fx.driveOn, drive = t.fx.drive,
                delayOn = t.fx.delayOn, delayMs = t.fx.delayMs,
                delayFeedback = t.fx.delayFeedback, delayMix = t.fx.delayMix,
                reverbOn = t.fx.reverbOn, reverbMix = t.fx.reverbMix,
            ),
            clips = clips,
        )
    }
    return SnapProject(
        sampleRate = sampleRate,
        masterVolume = project.masterVolume,
        masterLimiter = project.masterLimiter,
        tracks = tracks,
    )
}

/** Per-track insert chain. Holds state across blocks (tails!). */
class TrackChain(sr: Int) {
    private val sampleRate = sr
    private val hp = BiquadFilter(BiquadFilter.Kind.HIGHPASS, 90.0, sr)
    private val lp = BiquadFilter(BiquadFilter.Kind.LOWPASS, 12000.0, sr)
    private val delay = DelayLine(sr)
    private val reverb = Reverb(sr)
    private var lastHpHz = 90
    private var lastLpHz = 12000

    /** Push live FX parameter changes into the chain without cutting tails. */
    fun sync(fx: SnapFx) {
        if (fx.hpHz != lastHpHz) {
            hp.reconfigure(freqHz = fx.hpHz.toDouble())
            lastHpHz = fx.hpHz
        }
        if (fx.lpHz != lastLpHz) {
            lp.reconfigure(freqHz = fx.lpHz.toDouble())
            lastLpHz = fx.lpHz
        }
    }

    /** Process `mono` in place through the enabled inserts. */
    fun process(mono: FloatArray, n: Int, fx: SnapFx) {
        if (fx.hpOn) {
            for (i in 0 until n) mono[i] = hp.process(mono[i].toDouble()).toFloat()
        }
        if (fx.lpOn) {
            for (i in 0 until n) mono[i] = lp.process(mono[i].toDouble()).toFloat()
        }
        if (fx.driveOn) {
            Drive.processBlock(mono, mono, n, fx.drive)
        }
        if (fx.delayOn) {
            val delaySamples = (fx.delayMs / 1000.0 * sampleRate).toInt().coerceAtLeast(1)
            val fb = fx.delayFeedback.toDouble().coerceIn(0.0, 0.92)
            val mix = fx.delayMix.toDouble().coerceIn(0.0, 1.0)
            for (i in 0 until n) {
                mono[i] = delay.process(mono[i].toDouble(), delaySamples, fb, mix).toFloat()
            }
        }
        if (fx.reverbOn) {
            val mix = fx.reverbMix.toDouble().coerceIn(0.0, 1.0)
            for (i in 0 until n) {
                val dry = mono[i].toDouble()
                val wet = reverb.process(dry)
                mono[i] = (dry * (1.0 - mix) + wet * mix).toFloat()
            }
        }
    }
}

/** Master bus: per-channel peak limiting state. */
class MasterChain(sr: Int) {
    private val limL = PeakLimiter(sr)
    private val limR = PeakLimiter(sr)

    fun process(left: Double, right: Double, limiterOn: Boolean): Pair<Double, Double> {
        if (!limiterOn) return Pair(left.coerceIn(-1.0, 1.0), right.coerceIn(-1.0, 1.0))
        return Pair(limL.process(left), limR.process(right))
    }
}

/**
 * Mix one block of `outL.size` frames starting at global [posFrame].
 * `scratch` must be a FloatArray of at least that size.
 */
fun renderBlock(
    snap: SnapProject,
    chains: MutableMap<Int, TrackChain>,
    master: MasterChain,
    posFrame: Long,
    scratch: FloatArray,
    outL: FloatArray,
    outR: FloatArray,
) {
    val n = outL.size
    for (i in 0 until n) {
        outL[i] = 0f
        outR[i] = 0f
    }
    for (t in snap.tracks) {
        if (!t.audible || t.clips.isEmpty()) continue
        val chain = chains.getOrPut(t.id) { TrackChain(snap.sampleRate) }
        chain.sync(t.fx)
        for (i in 0 until n) scratch[i] = 0f

        for (c in t.clips) {
            val clipStart = c.startFrame
            if (clipStart > posFrame + n - 1) continue
            if (clipStart + c.lengthFrames <= posFrame) continue
            val i0: Int
            val readFrom: Int
            if (posFrame >= clipStart) {
                i0 = 0
                readFrom = (posFrame - clipStart).toInt()
            } else {
                i0 = (clipStart - posFrame).toInt()
                readFrom = 0
            }
            if (readFrom >= c.lengthFrames) continue
            val take = minOf(n - i0, c.lengthFrames - readFrom)
            if (take <= 0) continue
            val g = c.gain
            val len = c.lengthFrames
            val fadeIn = c.fadeInFrames
            val fadeOut = c.fadeOutFrames
            val src = c.samples
            for (j in 0 until take) {
                val s = readFrom + j
                var env = 1f
                if (fadeIn > 0 && s < fadeIn) env = minOf(env, s.toFloat() / fadeIn)
                if (fadeOut > 0 && s >= len - fadeOut) {
                    env = minOf(env, (len - 1 - s).toFloat() / fadeOut)
                }
                scratch[i0 + j] += src[s] * g * env
            }
        }

        chain.process(scratch, n, t.fx)

        val (gl, gr) = panGains(t.pan)
        val gain = t.volume
        for (i in 0 until n) {
            val v = scratch[i] * gain
            outL[i] += (v.toDouble() * gl).toFloat()
            outR[i] += (v.toDouble() * gr).toFloat()
        }
    }

    val mg = snap.masterVolume
    if (mg != 1f) {
        for (i in 0 until n) {
            outL[i] = outL[i] * mg
            outR[i] = outR[i] * mg
        }
    }
    for (i in 0 until n) {
        val (l, r) = master.process(outL[i].toDouble(), outR[i].toDouble(), snap.masterLimiter)
        outL[i] = l.toFloat()
        outR[i] = r.toFloat()
    }
}
