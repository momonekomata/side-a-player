package io.github.momonekomata.sidea.library

/**
 * One result of a search across the whole Library: an artist, an album, a song or a genre, with
 * how well it matches. Exactly one of the four is set; [segment] says which tab it belongs to.
 */
data class LibHit(
    val segment: LibSegment,
    val score: Int,
    val artist: LibArtist? = null,
    val album: LibAlbum? = null,
    val track: LibTrack? = null,
    val genre: LibGenre? = null,
) {
    val name get() = artist?.name ?: album?.title ?: track?.title ?: genre?.name.orEmpty()
}

/**
 * A search's results: the open tab's whose names match first ([top] of them), then the rest by
 * relevance (the open tab's weaker matches and the other tabs' best), and how many more each
 * other tab has.
 */
data class LibSearchResult(
    val hits: List<LibHit>,
    val top: Int,
    /** The other tabs' results beyond those shown, per tab. */
    val more: List<Pair<LibSegment, Int>>,
)

/**
 * How well a name matches what was typed. The whole query as the name scores highest, then
 * the name starting with it, a word in the name starting with it, the name containing it, every
 * word somewhere in the name, a match in [also] (an album's artist, a song's artist or album),
 * and last a match only elsewhere (another tag, the file name, the folder).
 */
object Relevance {
    const val EXACT = 100
    const val PREFIX = 90
    const val WORD_PREFIX = 80
    const val CONTAINS = 70
    const val ALL_WORDS_AT_STARTS = 65
    const val ALL_WORDS = 60
    const val OTHER_FIELD_PREFIX = 45
    const val OTHER_FIELD = 40
    const val ELSEWHERE = 10

    fun score(query: String, name: String, also: List<String> = emptyList()): Int {
        val q = normalize(query)
        if (q.isEmpty()) return 0
        val n = normalize(name)
        val words = q.split(' ')
        return when {
            n == q -> EXACT
            n.startsWith(q) -> PREFIX
            atWordStart(n, q) -> WORD_PREFIX
            n.contains(q) -> CONTAINS
            words.size > 1 && words.all { atWordStart(n, it) } -> ALL_WORDS_AT_STARTS
            words.size > 1 && words.all { n.contains(it) } -> ALL_WORDS
            else -> also.map(::normalize).let { others ->
                when {
                    others.any { it == q || it.startsWith(q) || atWordStart(it, q) } -> OTHER_FIELD_PREFIX
                    others.any { o -> words.all { o.contains(it) } } -> OTHER_FIELD
                    else -> ELSEWHERE
                }
            }
        }
    }

    private fun normalize(s: String) = s.lowercase().trim().replace(Regex("\\s+"), " ")

    /** [q] at the start of [n] or right after a space or punctuation. */
    private fun atWordStart(n: String, q: String): Boolean {
        var i = n.indexOf(q)
        while (i >= 0) {
            if (i == 0 || !n[i - 1].isLetterOrDigit()) return true
            i = n.indexOf(q, i + 1)
        }
        return false
    }

    /**
     * Puts [current]'s hits whose names match first, best first. Then everything else best
     * first: [current]'s weaker matches (all of them) and the best [othersEach] of every other
     * tab; a tie goes to the open tab, then the earlier tab. Among equal scores the shorter name
     * goes first (closer to what was typed).
     */
    fun arrange(current: LibSegment, bySegment: Map<LibSegment, List<LibHit>>, othersEach: Int): LibSearchResult {
        val best = compareByDescending<LibHit> { it.score }.thenBy { it.name.length }
        val (first, weaker) = bySegment[current].orEmpty().sortedWith(best).partition { it.score >= ALL_WORDS }
        val more = ArrayList<Pair<LibSegment, Int>>()
        val others = LibSegment.entries.filter { it != current }.flatMap { s ->
            val list = bySegment[s].orEmpty().sortedWith(best)
            if (list.size > othersEach) more += s to (list.size - othersEach)
            list.take(othersEach)
        }
        val rest = (weaker + others).sortedWith(
            compareByDescending<LibHit> { it.score }.thenBy { it.segment != current }.thenBy { it.segment.ordinal }.thenBy { it.name.length },
        )
        return LibSearchResult(first + rest, first.size, more)
    }
}

/** Searches every tab for [f]'s query, the open tab [current] first. */
internal fun LibraryDb.searchAll(f: LibFilter, current: LibSegment, othersEach: Int = 25): LibSearchResult {
    val q = f.query
    // The open tab is shown whole; a big Tracks result from another tab only needs its best.
    val trackLimit = if (current == LibSegment.TRACKS) Int.MAX_VALUE else 5_000
    val bySegment = mapOf(
        LibSegment.ARTISTS to artists(f).map { LibHit(LibSegment.ARTISTS, Relevance.score(q, it.name), artist = it) },
        LibSegment.ALBUMS to albums(f).map { LibHit(LibSegment.ALBUMS, Relevance.score(q, it.title, listOf(it.by)), album = it) },
        LibSegment.TRACKS to tracks(f, limit = trackLimit).map { LibHit(LibSegment.TRACKS, Relevance.score(q, it.title, listOf(it.artist, it.albumBy, it.album)), track = it) },
        LibSegment.GENRES to genres(f).map { LibHit(LibSegment.GENRES, Relevance.score(q, it.name), genre = it) },
    )
    return Relevance.arrange(current, bySegment, othersEach)
}
