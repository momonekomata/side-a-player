package io.github.momonekomata.sidea.library

import io.github.momonekomata.sidea.audio.Player
import io.github.momonekomata.sidea.sources.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibSegment(val label: String) { ARTISTS("Artists"), ALBUMS("Albums"), TRACKS("Tracks"), GENRES("Genres"), SOURCES("Sources") }

/** Where you've gone into the Library from a tab: an artist, an album, a genre or a source. */
sealed interface LibDrill {
    val title: String
    data class Artist(val name: String) : LibDrill { override val title get() = name }
    data class Album(val key: String, override val title: String, val by: String) : LibDrill
    data class Genre(val name: String) : LibDrill { override val title get() = name }
    data class Source(val id: String, val name: String) : LibDrill { override val title get() = name }
}

/** What's on the Library card now: one of the lists, per what's open. */
data class LibView(
    val artists: List<LibArtist>? = null,
    val albums: List<LibAlbum>? = null,
    val tracks: List<LibTrack>? = null,
    val genres: List<LibGenre>? = null,
    val sources: List<Pair<LibrarySource, Int>>? = null,
    /** The songs are an album's, in disc and track order. */
    val albumOrder: Boolean = false,
)

data class LibraryUi(
    val segment: LibSegment = LibSegment.ARTISTS,
    val filter: LibFilter = LibFilter(),
    val stack: List<LibDrill> = emptyList(),
    val view: LibView = LibView(),
    val totals: Triple<Int, Int, Int> = Triple(0, 0, 0),
    val facets: LibFacets? = null,
)

/** The Library card's state and actions, for the view model. */
@OptIn(FlowPreview::class)
class LibraryModel(private val scope: CoroutineScope) {
    private val _ui = MutableStateFlow(LibraryUi())
    val ui: StateFlow<LibraryUi> = _ui.asStateFlow()
    private val query = MutableStateFlow(LibQuery(LibSegment.ARTISTS, LibFilter(), emptyList()))

    private data class LibQuery(val segment: LibSegment, val filter: LibFilter, val stack: List<LibDrill>)

    init {
        scope.launch {
            // Typing in the search box asks the database a moment after the last key, not per key.
            combine(query.debounce(150), Library.version, Library.sources) { q, _, sources -> q to sources }.collectLatest { (q, sources) ->
                val (view, totals) = withContext(Dispatchers.IO) { load(q, sources) to Library.db.totals() }
                _ui.update { it.copy(view = view, totals = totals) }
            }
        }
    }

    private fun load(q: LibQuery, sources: List<LibrarySource>): LibView {
        val db = Library.db
        val f = q.filter
        return when (val top = q.stack.lastOrNull()) {
            is LibDrill.Artist -> LibView(albums = db.albums(f, artist = top.name))
            is LibDrill.Album -> LibView(tracks = db.tracks(f, album = top.key), albumOrder = true)
            is LibDrill.Genre -> LibView(albums = db.albums(f, genre = top.name))
            is LibDrill.Source -> LibView(tracks = db.tracks(f, source = top.id))
            null -> when (q.segment) {
                LibSegment.ARTISTS -> LibView(artists = db.artists(f))
                LibSegment.ALBUMS -> LibView(albums = db.albums(f))
                LibSegment.TRACKS -> LibView(tracks = db.tracks(f))
                LibSegment.GENRES -> LibView(genres = db.genres(f))
                LibSegment.SOURCES -> {
                    val counts = db.sourceCounts(f)
                    LibView(sources = sources.map { it to (counts[it.id] ?: 0) })
                }
            }
        }
    }

    private fun set(f: (LibraryUi) -> LibraryUi) {
        val u = f(_ui.value)
        _ui.value = u
        query.value = LibQuery(u.segment, u.filter, u.stack)
    }

    fun segment(s: LibSegment) = set { it.copy(segment = s, stack = emptyList()) }
    fun search(text: String) = set { it.copy(filter = it.filter.copy(query = text)) }
    fun filter(f: LibFilter) = set { it.copy(filter = f.copy(query = it.filter.query)) }
    fun open(d: LibDrill) = set { it.copy(stack = it.stack + d) }

    /** False at the top, so Back can leave the card. */
    fun back(): Boolean {
        if (_ui.value.stack.isEmpty()) return false
        set { it.copy(stack = it.stack.dropLast(1)) }
        return true
    }

    /** What the filter sheet can offer, read when it opens. */
    fun loadFacets() = scope.launch {
        val f = withContext(Dispatchers.IO) { Library.db.facets() }
        _ui.update { it.copy(facets = f) }
    }

    /**
     * Plays [list] from [index]: the songs around it (up to a thousand) become the queue, so a
     * big Tracks tab doesn't turn into a 50,000-song queue.
     */
    fun play(list: List<LibTrack>, index: Int, shuffle: Boolean = false) = scope.launch {
        val from = if (list.size > 1000) (index - 200).coerceAtLeast(0) else 0
        val window = list.subList(from, minOf(list.size, from + 1000))
        val tracks = withContext(Dispatchers.IO) { window.mapNotNull(Library::trackOf) }
        if (tracks.isEmpty()) return@launch
        if (shuffle) {
            Player.playQueue(tracks.shuffled(), 0)
        } else {
            val want = list.getOrNull(index)?.id
            Player.playQueue(tracks, tracks.indexOfFirst { it.id == want }.coerceAtLeast(0))
        }
    }

    /** Every song by [artist], or in [genre], in album order, for Play from an artist or genre. */
    fun playAll(artist: String? = null, genre: String? = null, shuffle: Boolean = false) = scope.launch {
        val f = _ui.value.filter
        val albums = withContext(Dispatchers.IO) { Library.db.albums(f, artist = artist, genre = genre) }
        val songs = withContext(Dispatchers.IO) { albums.flatMap { Library.db.tracks(f, album = it.key) } }
        play(songs, 0, shuffle)
    }

    fun trackOf(t: LibTrack): Track? = Library.trackOf(t)
}
