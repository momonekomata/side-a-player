package io.github.momonekomata.sidea.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayOnTest {
    private fun song(id: String, artist: String, album: String, year: Int, track: Int, genre: String = "Rock") = LibTrack(
        id = id, source = "s", folder = "/$artist/$album", folderName = album, file = "$id.flac", size = 1, title = id, artist = artist,
        albumArtist = "", album = album, genre = genre, year = year, track = track, disc = null, durationMs = 1000,
        codec = "FLAC", sampleRate = 44_100, bits = 16, channels = 2,
    )

    // 001 has one song; 10-FEET two albums; AKB48 one; a Pop genre beside Rock.
    private val songs = listOf(
        song("001-a", "001", "First", 2020, 1),
        song("10f-old-1", "10-FEET", "Old", 2010, 1),
        song("10f-new-1", "10-FEET", "New", 2022, 1),
        song("10f-new-2", "10-FEET", "New", 2022, 2),
        song("akb-1", "AKB48", "0と1の間", 2015, 1, genre = "Pop"),
        song("akb-2", "AKB48", "0と1の間", 2015, 2, genre = "Pop"),
    )

    /** A Library of [songs], listing the way LibraryDb does (albums by title, an artist's newest first). */
    private val shelf = object : Shelf {
        private fun albumsOf(list: List<LibTrack>) = list.groupBy { it.albumKey }.values.map { s ->
            val t = s.first()
            LibAlbum(t.album, t.albumBy, t.year, s.size, t.id, 44_100, 16, "FLAC")
        }
        override fun artists(f: LibFilter) = songs.groupBy { it.albumBy }.map { (a, s) -> LibArtist(a, s.map { it.album }.distinct().size, s.size, s.first().id) }
        override fun albums(f: LibFilter, artist: String?, genre: String?): List<LibAlbum> {
            val picked = songs.filter { (artist == null || it.albumBy == artist) && (genre == null || it.genre == genre) }
            return if (artist != null) albumsOf(picked).sortedByDescending { it.year } else albumsOf(picked).sortedBy { it.title }
        }
        override fun genres(f: LibFilter) = songs.groupBy { it.genre }.map { (g, s) -> LibGenre(g, s.size, s.map { it.album }.distinct().size) }
        override fun tracks(f: LibFilter, album: String?, source: String?) =
            if (album != null) songs.filter { it.albumKey == album }.sortedBy { it.track } else songs
    }

    private fun albumDrill(artist: String, album: String) = LibDrill.Album(albumKeyOf(artist, album), album, artist)
    private fun ids(r: Pair<List<LibTrack>, LibPlace>?) = r?.first?.map { it.id }

    @Test
    fun anArtistsAlbumIsFollowedByItsNextAlbumThenTheNextArtist() {
        // Artists › 001 › First: 001 has nothing more, so 10-FEET (all of it, newest album first).
        val from001 = LibPlace(LibSegment.ARTISTS, LibFilter(), listOf(LibDrill.Artist("001"), albumDrill("001", "First")))
        val next = PlayOn.next(shelf, from001, random = false)
        assertEquals(listOf("10f-new-1", "10f-new-2", "10f-old-1"), ids(next))
        assertEquals(listOf<LibDrill>(LibDrill.Artist("10-FEET")), next!!.second.path)
        // Artists › 10-FEET › New: its next album, Old, comes before the next artist.
        val fromNew = LibPlace(LibSegment.ARTISTS, LibFilter(), listOf(LibDrill.Artist("10-FEET"), albumDrill("10-FEET", "New")))
        assertEquals(listOf("10f-old-1"), ids(PlayOn.next(shelf, fromNew, random = false)))
        // And after the whole of 10-FEET, AKB48.
        assertEquals(listOf("akb-1", "akb-2"), ids(PlayOn.next(shelf, next.second, random = false)))
    }

    @Test
    fun anAlbumFromTheAlbumsTabIsFollowedByTheNextAlbumThere() {
        // Albums A–Z: 0と1の間 starts with a digit, so it's first (#): 0と1の間 → First → New → Old.
        val fromAkb = LibPlace(LibSegment.ALBUMS, LibFilter(), listOf(albumDrill("AKB48", "0と1の間")))
        assertEquals(listOf("001-a"), ids(PlayOn.next(shelf, fromAkb, random = false)))
        val fromFirst = LibPlace(LibSegment.ALBUMS, LibFilter(), listOf(albumDrill("001", "First")))
        assertEquals(listOf("10f-new-1", "10f-new-2"), ids(PlayOn.next(shelf, fromFirst, random = false)))
        // The last album: nothing after it.
        assertNull(PlayOn.next(shelf, LibPlace(LibSegment.ALBUMS, LibFilter(), listOf(albumDrill("10-FEET", "Old"))), random = false))
    }

    @Test
    fun aGenreGoesOnToTheNextGenre() {
        // Genres A–Z: Pop, Rock. After Pop, all of Rock (its albums A–Z).
        val pop = LibPlace(LibSegment.GENRES, LibFilter(), listOf(LibDrill.Genre("Pop")))
        assertEquals(listOf("001-a", "10f-new-1", "10f-new-2", "10f-old-1"), ids(PlayOn.next(shelf, pop, random = false)))
        // Inside a genre, an album goes on to the genre's next album first.
        val popAlbum = LibPlace(LibSegment.GENRES, LibFilter(), listOf(LibDrill.Genre("Rock"), albumDrill("10-FEET", "New")))
        assertEquals(listOf("10f-old-1"), ids(PlayOn.next(shelf, popAlbum, random = false)))
    }

    @Test
    fun theTracksTabGoesOnAfterTheSongsQueued() {
        // Tracks A–Z: 001-a, 10f-new-1, 10f-new-2, 10f-old-1, akb-1, akb-2. Queued up to 10f-new-2.
        val tracks = LibPlace(LibSegment.TRACKS, LibFilter(), emptyList(), windowEnd = "10f-new-2")
        assertEquals(listOf("10f-old-1", "akb-1", "akb-2"), ids(PlayOn.next(shelf, tracks, random = false)))
        assertNull(PlayOn.next(shelf, tracks.copy(windowEnd = "akb-2"), random = false))
    }

    @Test
    fun randomPicksAnotherOfTheTabsOwn() {
        val from001 = LibPlace(LibSegment.ARTISTS, LibFilter(), listOf(LibDrill.Artist("001")))
        repeat(10) {
            val r = PlayOn.next(shelf, from001, random = true)!!
            assert(r.second.path.single() != LibDrill.Artist("001"))
        }
    }
}
