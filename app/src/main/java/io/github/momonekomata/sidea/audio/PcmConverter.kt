package io.github.momonekomata.sidea.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/** Linear PCM layouts the engine moves between. All are little-endian, interleaved. */
enum class SampleFormat(val bytesPerSample: Int, val androidEncoding: Int, val label: String) {
    S16(2, AudioFormat.ENCODING_PCM_16BIT, "16-bit"),
    S24_PACKED(3, AudioFormat.ENCODING_PCM_24BIT_PACKED, "24-bit"),
    S32(4, AudioFormat.ENCODING_PCM_32BIT, "32-bit"),
    FLOAT(4, AudioFormat.ENCODING_PCM_FLOAT, "32-bit float");

    companion object {
        fun fromAndroidEncoding(encoding: Int): SampleFormat? = entries.firstOrNull { it.androidEncoding == encoding }
    }
}

/**
 * Converts between PCM layouts without touching the audio when the source fits in the target:
 * every sample goes through a left-justified 32-bit integer, so 16- and 24-bit material survives
 * any round trip exactly (a float has a 24-bit significand). Nothing here dithers, resamples or
 * changes volume; that is the point of the bit-perfect path.
 */
object PcmConverter {
    private const val FLOAT_SCALE = 2147483648.0 // 2^31

    fun convert(input: ByteBuffer, from: SampleFormat, to: SampleFormat, out: ByteArray? = null): ByteArray {
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val samples = src.remaining() / from.bytesPerSample
        val size = samples * to.bytesPerSample
        val dst = if (out != null && out.size >= size) out else ByteArray(size)
        if (from == to) {
            src.get(dst, 0, size)
            return dst
        }
        var o = 0
        repeat(samples) {
            val v = readS32(src, from)
            o = writeS32(dst, o, v, to)
        }
        return dst
    }

    fun outputSize(inputBytes: Int, from: SampleFormat, to: SampleFormat): Int =
        inputBytes / from.bytesPerSample * to.bytesPerSample

    /** Reads one sample as a left-justified signed 32-bit value. */
    private fun readS32(b: ByteBuffer, f: SampleFormat): Int = when (f) {
        SampleFormat.S16 -> b.short.toInt() shl 16
        SampleFormat.S24_PACKED -> {
            val lo = b.get().toInt() and 0xFF
            val mid = b.get().toInt() and 0xFF
            val hi = b.get().toInt() // sign-carrying byte
            (hi shl 24) or (mid shl 16) or (lo shl 8)
        }
        SampleFormat.S32 -> b.int
        SampleFormat.FLOAT -> {
            val scaled = (b.float.toDouble() * FLOAT_SCALE).roundToLong()
            scaled.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private fun writeS32(d: ByteArray, at: Int, v: Int, f: SampleFormat): Int {
        var o = at
        when (f) {
            SampleFormat.S16 -> {
                val s = v shr 16
                d[o++] = s.toByte(); d[o++] = (s shr 8).toByte()
            }
            SampleFormat.S24_PACKED -> {
                d[o++] = (v shr 8).toByte(); d[o++] = (v shr 16).toByte(); d[o++] = (v shr 24).toByte()
            }
            SampleFormat.S32 -> {
                d[o++] = v.toByte(); d[o++] = (v shr 8).toByte(); d[o++] = (v shr 16).toByte(); d[o++] = (v shr 24).toByte()
            }
            SampleFormat.FLOAT -> {
                val bits = java.lang.Float.floatToRawIntBits((v / FLOAT_SCALE).toFloat())
                d[o++] = bits.toByte(); d[o++] = (bits shr 8).toByte(); d[o++] = (bits shr 16).toByte(); d[o++] = (bits shr 24).toByte()
            }
        }
        return o
    }
}
