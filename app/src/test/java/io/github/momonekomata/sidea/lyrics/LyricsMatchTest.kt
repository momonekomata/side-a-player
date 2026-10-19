package io.github.momonekomata.sidea.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsMatchTest {
    @Test
    fun `streaming title decorations are removed`() {
        assertEquals("Somebody That I Used to Know", LyricsMatch.cleanTitle("Somebody That I Used to Know (feat. Kimbra)"))
        assertEquals("Here Comes the Sun", LyricsMatch.cleanTitle("Here Comes the Sun - Remastered 2009"))
        assertEquals("Here Comes the Sun", LyricsMatch.cleanTitle("Here Comes the Sun - 2019 Mix"))
        assertEquals("Wonderwall", LyricsMatch.cleanTitle("Wonderwall [Remastered]"))
        assertEquals("Song", LyricsMatch.cleanTitle("Song (Live) - Radio Edit"))
        assertEquals("ไว้ใจ", LyricsMatch.cleanTitle("ไว้ใจ"))
        // Parentheses that are part of the name stay.
        assertEquals("(I Can't Get No) Satisfaction", LyricsMatch.cleanTitle("(I Can't Get No) Satisfaction"))
    }

    @Test
    fun `lead artist`() {
        assertEquals("Gotye", LyricsMatch.primaryArtist("Gotye, Kimbra"))
        assertEquals("Simon", LyricsMatch.primaryArtist("Simon & Garfunkel"))
        assertEquals("Calvin Harris", LyricsMatch.primaryArtist("Calvin Harris feat. Rihanna"))
        assertEquals("AC/DC", LyricsMatch.primaryArtist("AC/DC"))
    }

    @Test
    fun `variants run from exact to loose without repeats`() {
        assertEquals(
            listOf("Song (feat. B)" to "A, B", "Song" to "A, B", "Song" to "A"),
            LyricsMatch.variants("Song (feat. B)", "A, B"),
        )
        assertEquals(listOf("Song" to "A"), LyricsMatch.variants("Song", "A"))
    }
}
