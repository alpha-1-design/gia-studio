package com.giastudio.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Real-time audio engine for GIA Studio.
 *
 * One mixer thread renders the session through the same [renderBlock]
 * pipeline used by offline exports. Recording runs on its own thread with an
 * optional input-monitoring track; the tuner uses a third, lightweight
 * capture. All three use the device's native sample rate where reported.
 *
 * Callbacks fire on the engine's worker threads — the UI layer marshals them
 * to the main thread.
 */
class AudioEngine(context: Context) {

    val sampleRate: Int
    val blockFrames = 256

    // ---------------------------------------------------------------- state
    @Volatile var isPlaying = false
        private set
    @Volatile var isRecording = false
        private set
    @Volatile var isTuning = false
        private set

    /** Current playback head in seconds (also updated while paused/stopped). */
    val positionSec: Double get() = posFrames.toDouble() / sampleRate

    @Volatile
    private var posFrames = 0L

    @Volatile
    private var playRequested = false

    @Volatile
    var loopEnabled = false

    /** Latest immutable session snapshot; refreshed by the controller. */
    @Volatile
    var currentSnap: SnapProject? = null

    // ------------------------------------------------------------ callbacks
    /** Throttled (~15 Hz) playback-position updates. */
    var onPosition: ((Double) -> Unit)? = null
    /** Fired when playback stops (manual or end-of-arrangement). */
    var onTransportStopped: (() -> Unit)? = null
    /** Live recording level, 0..1, ~20 Hz. */
    var onRecordLevel: ((Float) -> Unit)? = null
    /** Recording finished with the raw (pre-cleanup) WAV written. */
    var onRecordingFinished: ((File, Int) -> Unit)? = null
    /** Tuner results; pitch null when the input is too quiet/unpitched. */
    var onTunerResult: ((PitchResult?, Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val lock = Object()
    @Volatile private var stopped = false

    init {
        sampleRate = pickSampleRate(context)
        engineThread.start()
    }

    // -------------------------------------------------------------- helpers
    private fun pickSampleRate(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prop = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val rate = prop?.toIntOrNull() ?: 0
        return when (rate) {
            8000, 11025, 16000, 22050, 32000, 44100, 48000 -> rate
            else -> 44100
        }
    }

    private fun wake() {
        synchronized(lock) { lock.notifyAll() }
    }

    /** Engine heartbeat thread — waits for play requests and renders them. */
    private val engineThread = Thread({
        while (!stopped) {
            synchronized(lock) {
                while (!playRequested && !stopped) {
                    try {
                        lock.wait(60)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            if (stopped) break
            runPlaybackSession()
        }
    }, "gia-playback")

    // ------------------------------------------------------------- playback
    fun requestPlay() {
        playRequested = true
        wake()
    }

    /** Pause: keeps the playhead; next requestPlay resumes from here. */
    fun pause() {
        playRequested = false
    }

    /** Stop: pauses and returns the playhead to the start. */
    fun stop() {
        playRequested = false
        posFrames = 0L
    }

    fun seekSeconds(sec: Double) {
        if (!isPlaying) posFrames = (sec * sampleRate).toLong().coerceAtLeast(0L)
    }

    private fun createOutputTrack(): AudioTrack? {
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val minBytes = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
            )
            val bufferFrames = if (minBytes > 0) max(minBytes / 8, blockFrames * 4) else blockFrames * 8
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufferFrames * 8)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                null
            } else {
                track
            }
        } catch (e: Exception) {
            onError?.invoke("Could not open the audio output: ${e.message}")
            null
        }
    }

    private fun arrangementEndFrames(snap: SnapProject): Long {
        var end = 0L
        var hasTailFx = false
        for (t in snap.tracks) {
            for (c in t.clips) {
                val e = c.startFrame + c.lengthFrames
                if (e > end) end = e
            }
            if (t.fx.delayOn || t.fx.reverbOn) hasTailFx = true
        }
        val tail = if (hasTailFx) (sampleRate * 3.0).toLong() else (sampleRate * 0.35).toLong()
        return end + tail
    }

    private fun runPlaybackSession() {
        val snap = currentSnap
        if (snap == null || snap.tracks.none { it.audible && it.clips.isNotEmpty() }) {
            playRequested = false
            onTransportStopped?.invoke()
            return
        }
        val track = createOutputTrack() ?: run {
            playRequested = false
            onTransportStopped?.invoke()
            return
        }
        val chains = HashMap<Int, TrackChain>()
        val master = MasterChain(sampleRate)
        val outL = FloatArray(blockFrames)
        val outR = FloatArray(blockFrames)
        val scratch = FloatArray(blockFrames)
        val interleaved = FloatArray(blockFrames * 2)
        val endFrames = arrangementEndFrames(snap)

        isPlaying = true
        var ok = true
        try {
            track.play()
            var notifyCountdown = 0
            while (playRequested && !stopped) {
                val fresh = currentSnap
                val total = if (fresh != null) arrangementEndFrames(fresh) else endFrames
                if (posFrames >= total) {
                    if (loopEnabled) {
                        posFrames = 0L
                    } else {
                        posFrames = 0L
                        break
                    }
                }
                val liveSnap = fresh ?: snap
                renderBlock(liveSnap, chains, master, posFrames, scratch, outL, outR)
                var p = 0
                for (i in 0 until blockFrames) {
                    interleaved[p++] = outL[i]
                    interleaved[p++] = outR[i]
                }
                val written = track.write(interleaved, 0, interleaved.size, AudioTrack.WRITE_BLOCKING)
                if (written < interleaved.size) {
                    onError?.invoke("Audio output underrun while playing.")
                    ok = false
                    break
                }
                posFrames += blockFrames
                if (++notifyCountdown >= 6) {
                    notifyCountdown = 0
                    onPosition?.invoke(posFrames.toDouble() / sampleRate)
                }
            }
        } catch (e: Exception) {
            ok = false
            onError?.invoke("Playback stopped unexpectedly: ${e.message}")
        } finally {
            try {
                track.pause()
                track.flush()
                track.release()
            } catch (_: Exception) {
            }
            playRequested = false
            isPlaying = false
            onTransportStopped?.invoke()
        }
    }

    // -------------------------------------------------------------- recording
    /**
     * Start recording to [file] (mono 16-bit WAV). While recording, the input
     * is monitored through the speaker only when [monitorOn]; [gateOn] applies
     * a live noise gate to that monitor path (never to the recorded file).
     */
    fun startRecording(file: File, gateOn: Boolean, monitorOn: Boolean) {
        if (isRecording || isPlaying) return
        isRecording = true
        Thread({
            recordLoop(file, gateOn, monitorOn)
        }, "gia-record").start()
    }

    private fun recordLoop(file: File, gateOn: Boolean, monitorOn: Boolean) {
        var record: AudioRecord? = null
        var monitor: AudioTrack? = null
        var writer: WavFileWriter? = null
        var gate: NoiseGate? = null
        try {
            val bufferBytes = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 2) // ~12 ms of input buffer
            val source = try {
                MediaRecorder.AudioSource.UNPROCESSED
            } catch (e: Exception) {
                MediaRecorder.AudioSource.MIC
            }
            record = buildRecord(source, bufferBytes)
            if (record == null) {
                record = buildRecord(MediaRecorder.AudioSource.MIC, bufferBytes)
            }
            if (record == null) {
                onError?.invoke("Microphone unavailable. Check the mic permission.")
                isRecording = false
                return
            }

            if (monitorOn) {
                try {
                    val fmt = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                    monitor = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(fmt)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build()
                    if (monitor.state == AudioTrack.STATE_INITIALIZED) {
                        monitor.play()
                    } else {
                        monitor.release()
                        monitor = null
                    }
                } catch (_: Exception) {
                    monitor = null
                }
            }

            if (gateOn) gate = NoiseGate(sampleRate, -48.0)

            writer = WavFileWriter(file, sampleRate, 1, 16)
            val shorts = ShortArray(sampleRate / 20) // 50 ms chunks
            val floats = FloatArray(shorts.size)
            val monitorFloats = FloatArray(shorts.size)
            record.startRecording()
            var levelCountdown = 0
            while (isRecording && !stopped) {
                val n = record.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                var peak = 0f
                for (i in 0 until n) {
                    val v = shorts[i].toFloat() / 32768f
                    floats[i] = v
                    val a = abs(v)
                    if (a > peak) peak = a
                }
                writer.writeMono16(floats, 0, n)
                if (monitor != null) {
                    val mon = floats
                    if (gate != null) {
                        gate.processBlock(mon, monitorFloats, n, sampleRate)
                        monitor.write(monitorFloats, 0, n, AudioTrack.WRITE_BLOCKING)
                    } else {
                        monitor.write(mon, 0, n, AudioTrack.WRITE_BLOCKING)
                    }
                }
                if (++levelCountdown >= 3) {
                    levelCountdown = 0
                    onRecordLevel?.invoke(peak)
                }
            }
            record.stop()
            writer.close()
            writer = null
            onRecordLevel?.invoke(0f)
            onRecordingFinished?.invoke(file, 0)
        } catch (e: Exception) {
            onError?.invoke("Recording failed: ${e.message}")
            try {
                writer?.abort()
            } catch (_: Exception) {
            }
        } finally {
            try {
                record?.stop()
                record?.release()
            } catch (_: Exception) {
            }
            try {
                monitor?.stop()
                monitor?.release()
            } catch (_: Exception) {
            }
            isRecording = false
        }
    }

    private fun buildRecord(source: Int, bufferBytes: Int): AudioRecord? {
        return try {
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val r = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufferBytes)
                .build()
            if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                r.release()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun stopRecording() {
        isRecording = false
    }

    // ----------------------------------------------------------------- tuner
    fun startTuner() {
        if (isTuning) return
        isTuning = true
        Thread({
            tunerLoop()
        }, "gia-tuner").start()
    }

    private fun tunerLoop() {
        var record: AudioRecord? = null
        val detector = PitchDetector(sampleRate)
        try {
            val bufferBytes = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 4)
            val source = try {
                MediaRecorder.AudioSource.UNPROCESSED
            } catch (e: Exception) {
                MediaRecorder.AudioSource.MIC
            }
            record = buildRecord(source, bufferBytes)
            if (record == null) record = buildRecord(MediaRecorder.AudioSource.MIC, bufferBytes)
            if (record == null) {
                onError?.invoke("Microphone unavailable.")
                isTuning = false
                return
            }
            record.startRecording()
            val shorts = ShortArray(sampleRate / 20)
            val floats = FloatArray(shorts.size)
            var level = 0f
            var countdown = 0
            while (isTuning && !stopped) {
                val n = record.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                var peak = 0f
                for (i in 0 until n) {
                    val v = shorts[i].toFloat() / 32768f
                    floats[i] = v
                    val a = abs(v)
                    if (a > peak) peak = a
                }
                val result = detector.push(floats, n)
                if (++countdown >= 3) {
                    countdown = 0
                    level = level * 0.6f + peak * 0.4f
                    onTunerResult?.invoke(result, level)
                }
            }
        } catch (e: Exception) {
            onError?.invoke("Tuner failed: ${e.message}")
        } finally {
            try {
                record?.stop()
                record?.release()
            } catch (_: Exception) {
            }
            isTuning = false
        }
    }

    fun stopTuner() {
        isTuning = false
    }

    // ---------------------------------------------------------------- release
    fun release() {
        stopped = true
        playRequested = false
        isRecording = false
        isTuning = false
        wake()
    }
}
