package io.github.momonekomata.sidea.audio

import io.github.momonekomata.sidea.sources.RandomAccess
import io.github.momonekomata.sidea.sources.ascii
import io.github.momonekomata.sidea.sources.readFully
import io.github.momonekomata.sidea.sources.readUpTo
import io.github.momonekomata.sidea.sources.u32be
import io.github.momonekomata.sidea.sources.u32le
import java.io.IOException
import kotlin.math.pow

/**
 * Uncompressed WAV (incl. WAVE_FORMAT_EXTENSIBLE and float), AIFF and AIFF-C (NONE, sowt,
 * fl32/fl64, in24, in32), read directly: Android's extractor turns some of these into
 * "audio/unknown". Integer samples come out unchanged in a little-endian container.
 */
class PcmFileStream(private val ra: RandomAccess) {
    val sampleRate: Int
    val channels: Int
    /** Bits actually used, e.g. 20 in a 24-bit container. */
    val validBits: Int
    private val containerBits: Int
    private val isFloat: Boolean
    private val bigEndian: Boolean
    private val dataStart: Long
    private val dataBytes: Long
    private var pos = 0L // bytes into the data

    val format: SampleFormat
    val frameBytes: Int
    val totalFrames: Long get() = dataBytes / frameBytes

    init {
        val h = ra.readFully(0, 12)
        var rate = 0; var ch = 0; var bits = 0; var valid = 0; var float = false; var be = false
        var start = -1L; var len = 0L
        when {
            h.ascii(0, 4) == "RIFF" && h.ascii(8, 4) == "WAVE" -> {
                var p = 12L
                while (start < 0) {
                    val c = ra.readUpTo(p, 8)
                    if (c.size < 8) break
                    val size = c.u32le(4)
                    when (c.ascii(0, 4)) {
                        "fmt " -> {
                            val f = ra.readFully(p + 8, minOf(size, 40).toInt())
                            var tag = (f[0].toInt() and 0xFF) or ((f[1].toInt() and 0xFF) shl 8)
                            ch = (f[2].toInt() and 0xFF) or ((f[3].toInt() and 0xFF) shl 8)
                            rate = f.u32le(4).toInt()
                            bits = (f[14].toInt() and 0xFF) or ((f[15].toInt() and 0xFF) shl 8)
                            valid = bits
                            if (tag == 0xFFFE && f.size >= 26) { // WAVE_FORMAT_EXTENSIBLE
                                valid = ((f[18].toInt() and 0xFF) or ((f[19].toInt() and 0xFF) shl 8)).takeIf { it in 1..bits } ?: bits
                                tag = (f[24].toInt() and 0xFF) or ((f[25].toInt() and 0xFF) shl 8)
                            }
                            float = when (tag) {
                                1 -> false
                                3 -> true
                                else -> throw IOException("Compressed WAV (format $tag) isn't supported")
                            }
                        }
                        "data" -> {
                            start = p + 8
                            // 0xFFFFFFFF or 0 is used by streaming writers: data runs to the end.
                            len = if (size == 0xFFFFFFFFL || size == 0L) (ra.size - start) else size
                        }
                    }
                    p += 8 + size + (size and 1)
                }
            }
            h.ascii(0, 4) == "FORM" && (h.ascii(8, 4) == "AIFF" || h.ascii(8, 4) == "AIFC") -> {
                be = true
                var p = 12L
                while (start < 0) {
                    val c = ra.readUpTo(p, 8)
                    if (c.size < 8) break
                    val size = c.u32be(4)
                    when (c.ascii(0, 4)) {
                        "COMM" -> {
                            val f = ra.readFully(p + 8, minOf(size, 22).toInt())
                            ch = ((f[0].toInt() and 0xFF) shl 8) or (f[1].toInt() and 0xFF)
                            bits = ((f[6].toInt() and 0xFF) shl 8) or (f[7].toInt() and 0xFF)
                            valid = bits
                            rate = extended80(f, 8).toInt()
                            if (size >= 22) {
                                when (f.ascii(18, 4).lowercase()) {
                                    "none", "twos" -> Unit
                                    "sowt" -> be = false
                                    "fl32" -> { float = true; bits = 32 }
                                    "fl64" -> { float = true; bits = 64 }
                                    "in24" -> bits = 24
                                    "in32" -> bits = 32
                                    else -> throw IOException("Compressed AIFF-C isn't supported")
                                }
                                valid = if (float) bits else valid.coerceAtMost(bits)
                            }
                        }
                        "SSND" -> {
                            val offset = ra.readFully(p + 8, 4).u32be(0)
                            start = p + 16 + offset
                            len = size - 8 - offset
                        }
                    }
                    p += 8 + size + (size and 1)
                }
            }
            else -> throw IOException("Not WAV or AIFF")
        }
        if (start < 0 || rate <= 0 || ch <= 0) throw IOException("No audio data found")
        if (bits % 8 != 0 || bits !in 8..64) throw IOException("Unsupported $bits-bit PCM")
        sampleRate = rate; channels = ch; containerBits = bits; isFloat = float; bigEndian = be
        validBits = if (isFloat) 32 else valid.coerceAtMost(bits)
        dataStart = start; dataBytes = len
        frameBytes = channels * bits / 8
        format = when {
            isFloat -> SampleFormat.FLOAT
            bits <= 16 -> SampleFormat.S16
            bits == 24 -> SampleFormat.S24_PACKED
            else -> SampleFormat.S32
        }
    }

    fun seekToFrame(frame: Long) {
        pos = (frame.coerceIn(0, totalFrames)) * frameBytes
    }

    /** Next block of up to [frames] frames in [format], little-endian; null at the end. */
    fun read(frames: Int = 4096): PcmChunk? {
        val left = dataBytes - pos
        if (left < frameBytes) return null
        val n = minOf(frames.toLong(), left / frameBytes).toInt()
        val raw = ra.readUpTo(dataStart + pos, n * frameBytes)
        val got = raw.size / frameBytes
        if (got == 0) return null
        val firstFrame = pos / frameBytes
        pos += got.toLong() * frameBytes
        val out = convert(raw, got * channels)
        return PcmChunk(out, out.size, firstFrame * 1_000_000 / sampleRate)
    }

    private fun convert(raw: ByteArray, samples: Int): ByteArray {
        val inBytes = containerBits / 8
        return when {
            isFloat && containerBits == 64 -> {
                val out = ByteArray(samples * 4)
                for (i in 0 until samples) {
                    var bitsL = 0L
                    for (k in 0 until 8) {
                        val b = raw[i * 8 + (if (bigEndian) 7 - k else k)].toLong() and 0xFF
                        bitsL = bitsL or (b shl (8 * k))
                    }
                    val f = java.lang.Double.longBitsToDouble(bitsL).toFloat()
                    val fb = java.lang.Float.floatToRawIntBits(f)
                    out[i * 4] = fb.toByte(); out[i * 4 + 1] = (fb shr 8).toByte(); out[i * 4 + 2] = (fb shr 16).toByte(); out[i * 4 + 3] = (fb shr 24).toByte()
                }
                out
            }
            containerBits == 8 -> {
                // 8-bit WAV is unsigned, AIFF signed; widen to 16-bit.
                val out = ByteArray(samples * 2)
                for (i in 0 until samples) {
                    val v = if (bigEndian) raw[i].toInt() else (raw[i].toInt() and 0xFF) - 128
                    out[i * 2] = 0; out[i * 2 + 1] = v.toByte()
                }
                out
            }
            !bigEndian -> raw
            else -> {
                val out = ByteArray(samples * inBytes)
                for (i in 0 until samples) for (k in 0 until inBytes) out[i * inBytes + k] = raw[i * inBytes + inBytes - 1 - k]
                out
            }
        }
    }

    fun close() = ra.close()

    companion object {
        fun sniff(head: ByteArray) =
            head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WAVE" ||
                head.ascii(0, 4) == "FORM" && (head.ascii(8, 4) == "AIFF" || head.ascii(8, 4) == "AIFC")

        /** AIFF stores the sample rate as an 80-bit IEEE extended float. */
        fun extended80(b: ByteArray, at: Int): Double {
            val exp = (((b[at].toInt() and 0x7F) shl 8) or (b[at + 1].toInt() and 0xFF)) - 16383
            var mant = 0L
            for (i in 0 until 8) mant = (mant shl 8) or (b[at + 2 + i].toLong() and 0xFF)
            val m = (mant ushr 11).toDouble() / (1L shl 52).toDouble() // top 53 bits, 1.xxx
            return m * 2.0.pow(exp)
        }
    }
}
