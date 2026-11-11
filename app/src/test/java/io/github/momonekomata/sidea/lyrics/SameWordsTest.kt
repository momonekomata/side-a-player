package io.github.momonekomata.sidea.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameWordsTest {
    private val ownPlain = listOf(
        "Walking down the river road tonight",
        "Every lantern burning bright",
        "Hold the morning in your hand",
        "Paper boats across the sand",
    )

    @Test
    fun theSameLyricsTimedOnlineMatch() {
        // Another spelling, a line missing, case and punctuation: the same song.
        val online = listOf("Walkin' down the river road tonight,", "Every lantern burning bright!", "Hold the morning in your hand")
        assertTrue(LyricsMatch.sameWords(ownPlain, online))
    }

    @Test
    fun anotherSongIsNoMatchEvenWithTheCommonWords() {
        val other = listOf("You and I were never in the city", "I keep the window open for the night", "Tell me what you want and I will stay")
        assertFalse(LyricsMatch.sameWords(ownPlain, other))
    }

    @Test
    fun writingWithoutSpacesIsComparedByLetters() {
        val own = listOf("夜空に光る星をかぞえて", "君の名前を呼んでいた")
        assertTrue(LyricsMatch.sameWords(own, listOf("夜空に光る星を数えて", "君の名前を呼んでいた")))
        assertFalse(LyricsMatch.sameWords(own, listOf("海辺の街で朝を待つ", "風が窓をたたいている")))
    }
}
