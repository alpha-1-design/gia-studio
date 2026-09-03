package com.giastudio.app.audio

import com.giastudio.app.model.StudioProject
import java.io.File
import kotlin.math.abs

class MixdownException(message: String) : Exception(message)

/**
 * Offline renderer. Deterministic: fresh FX chains per pass and the exact
 * same [renderBlock] used live, so an export is what playback sounds like —
 * but finished (filters/delay/reverb tails included).
 */
object Mixdown {

    /** Render the whole session to [target] as stereo WAV. Throws on failure. */
    fun renderToFile(
        project: StudioProject,
        target: File,
        sampleRate: Int,
        bitDepth: Int,
        normalize: Boolean,
        sampleProvider: (clipId: String) -> FloatArray?,
        onProgress: (Float) -> Unit,
    ) {
        val snap = buildSnap(project, sampleRate, sampleProvider)
        var end = 0L
        var hasTailFx = false
        for (t in snap.tracks) {
            for (c in t.clips) {
                val e = c.startFrame + c.lengthFrames
                if (e > end) end = e
            }
            if (t.fx.delayOn || t.fx.reverbOn) hasTailFx = true
        }
        if (end <= 0) throw MixdownException("Nothing to export yet — record a clip first.")
        val tailSeconds = if (hasTailFx) 3.0 else 0.35
        val total = end + (tailSeconds * sampleRate).toLong()

        // Peak-normalization gain is folded into the master volume BEFORE the
        // master limiter runs, so an export can never clip from boosting.
        var renderSnap = snap
        if (normalize) {
            val peak = measurePeak(snap, total)
            if (peak > 1e-4) {
                val gain = (0.95 / peak).coerceAtMost(8.0)
                val boosted = (snap.masterVolume.toDouble() * gain).toFloat()
                renderSnap = snap.copy(masterVolume = boosted.coerceIn(0f, 2f))
            }
        }

        val chunk = 2048
        val outL = FloatArray(chunk)
        val outR = FloatArray(chunk)
        val scratch = FloatArray(chunk)
        val chains = HashMap<Int, TrackChain>()
        val master = MasterChain(sampleRate)
        val writer = WavFileWriter(target, sampleRate, 2, bitDepth)
        try {
            var pos = 0L
            while (pos < total) {
                val n = minOf(chunk.toLong(), total - pos).toInt()
                renderBlock(renderSnap, chains, master, pos, scratch, outL, outR)
                when (bitDepth) {
                    24 -> writer.writeStereo24(outL, outR, 0, n)
                    else -> writer.writeStereo16(outL, outR, 0, n)
                }
                pos += n
                onProgress(pos.toFloat() / total)
            }
            writer.close()
        } catch (e: Exception) {
            writer.abort()
            throw e
        }
    }

    private fun measurePeak(snap: SnapProject, total: Long): Double {
        val chunk = 2048
        val outL = FloatArray(chunk)
        val outR = FloatArray(chunk)
        val scratch = FloatArray(chunk)
        val chains = HashMap<Int, TrackChain>()
        val master = MasterChain(snap.sampleRate)
        var peak = 0.0
        var pos = 0L
        while (pos < total) {
            val n = minOf(chunk.toLong(), total - pos).toInt()
            renderBlock(snap, chains, master, pos, scratch, outL, outR)
            for (i in 0 until n) {
                val a = abs(outL[i].toDouble())
                val b = abs(outR[i].toDouble())
                if (a > peak) peak = a
                if (b > peak) peak = b
            }
            pos += n
        }
        return peak
    }
}
