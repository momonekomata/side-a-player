package io.github.momonekomata.sidea.library

/**
 * Where in the Library a queue was picked: the tab, Focus's filters (and the list shown:
 * Favourites, a playlist), and what was opened in it (an artist, then one of its albums…).
 * [windowEnd] is the last song queued from a list of songs (the Tracks tab, a playlist), which
 * may go on past it.
 */
data class LibPlace(val segment: LibSegment, val filter: LibFilter, val path: List<LibDrill>, val windowEnd: String? = null)

/** What [PlayOn] reads of the Library: its lists, as [LibraryDb] gives them. */
interface Shelf {
    fun artists(f: LibFilter): List<LibArtist>
    /** By title (the A–Z order is applied after), or an artist's newest first. */
    fun albums(f: LibFilter, artist: String? = null, genre: String? = null): List<LibAlbum>
    fun genres(f: LibFilter): List<LibGenre>
    /** An album's in track order, else any order (sorted after). */
    fun tracks(f: LibFilter, album: String? = null, source: String? = null): List<LibTrack>
}

/** The Library's database as a [Shelf]. */
class DbShelf(private val db: LibraryDb) : Shelf {
    override fun artists(f: LibFilter) = db.artists(f)
    override fun albums(f: LibFilter, artist: String?, genre: String?) = db.albums(f, artist = artist, genre = genre)
    override fun genres(f: LibFilter) = db.genres(f)
    override fun tracks(f: LibFilter, album: String?, source: String?) = db.tracks(f, album = album, source = source)
}

/**
 * What plays when a queue picked in the Library runs out: what comes after it where it was
 * picked, as the Library lists it. An album opened from an artist is followed by that artist's
 * next album, and after the last one by the next artist; an album from the Albums tab by the next
 * album; the Tracks tab or a playlist by the songs after the ones queued. "Random" picks an
 * artist, album or genre of the same tab at random instead.
 */
object PlayOn {
    /** At most this many songs are queued at once (a big genre, the Tracks tab). */
    private const val WINDOW = 1000

    private fun key(d: LibDrill) = when (d) {
        is LibDrill.Artist -> "ar:" + d.name.lowercase()
        is LibDrill.Album -> "al:" + d.key.lowercase()
        is LibDrill.Genre -> "g:" + d.name.lowercase()
        is LibDrill.Source -> "s:" + d.id
    }

    private fun album(a: LibAlbum) = LibDrill.Album(a.key, a.title, a.by)

    /** The artists, albums or genres listed where [path] is open, in the Library's order; null where songs are listed. */
    fun groupsAt(db: Shelf, segment: LibSegment, f: LibFilter, path: List<LibDrill>): List<LibDrill>? = when (val top = path.lastOrNull()) {
        null -> if (f.list != null) null else when (segment) {
            LibSegment.ARTISTS -> LibOrder.sorted(db.artists(f)) { it.name }.map { LibDrill.Artist(it.name) }
            LibSegment.ALBUMS -> LibOrder.sorted(db.albums(f)) { it.title }.map(::album)
            LibSegment.GENRES -> LibOrder.sorted(db.genres(f)) { it.name }.map { LibDrill.Genre(it.name) }
            LibSegment.TRACKS -> null
        }
        // An artist's albums newest first, as its page lists them; a genre's A–Z.
        is LibDrill.Artist -> db.albums(f, artist = top.name).map(::album)
        is LibDrill.Genre -> LibOrder.sorted(db.albums(f, genre = top.name)) { it.title }.map(::album)
        else -> null
    }

    /** The songs listed where [path] is open, when songs are what's listed there (Tracks, a list, a source). */
    fun songsAt(db: Shelf, segment: LibSegment, f: LibFilter, path: List<LibDrill>): List<LibTrack>? = when (val top = path.lastOrNull()) {
        null -> when {
            f.list != null -> db.tracks(f)
            segment == LibSegment.TRACKS -> LibOrder.sorted(db.tracks(f)) { it.title }
            else -> null
        }
        is LibDrill.Source -> LibOrder.sorted(db.tracks(f, source = top.id)) { it.title }
        else -> null
    }

    /** All of [d]'s songs, in the order its page plays them. */
    fun songsOf(db: Shelf, f: LibFilter, d: LibDrill): List<LibTrack> = when (d) {
        is LibDrill.Artist -> db.albums(f, artist = d.name).flatMap { db.tracks(f, album = it.key) }
        is LibDrill.Album -> db.tracks(f, album = d.key)
        is LibDrill.Genre -> LibOrder.sorted(db.albums(f, genre = d.name)) { it.title }.flatMap { db.tracks(f, album = it.key) }
        is LibDrill.Source -> LibOrder.sorted(db.tracks(f, source = d.id)) { it.title }
    }.take(WINDOW)

    /** What plays after the queue picked at [place] runs out: its songs and its own place; null at the end of the Library's list. */
    fun next(db: Shelf, place: LibPlace, random: Boolean): Pair<List<LibTrack>, LibPlace>? {
        val f = place.filter
        if (random) return randomFrom(db, place)
        // A list of songs: the ones after those queued.
        songsAt(db, place.segment, f, place.path)?.let { list ->
            val end = place.windowEnd?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
            if (end >= 0 && end + 1 < list.size) {
                val window = list.subList(end + 1, minOf(list.size, end + 1 + WINDOW))
                return window to place.copy(windowEnd = window.last().id)
            }
        }
        // Up through what was opened: the next artist, album or genre beside it, at the deepest
        // level that has one (an artist's next album, then the next artist).
        for (level in place.path.indices.reversed()) {
            val parent = place.path.take(level)
            val siblings = groupsAt(db, place.segment, f, parent) ?: continue
            val at = siblings.indexOfFirst { key(it) == key(place.path[level]) }
            if (at < 0) continue
            for (j in at + 1 until siblings.size) {
                val songs = songsOf(db, f, siblings[j])
                if (songs.isNotEmpty()) return songs to LibPlace(place.segment, f, parent + siblings[j])
            }
        }
        return null
    }

    /** An artist, album or genre of the same tab at random (not the one just played); in a list of songs, a stretch of it from anywhere. */
    private fun randomFrom(db: Shelf, place: LibPlace): Pair<List<LibTrack>, LibPlace>? {
        val f = place.filter
        val top = groupsAt(db, place.segment, f, emptyList())
        if (top != null) {
            val now = place.path.firstOrNull()?.let(::key)
            for (pick in top.filter { key(it) != now }.shuffled().take(20)) {
                val songs = songsOf(db, f, pick)
                if (songs.isNotEmpty()) return songs to LibPlace(place.segment, f, listOf(pick))
            }
            return null
        }
        val list = songsAt(db, place.segment, f, emptyList()) ?: return null
        if (list.isEmpty()) return null
        val from = list.indices.random()
        val window = list.subList(from, minOf(list.size, from + WINDOW))
        return window to LibPlace(place.segment, f, emptyList(), window.last().id)
    }
}
