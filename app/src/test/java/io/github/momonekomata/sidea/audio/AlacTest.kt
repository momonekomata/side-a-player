package io.github.momonekomata.sidea.audio

import io.github.momonekomata.sidea.sources.ByteArrayAccess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** ALAC fixtures were encoded by ffmpeg from the WAV fixtures; decoding must give the WAV back. */
class AlacTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/audio/$name")!!.readBytes()

    private fun drain(next: () -> PcmChunk?): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = next() ?: break
            out.write(c.data, 0, c.size)
        }
        return out.toByteArray()
    }

    private fun wav(name: String) = PcmFileStream(ByteArrayAccess(bytes(name))).let { s -> drain { s.read() } }

    @Test
    fun `16-bit stereo alac decodes bit-exact`() {
        val a = AlacStream(ByteArrayAccess(bytes("tone16_alac.m4a")))
        assertEquals(44_100, a.sampleRate)
        assertEquals(2, a.channels)
        assertEquals(16, a.bitDepth)
        assertArrayEquals(wav("tone16.wav"), drain { a.nextFrame() })
    }

    @Test
    fun `24-bit 96 kHz alac with shifted low bytes decodes bit-exact`() {
        val a = AlacStream(ByteArrayAccess(bytes("noise24_alac.m4a")))
        assertEquals(24, a.bitDepth)
        assertEquals(SampleFormat.S24_PACKED, a.format)
        assertArrayEquals(wav("noise24.wav"), drain { a.nextFrame() })
    }

    @Test
    fun `mono alac decodes bit-exact`() {
        val a = AlacStream(ByteArrayAccess(bytes("mono16_alac.m4a")))
        assertEquals(1, a.channels)
        assertArrayEquals(wav("mono16.wav"), drain { a.nextFrame() })
    }

    @Test
    fun `seeking lands on the right samples`() {
        val expected = wav("tone16.wav")
        val d = StreamDecoder.alac(AlacStream(ByteArrayAccess(bytes("tone16_alac.m4a"))))
        d.seekTo(150_000)
        val frame = 150_000L * 44_100 / 1_000_000
        assertArrayEquals(expected.copyOfRange((frame * 4).toInt(), expected.size), drain { d.read() })
        assertEquals(300_000L, d.durationUs)
    }

    @Test
    fun `aac m4a is left to MediaCodec`() {
        assertThrows(IOException::class.java) { AlacStream(ByteArrayAccess(bytes("aac_short.m4a"))) }
    }
}
