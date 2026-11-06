package io.github.momonekomata.sidea.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FormatProbeTest {
    private fun file(name: String) = File(javaClass.classLoader!!.getResource("audio/$name")!!.toURI())
    private fun probe(name: String) = openFileAccess(file(name)).use { FormatProbe.probe(it, name)!! }
    private fun tags(name: String) = openFileAccess(file(name)).use { TagReader.read(it)!! }

    private fun check(name: String, codec: String, rate: Int, bits: Int?, channels: Int, seconds: Double) {
        val f = probe(name)
        assertEquals(name, codec, f.codec)
        assertEquals(name, rate, f.sampleRate)
        assertEquals(name, bits, f.bits)
        assertEquals(name, channels, f.channels)
        if (seconds > 0) assertEquals(name, seconds * 1000, f.durationMs.toDouble(), 80.0)
    }

    @Test
    fun formats() {
        check("lib_96k24.flac", "FLAC", 96_000, 24, 2, 0.5)
        check("lib_96k24_alac.m4a", "ALAC", 96_000, 24, 2, 0.5)
        check("lib_48k_aac.m4a", "AAC", 48_000, null, 2, 1.5)
        check("lib_48k24.wav", "WAV", 48_000, 24, 2, 0.5)
        check("lib_44k16.aiff", "AIFF", 44_100, 16, 1, 0.5)
        check("lib_44k.mp3", "MP3", 44_100, null, 2, 1.5)
        check("lib_44k.ogg", "Vorbis", 44_100, null, 2, 0.0)
        check("lib_48k.opus", "Opus", 48_000, null, 2, 0.0)
        assertTrue(probe("lib_96k24.flac").lossless)
        assertTrue(!probe("lib_44k.mp3").lossless)
    }

    @Test
    fun dsfHeader() {
        // A DSD64 stereo DSF header for 10 seconds: "DSD " chunk, then "fmt ".
        val b = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN)
        b.put("DSD ".toByteArray()).putLong(28).putLong(92).putLong(0)
        b.put("fmt ".toByteArray()).putLong(52).putInt(1).putInt(0).putInt(2).putInt(2).putInt(2_822_400).putInt(1).putLong(28_224_000).putInt(4096).putInt(0)
        val f = File.createTempFile("dsd", ".dsf").apply { writeBytes(b.array()) }
        val p = openFileAccess(f).use { FormatProbe.probe(it, "x.dsf")!! }
        assertEquals("DSD", p.codec)
        assertEquals(64, p.dsdRate)
        assertEquals(1, p.bits)
        assertEquals(10_000, p.durationMs)
    }

    @Test
    fun genreYearAndDiscFromEveryTagKind() {
        for (name in listOf("lib_96k24.flac", "lib_96k24_alac.m4a", "lib_48k_aac.m4a", "lib_44k.mp3", "lib_44k.ogg", "lib_44k16.aiff")) {
            val t = tags(name)
            assertEquals(name, "Probe", t.title)
            assertEquals(name, "Jazz", t.genre)
            assertEquals(name, 1997, t.year)
            assertEquals(name, 4, t.trackNumber)
            assertEquals(name, 2, t.discNumber)
        }
    }

    @Test
    fun id3GenreNumbersAndYears() {
        assertEquals("Rock", genreOf("(17)"))
        assertEquals("Rock", genreOf("17"))
        assertEquals("Shoegaze", genreOf("(17)Shoegaze"))
        assertEquals("Jazz", genreOf("Jazz\u0000Blues"))
        assertEquals(2011, yearOf("2011-05-03"))
        assertNull(yearOf("n/a"))
    }
}
