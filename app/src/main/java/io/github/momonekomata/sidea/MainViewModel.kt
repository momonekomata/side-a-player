package io.github.momonekomata.sidea

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.momonekomata.sidea.audio.Player
import io.github.momonekomata.sidea.sources.Browser
import io.github.momonekomata.sidea.sources.Entry
import io.github.momonekomata.sidea.sources.Favorite
import io.github.momonekomata.sidea.sources.Playlist
import io.github.momonekomata.sidea.sources.PlaylistStore
import io.github.momonekomata.sidea.sources.FtpsBrowser
import io.github.momonekomata.sidea.sources.LocalBrowser
import io.github.momonekomata.sidea.sources.NasKind
import io.github.momonekomata.sidea.sources.NasServer
import io.github.momonekomata.sidea.sources.NasStore
import io.github.momonekomata.sidea.sources.WebDavBrowser
import io.github.momonekomata.sidea.sources.findUnpinned
import io.github.momonekomata.sidea.streaming.StreamingApp
import io.github.momonekomata.sidea.streaming.StreamingController
import io.github.momonekomata.sidea.lyrics.Lyrics
import io.github.momonekomata.sidea.lyrics.LyricsQuery
import io.github.momonekomata.sidea.lyrics.LyricsRepository
import io.github.momonekomata.sidea.lyrics.LyricsSources
import android.graphics.Bitmap
import io.github.momonekomata.sidea.sources.ArtworkStore
import io.github.momonekomata.sidea.sources.TagStore
import io.github.momonekomata.sidea.sources.displayAlbum
import io.github.momonekomata.sidea.sources.displayArtist
import io.github.momonekomata.sidea.sources.displayTitle
import kotlinx.coroutines.delay
import io.github.momonekomata.sidea.sources.guessArtistTitle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SourceTab(val label: String, val short: String, val app: StreamingApp? = null) {
    LOCAL("Local", "LOCAL"),
    NAS("NAS", "NAS"),
    SPOTIFY("Spotify", "SPOTIFY", StreamingApp.SPOTIFY),
    APPLE_MUSIC("Apple Music", "APPLE", StreamingApp.APPLE_MUSIC),
    TIDAL("TIDAL", "TIDAL", StreamingApp.TIDAL),
    YT_MUSIC("YT Music", "YT", StreamingApp.YT_MUSIC),
    FAVORITES("Favourites", "♥"),
}

/** A song or folder found by search, with the folders leading to it. */
data class Found(val entry: Entry, val crumbs: List<Pair<String, String>>, val haystack: String)

/** Search over one library (the local folder or a NAS), indexed on first use. */
data class SearchState(
    val isNas: Boolean,
    val query: String = "",
    val indexing: Boolean = true,
    val foldersRead: Int = 0,
    val results: List<Found> = emptyList(),
)

/** Where the J-card is in a folder tree: the browser, and the stack of folders opened. */
data class BrowseState(
    val browser: Browser,
    val path: List<Pair<String, String>>, // (display name, path)
    val entries: List<Entry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val currentPath get() = path.last().second
    val currentName get() = path.last().first
}

/** The long-press menu for a song; [playlistId]/[index] when it was opened inside a playlist. */
data class SongMenu(val track: io.github.momonekomata.sidea.sources.Track, val playlistId: String? = null, val index: Int = -1)

data class CertPrompt(val server: NasServer, val sha256: String, val subject: String)

/** Lyrics for whatever the deck is showing. [key] identifies the song they belong to. */
data class LyricsState(
    val key: String? = null,
    val lyrics: Lyrics? = null,
    val loading: Boolean = false,
    /** Looked up and found nothing; [song] says what was looked for. */
    val missed: Boolean = false,
    val song: String = "",
)

/** The song lyrics are wanted for, reduced to what identifies it. */
private sealed interface Playing {
    val key: String
    data class Local(val track: io.github.momonekomata.sidea.sources.Track, val durationMs: Long) : Playing {
        override val key get() = "local:${track.id}"
    }
    data class Remote(val app: StreamingApp, val title: String, val artist: String, val album: String, val durationMs: Long) : Playing {
        // Duration is part of the key so the lookup runs again once the app reports it.
        override val key get() = "remote:${app.name}:$artist:$title:${durationMs > 0}"
    }
}

data class UiState(
    val tab: SourceTab = SourceTab.LOCAL,
    val local: BrowseState? = null,
    val nasServers: List<NasServer> = emptyList(),
    val nas: BrowseState? = null,
    val nasServer: NasServer? = null,
    val certPrompt: CertPrompt? = null,
    val streamingAccess: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("sidea", 0)
    private val nasStore = NasStore(app)
    val streaming = StreamingController(app)

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()
    private var loadJob: Job? = null
    private var tagJob: Job? = null

    /**
     * What the now-playing card shows: null for Side A's own player, or a streaming app. It
     * follows what is actually playing; the tab only decides when nothing is.
     */
    val deckSource: StateFlow<StreamingApp?>

    private fun deckSourceFlow(): StateFlow<StreamingApp?> {
        // Whatever played last stays on the card when it pauses; only something else starting
        // to play takes the card over (pausing Spotify mustn't flip it to Side A's own song).
        var last: StreamingApp? = null
        var lastKnown = false
        return combine(_ui.map { it.tab }.distinctUntilChanged(), Player.state.map { it.isPlaying }.distinctUntilChanged(), streaming.nowPlaying) { tab, localPlaying, remote ->
            val playingApps = remote.values.filter { it.isPlaying }.map { it.app }
            val pick = when {
                localPlaying -> null
                tab.app != null && tab.app in playingApps -> tab.app
                playingApps.isNotEmpty() -> playingApps.first()
                lastKnown && (last == null || remote[last] != null) -> last
                tab.app != null && remote[tab.app] != null && Player.queue.value.current == null -> tab.app
                else -> if (Player.queue.value.current == null) tab.app else null
            }
            if (localPlaying || playingApps.isNotEmpty()) {
                last = pick
                lastKnown = true
            }
            pick
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

    private val _nowArt = MutableStateFlow<Bitmap?>(null)
    /** Cover of the local song playing. */
    val nowArt = _nowArt.asStateFlow()

    private val favoriteStore = io.github.momonekomata.sidea.sources.FavoritesStore(app)
    private val _favorites = MutableStateFlow(favoriteStore.all())
    val favorites = _favorites.asStateFlow()

    private val _search = MutableStateFlow<SearchState?>(null)
    val search = _search.asStateFlow()
    private val indexes = mutableMapOf<String, List<Found>>()
    private var indexJob: Job? = null

    /** Where each folder was scrolled to, so going back up lands where you were. */
    private val scroll = mutableMapOf<String, Pair<Int, Int>>()

    private val lyricsRepo = LyricsRepository(app)
    private val _lyrics = MutableStateFlow(LyricsState())
    val lyrics = _lyrics.asStateFlow()

    init {
        Player.init(app)
        deckSource = deckSourceFlow()
        _ui.update {
            it.copy(
                tab = runCatching { SourceTab.valueOf(prefs.getString("tab", null)!!) }.getOrDefault(SourceTab.LOCAL),
                nasServers = nasStore.all(),
            )
        }
        prefs.getString("localTree", null)?.let { openLocal(Uri.parse(it), remember = false) }
        onResume()
        streaming.output.start()
        followLyrics()
        followArtwork()
    }

    private fun followArtwork() = viewModelScope.launch {
        Player.queue.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id }.collectLatest { t ->
            _nowArt.value = t?.let { ArtworkStore.cached(it) }
            if (t == null) return@collectLatest
            // An FTPS song only becomes readable once it's cached, so look again a few times.
            repeat(4) {
                ArtworkStore.load(getApplication(), t)?.let { _nowArt.value = it; return@collectLatest }
                delay(20_000)
            }
        }
    }

    /**
     * Looks lyrics up for the song on the deck: the local queue, or the streaming app it shows.
     * Waits for the duration so the online match can use it.
     */
    private fun followLyrics() = viewModelScope.launch {
        combine(
            deckSource,
            Player.queue,
            Player.state.map { it.durationMs }.distinctUntilChanged(),
            streaming.nowPlaying,
        ) { app, queue, duration, remote ->
            if (app != null) {
                remote[app]?.takeIf { it.title.isNotBlank() }?.let { Playing.Remote(app, it.title, it.artist, it.album, it.durationMs) }
            } else {
                queue.current?.takeIf { duration > 0 }?.let { Playing.Local(it, duration) }
            }
        }
            .distinctUntilChanged { a, b -> a?.key == b?.key }
            .collectLatest { playing ->
                if (playing == null) {
                    current = null
                    _lyrics.value = LyricsState()
                    return@collectLatest
                }
                current = playing
                lookUp(playing, force = false)
            }
    }

    private var current: Playing? = null

    private suspend fun lookUp(playing: Playing, force: Boolean) {
        val q = query(playing)
        val song = listOf(q.title, q.artist).filter { it.isNotBlank() }.joinToString(" — ")
        _lyrics.value = LyricsState(key = playing.key, loading = true, song = song)
        val p = Settings.current
        val sources = LyricsSources(lrclib = p.lrclib, musixmatchKey = p.musixmatchKey, lyricsOvh = p.lyricsOvh)
        val found = lyricsRepo.find(q, online = p.onlineLyrics, sources = sources, force = force)
        if (current?.key == playing.key) _lyrics.value = LyricsState(key = playing.key, lyrics = found, missed = found == null, song = song)
    }

    fun retryLyrics() {
        val p = current ?: return
        viewModelScope.launch { lookUp(p, force = true) }
    }

    private suspend fun query(p: Playing): LyricsQuery = when (p) {
        is Playing.Remote -> LyricsQuery(p.title, p.artist, p.album, p.durationMs)
        is Playing.Local -> {
            val ctx = getApplication<Application>()
            val tags = TagStore.load(ctx, p.track)
            val (guessArtist, guessTitle) = guessArtistTitle(p.track.title)
            LyricsQuery(
                title = tags?.title ?: guessTitle,
                artist = tags?.artist ?: tags?.albumArtist ?: guessArtist,
                album = tags?.album.orEmpty(),
                durationMs = p.durationMs,
                embedded = tags?.lyrics,
                sidecar = p.track.lyricsFile?.let { read -> { read(ctx) } },
            )
        }
    }

    /** Notification access is granted in system settings, so re-check whenever we come back. */
    fun onResume() {
        streaming.start()
        _ui.update { it.copy(streamingAccess = streaming.hasAccess()) }
    }

    /** Play/pause for a streaming app; playing it first hands the DAC back from Side A's driver. */
    fun toggleRemote(app: io.github.momonekomata.sidea.streaming.StreamingApp) {
        if (streaming.nowPlaying.value[app]?.isPlaying == true) streaming.playPause(app)
        else Player.releaseDacThen { streaming.playPause(app) }
    }

    fun selectTab(tab: SourceTab) {
        prefs.edit().putString("tab", tab.name).apply()
        _ui.update { it.copy(tab = tab) }
    }

    // ---- Local folder ----

    fun openLocal(tree: Uri, remember: Boolean = true) {
        if (remember) {
            getApplication<Application>().contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("localTree", tree.toString()).apply()
        }
        val b = LocalBrowser(getApplication(), tree)
        _ui.update { it.copy(local = BrowseState(b, listOf("Music" to b.rootPath))) }
        load(isNas = false)
    }

    // ---- NAS ----

    fun saveServer(server: NasServer) {
        nasStore.save(server)
        _ui.update { it.copy(nasServers = nasStore.all()) }
    }

    fun deleteServer(server: NasServer) {
        nasStore.delete(server.id)
        _ui.update { it.copy(nasServers = nasStore.all(), nas = if (it.nasServer?.id == server.id) null else it.nas) }
    }

    fun openServer(server: NasServer) {
        val b = when (server.kind) {
            NasKind.WEBDAV -> runCatching { WebDavBrowser(server) }.getOrElse { e ->
                _ui.update { it.copy(nasServer = server, nas = null) }
                return failNas(server, e)
            }
            NasKind.FTPS -> FtpsBrowser(getApplication(), server)
        }
        _ui.update { it.copy(nasServer = server, nas = BrowseState(b, listOf(server.name to b.rootPath))) }
        load(isNas = true)
    }

    fun closeServer() = _ui.update { it.copy(nas = null, nasServer = null) }

    fun trustCertificate(prompt: CertPrompt) {
        val pinned = prompt.server.copy(pinnedSha256 = prompt.sha256)
        saveServer(pinned)
        _ui.update { it.copy(certPrompt = null) }
        openServer(pinned)
    }

    fun dismissCertPrompt() = _ui.update { it.copy(certPrompt = null) }

    private fun failNas(server: NasServer, e: Throwable) {
        val unpinned = e.findUnpinned()
        if (unpinned != null) {
            _ui.update { it.copy(certPrompt = CertPrompt(server, unpinned.sha256, unpinned.subject)) }
        }
    }

    // ---- Browsing, shared by both trees ----

    fun openFolder(isNas: Boolean, folder: Entry.Folder) {
        updateBrowse(isNas) { it.copy(path = it.path + (folder.name to folder.path)) }
        load(isNas)
    }

    /** Returns false at the root, so the caller can let Back leave the screen. */
    fun up(isNas: Boolean): Boolean {
        val b = browse(isNas) ?: return false
        if (b.path.size <= 1) {
            if (isNas) { closeServer(); return true }
            return false
        }
        updateBrowse(isNas) { it.copy(path = it.path.dropLast(1)) }
        load(isNas)
        return true
    }

    fun play(isNas: Boolean, song: Entry.Song) {
        val songs = browse(isNas)?.entries?.filterIsInstance<Entry.Song>() ?: return
        Player.playQueue(songs.map { it.track }, songs.indexOf(song).coerceAtLeast(0))
    }

    fun retry(isNas: Boolean) = load(isNas)

    private fun browse(isNas: Boolean) = if (isNas) _ui.value.nas else _ui.value.local

    private fun updateBrowse(isNas: Boolean, f: (BrowseState) -> BrowseState) = _ui.update {
        if (isNas) it.copy(nas = it.nas?.let(f)) else it.copy(local = it.local?.let(f))
    }

    private fun load(isNas: Boolean) {
        val b = browse(isNas) ?: return
        val path = b.currentPath
        updateBrowse(isNas) { it.copy(loading = true, error = null) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val entries = b.browser.list(path)
                updateBrowse(isNas) { if (it.currentPath == path) it.copy(entries = entries, loading = false) else it }
                readTags(entries)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                updateBrowse(isNas) { it.copy(loading = false, error = e.findUnpinned()?.let { "Certificate not trusted yet" } ?: e.message ?: e.javaClass.simpleName) }
                if (isNas) _ui.value.nasServer?.let { failNas(it, e) }
            }
        }
    }

    /** Fills in titles from tags for the folder on screen, top to bottom. */
    private fun readTags(entries: List<Entry>) {
        tagJob?.cancel()
        tagJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            entries.filterIsInstance<Entry.Song>().forEach { TagStore.load(ctx, it.track) }
        }
    }

    // ---- favourites ----

    fun toggleFavorite(t: io.github.momonekomata.sidea.sources.Track) {
        val list = _favorites.value
        val next = if (list.any { it.id == t.id }) list.filterNot { it.id == t.id } else listOf(saved(t)) + list
        _favorites.value = next
        favoriteStore.save(next)
    }

    /** What's kept of a song to show it and find it again (favourites, playlists). */
    private fun saved(t: io.github.momonekomata.sidea.sources.Track): Favorite {
        val tags = TagStore.tags.value
        return Favorite(t.id, t.displayTitle(tags), t.displayArtist(tags), t.displayAlbum(tags), t.sizeBytes)
    }

    private fun resolve(f: Favorite) =
        io.github.momonekomata.sidea.sources.TrackResolver.resolve(getApplication(), f, nasStore.all())

    /** Plays [songs] (favourites or a playlist) from the one at [index]. */
    private fun playSaved(songs: List<Favorite>, index: Int) {
        val servers = nasStore.all()
        val ctx = getApplication<Application>()
        val tracks = songs.mapNotNull { io.github.momonekomata.sidea.sources.TrackResolver.resolve(ctx, it, servers) }
        // Songs whose server was removed drop out; start from the one tapped, wherever it ended up.
        val start = songs.take(index).count { f -> tracks.any { it.id == f.id } }.coerceAtMost(tracks.size - 1)
        if (tracks.isNotEmpty()) Player.playQueue(tracks, start)
    }

    fun playFavorites(index: Int) = playSaved(_favorites.value, index)

    /** Plays one song from the history. */
    fun playPlayed(f: Favorite) = playSaved(listOf(f), 0)

    // ---- long-press menu: queue, playlists ----

    private val _songMenu = MutableStateFlow<SongMenu?>(null)
    val songMenu = _songMenu.asStateFlow()

    fun openSongMenu(t: io.github.momonekomata.sidea.sources.Track, playlistId: String? = null, index: Int = -1) {
        _songMenu.value = SongMenu(t, playlistId, index)
    }

    fun openSongMenu(f: Favorite, playlistId: String? = null, index: Int = -1) {
        val t = resolve(f) ?: run {
            android.widget.Toast.makeText(getApplication(), "That song's NAS server was removed", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        openSongMenu(t, playlistId, index)
    }

    fun closeSongMenu() { _songMenu.value = null }

    fun playNext(t: io.github.momonekomata.sidea.sources.Track) = Player.playNext(t)
    fun addToQueue(t: io.github.momonekomata.sidea.sources.Track) = Player.addToQueue(t)

    private val playlistStore = PlaylistStore(app)
    private val _playlists = MutableStateFlow(playlistStore.all())
    val playlists = _playlists.asStateFlow()

    /** The playlist open in the Favourites card; null shows the list. */
    private val _openPlaylist = MutableStateFlow<String?>(null)
    val openPlaylist = _openPlaylist.asStateFlow()

    fun showPlaylist(id: String?) { _openPlaylist.value = id }

    private fun savePlaylists(list: List<Playlist>) {
        _playlists.value = list
        playlistStore.save(list)
    }

    fun addToPlaylist(id: String, t: io.github.momonekomata.sidea.sources.Track) =
        savePlaylists(_playlists.value.map { if (it.id == id) it.copy(songs = it.songs + saved(t)) else it })

    /** A new playlist, with [first] in it if given. */
    fun newPlaylist(name: String, first: io.github.momonekomata.sidea.sources.Track?) {
        val p = Playlist(java.util.UUID.randomUUID().toString(), name.trim().ifEmpty { "Playlist" }, listOfNotNull(first?.let(::saved)))
        savePlaylists(_playlists.value + p)
    }

    fun removeFromPlaylist(id: String, index: Int) =
        savePlaylists(_playlists.value.map { p -> if (p.id == id) p.copy(songs = p.songs.filterIndexed { i, _ -> i != index }) else p })

    fun deletePlaylist(id: String) {
        if (_openPlaylist.value == id) _openPlaylist.value = null
        savePlaylists(_playlists.value.filterNot { it.id == id })
    }

    fun playPlaylist(id: String, index: Int) {
        _playlists.value.firstOrNull { it.id == id }?.let { playSaved(it.songs, index) }
    }

    // ---- scroll memory ----

    fun scrollFor(key: String) = scroll[key] ?: (0 to 0)
    fun saveScroll(key: String, index: Int, offset: Int) { scroll[key] = index to offset }

    // ---- search ----

    fun openSearch(isNas: Boolean) {
        val b = browse(isNas) ?: return
        val key = indexKey(isNas, b)
        _search.value = SearchState(isNas, indexing = indexes[key] == null)
        if (indexes[key] != null) return
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            val found = mutableListOf<Found>()
            // Breadth-first over the whole tree; each folder is one listing, like opening it.
            val queue = ArrayDeque(listOf(b.path.take(1)))
            var read = 0
            while (queue.isNotEmpty()) {
                val crumbs = queue.removeFirst()
                val entries = runCatching { b.browser.list(crumbs.last().second) }.getOrDefault(emptyList())
                read++
                for (e in entries) {
                    when (e) {
                        is Entry.Folder -> {
                            val next = crumbs + (e.name to e.path)
                            queue.addLast(next)
                            found += Found(e, next, e.name.lowercase())
                        }
                        is Entry.Song -> found += Found(e, crumbs, (e.name + " " + crumbs.last().first).lowercase())
                    }
                }
                if (read % 10 == 0) _search.update { it?.copy(foldersRead = read) }
            }
            indexes[key] = found
            _search.update { it?.copy(indexing = false, foldersRead = read) }
            setQuery(_search.value?.query.orEmpty())
            // Tags for the songs make titles and artists searchable too.
            val ctx = getApplication<Application>()
            found.mapNotNull { (it.entry as? Entry.Song)?.track }.forEach { TagStore.load(ctx, it) }
        }
    }

    fun closeSearch() {
        _search.value = null
    }

    fun setQuery(q: String) {
        val s = _search.value ?: return
        val b = browse(s.isNas) ?: return
        val all = indexes[indexKey(s.isNas, b)]
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        val tags = TagStore.tags.value
        val results = if (all == null || words.isEmpty()) emptyList() else all.filter { f ->
            val t = (f.entry as? Entry.Song)?.track?.let { tags[it.id] }
            val text = if (t == null) f.haystack else f.haystack + " " + listOfNotNull(t.title, t.artist, t.album, t.albumArtist).joinToString(" ").lowercase()
            words.all { it in text }
        }.sortedBy { if (it.entry is Entry.Song) 0 else 1 }.take(400)
        _search.value = s.copy(query = q, results = results)
    }

    fun playFound(found: Found) {
        val songs = _search.value?.results?.mapNotNull { (it.entry as? Entry.Song)?.track } ?: return
        val t = (found.entry as? Entry.Song)?.track ?: return
        Player.playQueue(songs, songs.indexOf(t).coerceAtLeast(0))
    }

    /** Opens the folder a search result lives in (or the folder itself). */
    fun openFound(found: Found) {
        val isNas = _search.value?.isNas ?: return
        _search.value = null
        updateBrowse(isNas) { it.copy(path = found.crumbs) }
        load(isNas)
    }

    private fun indexKey(isNas: Boolean, b: BrowseState) = (if (isNas) "nas:" else "local:") + b.browser.title + ":" + b.path.first().second

    override fun onCleared() {
        streaming.stop()
        streaming.output.stop()
    }
}
