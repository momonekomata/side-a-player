package io.github.momonekomata.sidea.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GaplessTrimmerTest {
    @Test
    fun `drops priming at the start and padding at the end across chunks`() {
        // Mono 16-bit: 2 bytes per frame. Frames are numbered by their first byte.
        val frames = 20
        val all = ByteArray(frames * 2) { (it / 2).toByte() }
        val t = GaplessTrimmer(frameBytes = 2, delayFrames = 2, paddingFrames = 3)
        val out = java.io.ByteArrayOutputStream()
        for (start in 0 until frames * 2 step 6) {
            val end = minOf(start + 6, all.size)
            t.process(PcmChunk(all.copyOfRange(start, end), end - start, 0))?.let { out.write(it.data, 0, it.size) }
        }
        t.finish()
        assertArrayEquals(all.copyOfRange(2 * 2, (frames - 3) * 2), out.toByteArray())
    }
}
