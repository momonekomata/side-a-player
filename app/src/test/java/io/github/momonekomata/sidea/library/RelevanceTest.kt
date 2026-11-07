package io.github.momonekomata.sidea.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelevanceTest {
    @Test fun closerMatchesScoreHigher() {
        val q = "supply"
        val exact = Relevance.score(q, "Supply")
        val prefix = Relevance.score(q, "Supply Chain")
        val word = Relevance.score(q, "Air Supply")
        val inside = Relevance.score(q, "Oversupply")
        val other = Relevance.score(q, "Lost in Love", listOf("Air Supply"))
        val elsewhere = Relevance.score(q, "Track 01")
        assertEquals(Relevance.EXACT, exact)
        assertEquals(Relevance.PREFIX, prefix)
        assertEquals(Relevance.WORD_PREFIX, word)
        assertEquals(Relevance.CONTAINS, inside)
        assertEquals(Relevance.OTHER_FIELD_PREFIX, other)
        assertEquals(Relevance.ELSEWHERE, elsewhere)
        assertTrue(exact > prefix && prefix > word && word > inside && inside > other && other > elsewhere)
    }

    @Test fun wordsInAnyOrder() {
        assertEquals(Relevance.ALL_WORDS_AT_STARTS, Relevance.score("supply air", "Air Supply"))
        assertEquals(Relevance.ALL_WORDS, Relevance.score("upp ai", "Air Supply"))
        assertEquals(Relevance.EXACT, Relevance.score("  AIR   supply ", "Air Supply"))
    }

    @Test fun wordStartAfterPunctuation() {
        assertEquals(Relevance.WORD_PREFIX, Relevance.score("love", "\"Podium\" (Love Songs)"))
    }

    private fun artist(name: String, score: Int) = LibHit(LibSegment.ARTISTS, score, artist = LibArtist(name, 1, 1, "x"))
    private fun genre(name: String, score: Int) = LibHit(LibSegment.GENRES, score, genre = LibGenre(name, 1, 1))
    private fun album(title: String, score: Int) = LibHit(LibSegment.ALBUMS, score, album = LibAlbum(title, "Someone", null, 1, "x", 0, null, "FLAC"))

    @Test fun openTabFirstThenOthersByRelevance() {
        val r = Relevance.arrange(
            LibSegment.ARTISTS,
            mapOf(
                LibSegment.ARTISTS to listOf(artist("Weak Supplies Band", 10), artist("Air Supply", 80)),
                LibSegment.ALBUMS to listOf(album("Supply", 100), album("Greatest", 10)),
                LibSegment.GENRES to listOf(genre("Supply Pop", 90)),
            ),
            othersEach = 25,
        )
        // The open tab's matching name on top; its weak match mixes in with the rest, winning a tie.
        assertEquals(listOf("Air Supply", "Supply", "Supply Pop", "Weak Supplies Band", "Greatest"), r.hits.map { it.name })
        assertEquals(1, r.top)
        assertTrue(r.more.isEmpty())
    }

    @Test fun otherTabsAreCappedWithACountOfTheRest() {
        val many = (1..30).map { genre("Genre $it", 50) }
        val r = Relevance.arrange(LibSegment.ARTISTS, mapOf(LibSegment.GENRES to many), othersEach = 25)
        assertEquals(25, r.hits.size)
        assertEquals(listOf(LibSegment.GENRES to 5), r.more)
    }

    @Test fun tiesGoToTheShorterName() {
        val r = Relevance.arrange(LibSegment.ARTISTS, mapOf(LibSegment.ARTISTS to listOf(artist("Air Supply Tribute", 80), artist("Air Supply", 80))), 25)
        assertEquals("Air Supply", r.hits.first().name)
    }
}
