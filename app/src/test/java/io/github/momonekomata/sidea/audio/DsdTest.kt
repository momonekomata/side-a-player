package io.github.momonekomata.sidea.audio

import io.github.momonekomata.sidea.sources.ByteArrayAccess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DsdTest {
    private fun le(size: Int, f: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(f).array()

    /** A stereo DSF whose left channel holds [left] and right channel [right], LSB-first. */
    private fun dsf(left: ByteArray, right: ByteArray): ByteArray {
        val block = 4096
        val blocks = (left.size + block - 1) / block
        val data = ByteArray(blocks * block * 2)
        for (b in 0 until blocks) {
            for (i in 0 until block) {
                val k = b * block + i
                if (k < left.size) data[(b * 2) * block + i] = left[k]
                if (k < right.size) data[(b * 2 + 1) * block + i] = right[k]
            }
        }
        val header = le(28) { put("DSD ".toByteArray()); putLong(28); putLong(0); putLong(0) }
        val fmt = le(52) {
            put("fmt ".toByteArray()); putLong(52); putInt(1); putInt(0); putInt(2); putInt(2)
            putInt(2_822_400); putInt(1); putLong(left.size * 8L); putInt(block); putInt(0)
        }
        val dataHdr = le(12) { put("data".toByteArray()); putLong(12L + data.size) }
        return header + fmt + dataHdr + data
    }

    @Test
    fun `dsf blocks are deinterleaved and bit-reversed`() {
        val left = ByteArray(5000) { 0x01 }
        val right = ByteArray(5000) { 0x0F }
        val s = DsdStream.open(ByteArrayAccess(dsf(left, right)))
        assertEquals(2, s.channels)
        assertEquals(5000L, s.bytesPerChannel)
        val out = arrayOf(ByteArray(4096), ByteArray(4096))
        var total = 0
        while (true) {
            val n = s.read(out, 4096)
            if (n == 0) break
            for (i in 0 until n) {
                assertEquals(0x80.toByte(), out[0][i])
                assertEquals(0xF0.toByte(), out[1][i])
            }
            total += n
        }
        assertEquals(5000, total) // the zero padding in the last block is not played
    }

    @Test
    fun `dop alternates markers and keeps bit order`() {
        val p = DopPacker(1)
        val out = ByteArray(9)
        val n = p.process(arrayOf(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)), 6, out)
        assertEquals(9, n)
        assertArrayEquals(byteArrayOf(0x22, 0x11, 0x05, 0x44, 0x33, 0xFA.toByte(), 0x66, 0x55, 0x05), out)
    }

    /** First-order delta-sigma modulation of a sine: what a DSD recorder would produce. */
    private fun dsdSine(freq: Double, amp: Double, bytes: Int, rate: Int): ByteArray {
        val out = ByteArray(bytes)
        var integ = 0.0
        var fb = 0.0
        for (i in 0 until bytes * 8) {
            val x = amp * sin(2 * PI * freq * i / rate)
            integ += x - fb
            val bit = integ >= 0
            fb = if (bit) 1.0 else -1.0
            if (bit) out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        }
        return out
    }

    @Test
    fun `dsd64 sine converts to pcm at the right level`() {
        val rate = 2_822_400
        val bytes = rate / 8 / 10 // 100 ms
        val dsd = dsdSine(1000.0, 0.5, bytes, rate)
        val conv = DsdToPcm(rate, 1)
        assertEquals(176_400, conv.outputRate)
        val out = ByteArray(conv.outputBytesFor(bytes))
        val n = conv.process(arrayOf(dsd), bytes, out)
        val samples = ByteBuffer.wrap(out, 0, n).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val pcm = FloatArray(samples.remaining()).also { samples.get(it) }
        val settled = pcm.drop(2000) // past the filter's start-up
        val rms = sqrt(settled.sumOf { (it * it).toDouble() } / settled.size)
        assertEquals(0.5 / sqrt(2.0), rms, 0.01)
        // DC from the modulator's idle tones and the filter stays negligible.
        assertEquals(0.0, settled.average(), 0.01)
        assertTrue(settled.all { abs(it) < 0.6f })
    }
}
