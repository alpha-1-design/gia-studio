package com.giastudio.app.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Decoded mono audio at a given sample rate. */
data class DecodedAudio(val samples: FloatArray, val sampleRate: Int)

class WavException(message: String) : Exception(message)

/**
 * Read any common PCM WAV (8/16/24/32-bit int or 32-bit float, mono or
 * multichannel) and downmix to mono floats in [-1, 1].
 */
fun readWavToMono(file: File): DecodedAudio {
    val input = BufferedInputStream(FileInputStream(file), 1 shl 16)
    try {
        val little = LittleEndianReader(input)
        if (little.readString(4) != "RIFF") throw WavException("Not a RIFF/WAV file")
        little.readInt() // chunk size
        if (little.readString(4) != "WAVE") throw WavException("Not a WAVE file")

        var audioFormat = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var blockAlign = 0
        var dataChunk: ByteArray? = null

        while (true) {
            val id = try {
                little.readString(4)
            } catch (e: Exception) {
                break
            }
            if (id.length < 4) break
            val size = little.readInt()
            when (id) {
                "fmt " -> {
                    val fmtStart = little.pos
                    val formatTag = little.readShort()
                    audioFormat = formatTag
                    channels = little.readShort()
                    sampleRate = little.readInt()
                    little.readInt() // byte rate
                    blockAlign = little.readShort()
                    bitsPerSample = little.readShort()
                    if (formatTag == 0xFFFE) {
                        // WAVE_FORMAT_EXTENSIBLE — read the real sub-format
                        val cbSize = little.readShort()
                        if (cbSize >= 22) {
                            little.skip(2) // valid bits
                            little.skip(4) // channel mask
                            val sub = little.readShort()
                            if (sub == 1 || sub == 3) audioFormat = sub
                        }
                    }
                    little.skipTo(fmtStart + size)
                }
                "data" -> {
                    dataChunk = ByteArray(size)
                    little.readBytes(dataChunk!!)
                }
                else -> little.skip(size)
            }
        }

        if (dataChunk == null) throw WavException("No audio data chunk found")
        if (audioFormat != 1 && audioFormat != 3) {
            throw WavException("Unsupported WAV encoding (format $audioFormat); use PCM or IEEE float")
        }
        if (channels <= 0 || sampleRate <= 0) throw WavException("Bad WAV header")

        val bytesPerSample = if (bitsPerSample <= 8) 1 else bitsPerSample / 8
        if (bytesPerSample < 1) throw WavException("Bad WAV header")

        val data = dataChunk!!
        val totalFrames = data.size / (blockAlign.coerceAtLeast(bytesPerSample * channels))
        if (totalFrames <= 0) return DecodedAudio(FloatArray(0), sampleRate)

        val out = FloatArray(totalFrames)
        var pos = 0
        for (frame in 0 until totalFrames) {
            var sum = 0.0
            for (ch in 0 until channels) {
                var v = 0
                when (bytesPerSample) {
                    1 -> v = (data[pos].toInt() and 0xFF) - 128
                    2 -> {
                        val b0 = data[pos].toInt() and 0xFF
                        val b1 = data[pos + 1].toInt() and 0xFF
                        v = (b1 shl 8) or b0
                        if (v >= 0x8000) v -= 0x10000
                    }
                    3 -> {
                        val b0 = data[pos].toInt() and 0xFF
                        val b1 = data[pos + 1].toInt() and 0xFF
                        val b2 = data[pos + 2].toInt() and 0xFF
                        v = (b2 shl 16) or (b1 shl 8) or b0
                        if (v >= 0x800000) v -= 0x1000000
                    }
                    else -> {
                        val b0 = data[pos].toInt() and 0xFF
                        val b1 = data[pos + 1].toInt() and 0xFF
                        val b2 = data[pos + 2].toInt() and 0xFF
                        val b3 = data[pos + 3].toInt() and 0xFF
                        v = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                    }
                }
                pos += bytesPerSample
                sum += if (audioFormat == 3) {
                    if (bytesPerSample == 4) Float.fromBits(v).toDouble()
                    else 0.0
                } else {
                    when (bytesPerSample) {
                        1 -> v / 128.0
                        2 -> v / 32768.0
                        3 -> v / 8388608.0
                        else -> v / 2147483648.0
                    }
                }
            }
            out[frame] = (sum / channels).toFloat()
        }
        return DecodedAudio(out, sampleRate)
    } finally {
        input.close()
    }
}

/** Linear resample to a target sample rate (identity when rates match). */
fun resampleLinear(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
    if (srcRate == dstRate || src.isEmpty()) return src
    val ratio = dstRate.toDouble() / srcRate.toDouble()
    val outLen = (src.size * ratio).toInt().coerceAtLeast(1)
    val out = FloatArray(outLen)
    for (i in 0 until outLen) {
        val pos = i / ratio
        val i0 = pos.toInt().coerceAtMost(src.size - 1)
        val i1 = (i0 + 1).coerceAtMost(src.size - 1)
        val frac = (pos - i0).toFloat()
        out[i] = src[i0] + (src[i1] - src[i0]) * frac
    }
    return out
}

/** Convenience: write a whole mono buffer to a 16-bit WAV file. */
fun writeMono16Wav(file: File, samples: FloatArray, sampleRate: Int) {
    val writer = WavFileWriter(file, sampleRate, 1, 16)
    try {
        writer.writeMono16(samples, 0, samples.size)
        writer.close()
    } catch (e: Exception) {
        writer.abort()
        throw e
    }
}

/**
 * Streaming WAV writer — used by the recorder and the offline mixdown
 * renderer. Writes PCM 16 or 24 bit, mono or stereo.
 */
class WavFileWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
) {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val bytesPerSample = bitsPerSample / 8
    private var framesWritten: Long = 0

    init {
        require(channels in 1..2) { "mono or stereo only" }
        require(bytesPerSample == 2 || bytesPerSample == 3) { "16 or 24 bit PCM" }
        val header = ByteArray(44)
        writeAscii(header, 0, "RIFF")
        writeLeInt(header, 4, 36) // placeholder
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeLeInt(header, 16, 16)
        writeLeShort(header, 20, 1) // PCM
        writeLeShort(header, 22, channels)
        writeLeInt(header, 24, sampleRate)
        writeLeInt(header, 28, sampleRate * channels * bytesPerSample)
        writeLeShort(header, 32, channels * bytesPerSample)
        writeLeShort(header, 34, bitsPerSample)
        writeAscii(header, 36, "data")
        writeLeInt(header, 40, 0) // placeholder
        out.write(header)
    }

    fun writeMono16(samples: FloatArray, from: Int, count: Int) {
        require(channels == 1 && bytesPerSample == 2)
        val buf = ByteArray(count * 2)
        var p = 0
        for (i in 0 until count) {
            val s = samples[from + i].coerceIn(-1f, 1f)
            val v = (s * 32767f).toInt()
            buf[p++] = (v and 0xFF).toByte()
            buf[p++] = ((v shr 8) and 0xFF).toByte()
        }
        out.write(buf)
        framesWritten += count
    }

    fun writeStereo16(left: FloatArray, right: FloatArray, from: Int, count: Int) {
        require(channels == 2 && bytesPerSample == 2)
        val buf = ByteArray(count * 4)
        var p = 0
        for (i in 0 until count) {
            val sl = left[from + i].coerceIn(-1f, 1f)
            val sr = right[from + i].coerceIn(-1f, 1f)
            val vl = (sl * 32767f).toInt()
            val vr = (sr * 32767f).toInt()
            buf[p++] = (vl and 0xFF).toByte()
            buf[p++] = ((vl shr 8) and 0xFF).toByte()
            buf[p++] = (vr and 0xFF).toByte()
            buf[p++] = ((vr shr 8) and 0xFF).toByte()
        }
        out.write(buf)
        framesWritten += count
    }

    fun writeStereo24(left: FloatArray, right: FloatArray, from: Int, count: Int) {
        require(channels == 2 && bytesPerSample == 3)
        val buf = ByteArray(count * 6)
        var p = 0
        for (i in 0 until count) {
            val sl = (left[from + i].coerceIn(-1f, 1f) * 8388607f).toInt()
            val sr = (right[from + i].coerceIn(-1f, 1f) * 8388607f).toInt()
            buf[p++] = (sl and 0xFF).toByte()
            buf[p++] = ((sl shr 8) and 0xFF).toByte()
            buf[p++] = ((sl shr 16) and 0xFF).toByte()
            buf[p++] = (sr and 0xFF).toByte()
            buf[p++] = ((sr shr 8) and 0xFF).toByte()
            buf[p++] = ((sr shr 16) and 0xFF).toByte()
        }
        out.write(buf)
        framesWritten += count
    }

    fun close() {
        out.flush()
        out.close()
        val dataBytes = framesWritten * channels * bytesPerSample
        val raf = RandomAccessFile(file, "rw")
        raf.seek(4)
        writeLeIntTo(raf, (36 + dataBytes).toInt())
        raf.seek(40)
        writeLeIntTo(raf, dataBytes.toInt())
        raf.close()
    }

    fun abort() {
        try {
            out.close()
        } catch (_: Exception) {
        }
        file.delete()
    }

    private companion object {
        fun writeAscii(b: ByteArray, off: Int, s: String) {
            for (i in s.indices) b[off + i] = s[i].code.toByte()
        }

        fun writeLeShort(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        fun writeLeInt(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xFF).toByte()
            b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte()
            b[off + 3] = ((v shr 24) and 0xFF).toByte()
        }

        fun writeLeIntTo(raf: RandomAccessFile, v: Int) {
            raf.write(v and 0xFF)
            raf.write((v shr 8) and 0xFF)
            raf.write((v shr 16) and 0xFF)
            raf.write((v shr 24) and 0xFF)
        }
    }
}

/** Tiny little-endian reader with position tracking. */
private class LittleEndianReader(private val input: BufferedInputStream) {
    var pos: Int = 0
        private set

    fun readString(n: Int): String {
        val b = ByteArray(n)
        readBytes(b)
        var end = 0
        while (end < n && b[end] != 0.toByte()) end++
        return String(b, 0, end, Charsets.US_ASCII)
    }

    fun readInt(): Int {
        val b = ByteArray(4)
        readBytes(b)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    fun readShort(): Int {
        val b = ByteArray(2)
        readBytes(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    fun readBytes(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) throw WavException("Unexpected end of file")
            off += n
        }
        pos += b.size
    }

    fun skip(n: Int) {
        if (n <= 0) return
        val skipped = input.skip(n.toLong())
        if (skipped < n) throw WavException("Unexpected end of file")
        pos += n
    }

    fun skipTo(target: Int) {
        if (target > pos) skip(target - pos)
    }
}
