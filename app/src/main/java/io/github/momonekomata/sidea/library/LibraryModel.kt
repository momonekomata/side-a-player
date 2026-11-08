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

/** The Library's views. Where the music comes from is in Settings → Music sources. */
enum class LibSegment(val label: String) { ARTISTS("Artists"), ALBUMS("Albums"), TRACKS("Tracks"), GENRES("Genres") }

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
    /** A search from a tab: every tab's results, that tab's first. */
    val search: LibSearchResult? = null,
)

data class LibraryUi(
    val segment: LibSegment = LibSegment.ARTISTS,
    val filter: LibFilter = LibFilter(),
    val stack: List<LibDrill> = emptyList(),
    val view: LibView = LibView(),
    val totals: Triple<Int, Int, Int> = Triple(0, 0, 0),
    val facets: LibFacets? = null,
    /** The [LibraryModel.placeKey] of what [view] holds, so the list can wait for it. */
    val loaded: String? = null,
)

/**
 * The Library card's state and actions, for the view model. Where you were is kept in [prefs]:
 * the view (Artists, Albums…), what was opened in it, and each list's scroll position, so Back
 * and the next start land where you left off.
 */
@OptIn(FlowPreview::class)
class LibraryModel(private val scope: CoroutineScope, private val prefs: android.content.SharedPreferences? = null) {
    private val _ui = MutableStateFlow(restoredUi())
    val ui: StateFlow<LibraryUi> = _ui.asStateFlow()
    private val query = MutableStateFlow(LibQuery(_ui.value.segment, _ui.value.filter, _ui.value.stack))

    private data class LibQuery(val segment: LibSegment, val filter: LibFilter, val stack: List<LibDrill>)

    init {
        scope.launch {
            // Typing in the search box asks the database a moment after the last key, not per key.
            combine(query.debounce(150), Library.version, Library.sources) { q, _, sources -> q to sources }.collectLatest { (q, sources) ->
                val (view, totals) = withContext(Dispatchers.IO) { load(q, sources) to Library.db.totals() }
                _ui.update { it.copy(view = view, totals = totals, loaded = placeKey(q.segment, q.stack, q.filter)) }
            }
        }
    }

    // ---- where you were ----

    /** One list: the view, what's opened in it, and the list or search it's narrowed to. */
    fun placeKey(segment: LibSegment, stack: List<LibDrill>, filter: LibFilter) =
        segment.name + "|" + stack.joinToString("/") { drillKey(it) } + "|" + filter.list.orEmpty() + "|" + filter.query.trim().lowercase()

    private fun drillKey(d: LibDrill) = when (d) {
        is LibDrill.Artist -> "ar:" + d.name
        is LibDrill.Album -> "al:" + d.key
        is LibDrill.Genre -> "g:" + d.name
        is LibDrill.Source -> "s:" + d.id
    }

    private val scroll: MutableMap<String, Pair<Int, Int>> = restoredScroll()
    private var saveJob: kotlinx.coroutines.Job? = null

    private val _gridAlbums = MutableStateFlow(prefs?.getBoolean("lib:grid", true) ?: true)
    /** Albums as a grid of covers (or a list), kept between runs. */
    val gridAlbums: StateFlow<Boolean> = _gridAlbums.asStateFlow()

    fun setGridAlbums(on: Boolean) {
        _gridAlbums.value = on
        prefs?.edit()?.putBoolean("lib:grid", on)?.apply()
    }

    fun scrollFor(key: String) = scroll[key] ?: (0 to 0)

    fun saveScroll(key: String, index: Int, offset: Int) {
        if (scroll[key] == index to offset) return
        scroll.remove(key)
        scroll[key] = index to offset // most recent last
        while (scroll.size > 100) scroll.remove(scroll.keys.first())
        // Scrolling reports every frame; keep it a moment after it stops.
        saveJob?.cancel()
        saveJob = scope.launch {
            kotlinx.coroutines.delay(600)
            savePlace()
        }
    }

    private fun savePlace() {
        val p = prefs ?: return
        val u = _ui.value
        val stack = org.json.JSONArray(u.stack.map { d ->
            when (d) {
                is LibDrill.Artist -> org.json.JSONObject().put("t", "artist").put("name", d.name)
                is LibDrill.Album -> org.json.JSONObject().put("t", "album").put("key", d.key).put("title", d.title).put("by", d.by)
                is LibDrill.Genre -> org.json.JSONObject().put("t", "genre").put("name", d.name)
                is LibDrill.Source -> org.json.JSONObject().put("t", "source").put("id", d.id).put("name", d.name)
            }
        })
        val pos = org.json.JSONObject()
        scroll.forEach { (k, v) -> pos.put(k, org.json.JSONArray().put(v.first).put(v.second)) }
        p.edit().putString("lib:segment", u.segment.name).putString("lib:stack", stack.toString()).putString("lib:scroll", pos.toString()).apply()
    }

    private fun restoredUi(): LibraryUi {
        val p = prefs ?: return LibraryUi()
        val segment = runCatching { LibSegment.valueOf(p.getString("lib:segment", null)!!) }.getOrDefault(LibSegment.ARTISTS)
        val stack = runCatching {
            val a = org.json.JSONArray(p.getString("lib:stack", "[]"))
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                when (o.optString("t")) {
                    "artist" -> LibDrill.Artist(o.getString("name"))
                    "album" -> LibDrill.Album(o.getString("key"), o.getString("title"), o.getString("by"))
                    "genre" -> LibDrill.Genre(o.getString("name"))
                    "source" -> LibDrill.Source(o.getString("id"), o.getString("name"))
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
        return LibraryUi(segment = segment, stack = stack)
    }

    private fun restoredScroll(): MutableMap<String, Pair<Int, Int>> {
        val out = LinkedHashMap<String, Pair<Int, Int>>()
        val raw = prefs?.getString("lib:scroll", null) ?: return out
        runCatching {
            val o = org.json.JSONObject(raw)
            o.keys().forEach { k -> o.getJSONArray(k).let { out[k] = it.getInt(0) to it.getInt(1) } }
        }
        return out
    }

    private fun load(q: LibQuery, sources: List<LibrarySource>): LibView {
        val db = Library.db
        val f = q.filter
        // Searching from a tab looks through the whole Library; inside an artist, an album,
        // Favourites or a playlist it stays inside it.
        if (q.stack.isEmpty() && f.query.isNotBlank() && f.list == null) return LibView(search = db.searchAll(f, q.segment))
        // A–Z lists in the order the letter index files them (LibOrder); an artist's albums stay
        // newest first, an album's songs in track order, a list in its own order.
        fun az(t: List<LibTrack>) = if (f.list != null) t else LibOrder.sorted(t) { it.title }
        fun azAlbums(a: List<LibAlbum>) = LibOrder.sorted(a) { it.title }
        return when (val top = q.stack.lastOrNull()) {
            is LibDrill.Artist -> LibView(albums = db.albums(f, artist = top.name))
            is LibDrill.Album -> LibView(tracks = db.tracks(f, album = top.key), albumOrder = true)
            is LibDrill.Genre -> LibView(albums = azAlbums(db.albums(f, genre = top.name)))
            is LibDrill.Source -> LibView(tracks = az(db.tracks(f, source = top.id)))
            null -> when (q.segment) {
                LibSegment.ARTISTS -> LibView(artists = LibOrder.sorted(db.artists(f)) { it.name })
                LibSegment.ALBUMS -> LibView(albums = azAlbums(db.albums(f)))
                LibSegment.TRACKS -> LibView(tracks = az(db.tracks(f)))
                LibSegment.GENRES -> LibView(genres = LibOrder.sorted(db.genres(f)) { it.name })
            }
        }
    }

    private fun set(f: (LibraryUi) -> LibraryUi) {
        val u = f(_ui.value)
        _ui.value = u
        query.value = LibQuery(u.segment, u.filter, u.stack)
        savePlace()
    }

    /** A view's tab: it leaves Favourites or a playlist, like any other tab. */
    fun segment(s: LibSegment) = set { it.copy(segment = s, stack = emptyList(), filter = it.filter.copy(list = null)) }

    /** The Favourites or Playlists tab: [id]'s songs, in the list's own order. */
    fun showList(id: String) = set { it.copy(segment = LibSegment.TRACKS, stack = emptyList(), filter = it.filter.copy(list = id)) }
    fun search(text: String) = set { it.copy(filter = it.filter.copy(query = text)) }
    /** Focus's filters. The search and the list being shown (Favourites, a playlist) stay. */
    fun filter(f: LibFilter) = set { it.copy(filter = f.copy(query = it.filter.query, list = it.filter.list)) }

    /** Leaves Favourites or a playlist, back to the view it was opened over. */
    fun closeList() = set { it.copy(filter = it.filter.copy(list = null)) }
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

    /**
     * Every song of an artist, an album or a genre, in album order, for the long-press menu.
     * Focus's filters apply; the search doesn't (an album found by its name means all of it).
     */
    suspend fun songsOf(artist: String? = null, album: String? = null, genre: String? = null): List<Track> = withContext(Dispatchers.IO) {
        val f = _ui.value.filter.copy(query = "", list = null)
        val songs = if (album != null) {
            Library.db.tracks(f, album = album)
        } else {
            Library.db.albums(f, artist = artist, genre = genre).flatMap { Library.db.tracks(f, album = it.key) }
        }
        songs.mapNotNull(Library::trackOf)
    }

    fun trackOf(t: LibTrack): Track? = Library.trackOf(t)
}
