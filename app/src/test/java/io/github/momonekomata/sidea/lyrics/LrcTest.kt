package io.github.momonekomata.sidea.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    @Test
    fun `timed lines sort and repeated stamps expand`() {
        val l = Lrc.parse(
            """
            [ti:Song]
            [ar:Someone]
            [00:12.50]second
            [00:01.00][00:30.25]chorus
            [01:02.123]<00:01.00>word <00:01.50>stamps
            """.trimIndent(),
            "test",
        )!!
        assertTrue(l.synced)
        assertEquals(listOf(1000L, 12500L, 30250L, 62123L), l.lines.map { it.timeMs })
        assertEquals(listOf("chorus", "second", "chorus", "word stamps"), l.lines.map { it.text })
    }

    @Test
    fun `offset moves lines earlier`() {
        val l = Lrc.parse("[offset:+500]\n[00:02.00]a\n[00:00.20]b", "t")!!
        assertEquals(listOf(0L, 1500L), l.lines.map { it.timeMs })
    }

    @Test
    fun `line lookup`() {
        val l = Lrc.parse("[00:01.00]a\n[00:02.00]b\n[00:03.00]c", "t")!!
        assertEquals(-1, l.lineAt(500))
        assertEquals(0, l.lineAt(1000))
        assertEquals(1, l.lineAt(2999))
        assertEquals(2, l.lineAt(99_000))
    }

    @Test
    fun `plain lyrics stay unsynced`() {
        val l = Lrc.parse("\nFirst line\n\nSecond line\n", "t")!!
        assertFalse(l.synced)
        assertEquals(listOf("First line", "", "Second line"), l.lines.map { it.text })
        assertEquals(-1, l.lineAt(5000))
    }

    @Test
    fun formattedLrcReadsBackTheSame() {
        val l = Lrc.parse("[00:12.30]Hello\n[01:05.07]World\n[61:00.00]Late", "LRCLIB")!!
        val text = Lrc.format(l, "Song", "Band", "Album")
        assertTrue(text.startsWith("[ar:Band]\n[ti:Song]\n[al:Album]\n"))
        val back = Lrc.parse(text, ".lrc file")!!
        assertTrue(back.synced)
        assertEquals(l.lines, back.lines)
    }

    @Test
    fun plainLyricsStayPlain() {
        val l = Lrc.parse("One\nTwo", "lyrics.ovh")!!
        val back = Lrc.parse(Lrc.format(l, "", "", ""), ".lrc file")!!
        assertFalse(back.synced)
        assertEquals(listOf("One", "Two"), back.lines.map { it.text })
    }

    @Test
    fun enhancedLrcKeepsWordTimesAndFormatsThemBack() {
        val l = Lrc.parse("[00:10.00]<00:10.00>Hello <00:10.50>bright <00:11.20>world<00:12.00>\n[00:13.00]Next", "LRCLIB")!!
        val first = l.lines[0]
        assertEquals("Hello bright world", first.text)
        assertEquals(listOf(WordTime(0, 10_000), WordTime(6, 10_500), WordTime(13, 11_200)), first.words)
        val back = Lrc.parse(Lrc.format(l, "", "", ""), ".lrc file")!!
        assertEquals(l.lines, back.lines)
    }

    @Test
    fun karaokeFollowsWordsWhenThereAreSome() {
        val l = Lrc.parse("[00:10.00]<00:10.00>Hello <00:10.50>bright <00:11.20>world\n[00:13.00]Next", "x")!!
        assertEquals(0f, l.sungChars(0, 9_000), 0.01f)
        assertEquals(3f, l.sungChars(0, 10_250), 0.01f) // half of "Hello "
        assertEquals(6f, l.sungChars(0, 10_500), 0.01f) // "bright" starts
        assertEquals(18f, l.sungChars(0, 13_000), 0.01f) // done
    }

    @Test
    fun karaokeSpreadsALineWithoutWordsOverItsTime() {
        // Japanese: no spaces, so the sweep goes character by character.
        val l = Lrc.parse("[00:20.00]さくらさくら\n[00:23.00]次", "x")!!
        assertEquals(3f, l.sungChars(0, 21_500), 0.01f)
        assertEquals(6f, l.sungChars(0, 23_000), 0.01f)
    }

    @Test
    fun aLongBreakAfterALineDoesntStretchItsSweep() {
        val l = Lrc.parse("[00:10.00]Hi\n[01:00.00]After the solo", "x")!!
        // "Hi" is done within 3 s, not over the 50 s until the next line.
        assertEquals(1f, l.sungChars(0, 11_500), 0.01f)
        assertEquals(2f, l.sungChars(0, 13_100), 0.01f)
    }
}
