package io.github.momonekomata.sidea.audio

import io.github.momonekomata.sidea.sources.ByteArrayAccess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixtures were made with ffmpeg from tone16.wav / noise24.wav; FLAC must give the WAV's samples back exactly. */
class LosslessDecoderTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/audio/$name")!!.readBytes()

    /** The PCM payload of one of our WAV fixtures. */
    private fun wavData(name: String): ByteArray {
        val s = PcmFileStream(ByteArrayAccess(bytes(name)))
        return drain { s.read() }
    }

    private fun drain(next: () -> PcmChunk?): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = next() ?: break
            out.write(c.data, 0, c.size)
        }
        return out.toByteArray()
    }

    @Test
    fun `16-bit flac in every stereo mode decodes bit-exact`() {
        val expected = wavData("tone16.wav")
        for (f in listOf("tone16_indep", "tone16_left_side", "tone16_right_side", "tone16_mid_side", "tone16_fixed")) {
            val s = FlacStream(ByteArrayAccess(bytes("$f.flac")))
            assertEquals(f, SampleFormat.S16, s.format)
            assertEquals(44_100, s.sampleRate)
            assertArrayEquals(f, expected, drain { s.nextFrame() })
        }
    }

    @Test
    fun `24-bit 96 kHz flac decodes bit-exact`() {
        val expected = wavData("noise24.wav")
        val s = FlacStream(ByteArrayAccess(bytes("noise24.flac")))
        assertEquals(24, s.bitsPerSample)
        assertEquals(SampleFormat.S24_PACKED, s.format)
        assertEquals(96_000, s.sampleRate)
        assertArrayEquals(expected, drain { s.nextFrame() })
    }

    @Test
    fun `flac behind an id3 tag still plays`() {
        val s = FlacStream(ByteArrayAccess(bytes("id3_tone16.flac")))
        assertArrayEquals(wavData("tone16.wav"), drain { s.nextFrame() })
    }

    /** Turns the SEEKTABLE block into PADDING so seeking has to bisect. */
    private fun withoutSeekTable(b: ByteArray): ByteArray {
        val out = b.copyOf()
        var p = 4
        while (true) {
            val last = out[p].toInt() and 0x80
            val type = out[p].toInt() and 0x7F
            val len = ((out[p + 1].toInt() and 0xFF) shl 16) or ((out[p + 2].toInt() and 0xFF) shl 8) or (out[p + 3].toInt() and 0xFF)
            if (type == 3) out[p] = (last or 1).toByte()
            if (last != 0) return out
            p += 4 + len
        }
    }

    @Test
    fun `seeking lands on the right samples with and without a seek table`() {
        val expected = wavData("tone16.wav")
        val frameBytes = 4
        val targetUs = 170_000L
        val targetFrame = targetUs * 44_100 / 1_000_000
        for (file in listOf(bytes("tone16_mid_side.flac"), withoutSeekTable(bytes("tone16_mid_side.flac")))) {
            val f = FlacStream(ByteArrayAccess(file))
            val d = StreamDecoder.flac(f)
            d.seekTo(targetUs)
            val got = drain { d.read() }
            val want = expected.copyOfRange((targetFrame * frameBytes).toInt(), expected.size)
            assertEquals(want.size, got.size)
            assertArrayEquals(want, got)
        }
    }

    @Test
    fun `aiff is byte-swapped to the same samples`() {
        val aiff = PcmFileStream(ByteArrayAccess(bytes("tone16.aiff")))
        assertEquals(44_100, aiff.sampleRate)
        assertArrayEquals(wavData("tone16.wav"), drain { aiff.read() })
    }

    @Test
    fun `float wav comes out as float matching the 16-bit source`() {
        val f = PcmFileStream(ByteArrayAccess(bytes("tone_f32.wav")))
        assertEquals(SampleFormat.FLOAT, f.format)
        val floats = ByteBuffer.wrap(drain { f.read() }).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val ints = ByteBuffer.wrap(wavData("tone16.wav")).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(ints.remaining(), floats.remaining())
        for (i in 0 until ints.remaining()) assertEquals(ints.get(i) / 32768f, floats.get(i), 1e-6f)
    }

    @Test
    fun `24-bit wav header reads right`() {
        val w = PcmFileStream(ByteArrayAccess(bytes("noise24.wav")))
        assertEquals(SampleFormat.S24_PACKED, w.format)
        assertEquals(24, w.validBits)
        assertEquals(96_000, w.sampleRate)
        assertEquals(11_520L, w.totalFrames)
    }
}
