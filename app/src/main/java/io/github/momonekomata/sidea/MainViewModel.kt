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
import android.util.Log
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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

enum class SourceTab(val label: String, val short: String) {
    LIBRARY("Library", "LIBRARY"),
    LOCAL("Local", "LOCAL"),
    NAS("NAS & cloud", "REMOTE"),
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
    /** The folder searched from. */
    val scopeName: String = "",
    /** When the index was made; 0 while it's being made. */
    val indexedAt: Long = 0,
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
data class SongMenu(val track: io.github.momonekomata.sidea.sources.Track, val playlistId: String? = null, val index: Int = -1, val queueIndex: Int = -1)

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
}

data class UiState(
    val tab: SourceTab = SourceTab.LIBRARY,
    val local: BrowseState? = null,
    val nasServers: List<NasServer> = emptyList(),
    val nas: BrowseState? = null,
    val nasServer: NasServer? = null,
    val certPrompt: CertPrompt? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("sidea", 0)
    private val nasStore = NasStore(app)

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()
    /** One listing at a time per side, so restoring Local and the NAS together doesn't cancel one. */
    private val loadJobs = arrayOfNulls<Job>(2)
    private var tagJob: Job? = null

    private val _nowArt = MutableStateFlow<Bitmap?>(null)
    /** Cover of the local song playing. */
    val nowArt = _nowArt.asStateFlow()

    private val favoriteStore = io.github.momonekomata.sidea.sources.FavoritesStore(app)
    private val _favorites = MutableStateFlow(favoriteStore.all())
    val favorites = _favorites.asStateFlow()

    private val _search = MutableStateFlow<SearchState?>(null)
    val search = _search.asStateFlow()
    private val indexes = mutableMapOf<String, SearchIndex>()
    private val searchStore = SearchIndexStore(app)
    /** What the open search looks through: the index, narrowed to the folder searched from. */
    private var pool: List<Found> = emptyList()
    private var indexJob: Job? = null

    /** Where each folder was scrolled to, so going back up lands where you were. */
    private val scroll = mutableMapOf<String, Pair<Int, Int>>()

    private val lyricsRepo = LyricsRepository(app)
    private val _lyrics = MutableStateFlow(LyricsState())
    val lyrics = _lyrics.asStateFlow()

    init {
        Player.init(app)
        _ui.update {
            it.copy(
                tab = runCatching { SourceTab.valueOf(prefs.getString("tab", null)!!) }.getOrDefault(SourceTab.LIBRARY),
                nasServers = nasStore.all(),
            )
        }
        prefs.getString("localTree", null)?.let { openLocal(Uri.parse(it), remember = false) }
        // The NAS server and folder you were in last time.
        prefs.getString("place:nasServer", null)?.let { id -> nasStore.all().firstOrNull { it.id == id } }
            ?.takeIf { !it.kind.cloud || it.refreshToken.isNotBlank() }
            ?.let { openServer(it, resume = true) }
        // The last queue, paused where it was, if the player has nothing (the app was closed).
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val servers = nasStore.all()
            Player.restore { io.github.momonekomata.sidea.sources.TrackResolver.resolve(ctx, it, servers) }
        }
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
     * Looks lyrics up for the song playing. Waits for the duration so the online match can use it.
     */
    private fun followLyrics() = viewModelScope.launch {
        combine(Player.queue, Player.state.map { it.durationMs }.distinctUntilChanged()) { queue, duration ->
            queue.current?.takeIf { duration > 0 }?.let { Playing.Local(it, duration) }
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
        if (found != null && playing is Playing.Local) saveLyrics(playing.track, found, q)
    }

    private val lyricsSaved = mutableSetOf<String>()
    private var toldNoWrite = false

    /**
     * Lyrics found online go into a .lrc beside the song (timed when they are), which other
     * players read too and which outlives the cache. Timed lyrics replace a .lrc that only had plain
     * ones (that's the only time an online lookup happens with a .lrc there); plain ones never
     * replace anything. Never into the audio file itself.
     */
    private suspend fun saveLyrics(t: io.github.momonekomata.sidea.sources.Track, l: io.github.momonekomata.sidea.lyrics.Lyrics, q: LyricsQuery) {
        val write = t.lyricsWriter ?: return
        if (!Settings.current.saveLyrics || l.source == "file tags" || l.source == ".lrc file") return
        val hadLyrics = t.lyricsFile != null || q.embedded != null
        if (hadLyrics && !l.synced) return
        if (!lyricsSaved.add(t.id)) return
        val text = io.github.momonekomata.sidea.lyrics.Lrc.format(l, q.title, q.artist, q.album)
        withContext(Dispatchers.IO) {
            runCatching { write(getApplication(), text) }.onFailure { e ->
                Log.w("SideA", "couldn't save lyrics for ${t.id}", e)
                if (e is SecurityException && !toldNoWrite) {
                    toldNoWrite = true
                    launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(getApplication(), "To save lyrics beside your songs, choose the music folder again (Local → Change music folder)", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun retryLyrics() {
        val p = current ?: return
        viewModelScope.launch { lookUp(p, force = true) }
    }

    private suspend fun query(p: Playing): LyricsQuery = when (p) {
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

    fun tabTo(tab: SourceTab) = selectTab(tab)

    fun selectTab(tab: SourceTab) {
        prefs.edit().putString("tab", tab.name).apply()
        _ui.update { it.copy(tab = tab) }
    }

    // ---- Local folder ----

    fun openLocal(tree: Uri, remember: Boolean = true) {
        if (remember) {
            // Write too, for saving found lyrics as .lrc files beside the songs.
            val resolver = getApplication<Application>().contentResolver
            runCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .onFailure { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.edit().putString("localTree", tree.toString()).apply()
        }
        val b = LocalBrowser(getApplication(), tree)
        val root = listOf("Music" to b.rootPath)
        // Back where you were last time, if that folder is still inside the one chosen.
        val path = savedPlace(false)?.takeIf { !remember && it.firstOrNull()?.second == b.rootPath } ?: root
        _ui.update { it.copy(local = BrowseState(b, path)) }
        load(isNas = false)
    }

    // ---- the Library ----

    /** Made on first use: the Library opens its database then. */
    val library by lazy {
        io.github.momonekomata.sidea.library.Library.init(getApplication())
        io.github.momonekomata.sidea.library.LibraryModel(viewModelScope)
    }

    /** Whether the folder open on this side is already one of the Library's sources. */
    fun inLibrary(isNas: Boolean): Boolean {
        val b = browse(isNas) ?: return false
        library // makes sure it's open
        return if (isNas) io.github.momonekomata.sidea.library.Library.contains(null, _ui.value.nasServer?.id, b.currentPath)
        else io.github.momonekomata.sidea.library.Library.contains(prefs.getString("localTree", null), null, b.currentPath)
    }

    /** Adds the folder open on this side (and everything in it) to the Library, and scans it. */
    fun addToLibrary(isNas: Boolean) {
        val b = browse(isNas) ?: return
        library
        val src = if (isNas) {
            val server = _ui.value.nasServer ?: return
            io.github.momonekomata.sidea.library.LibrarySource(name = "${server.name} · ${b.currentName}", serverId = server.id, root = b.currentPath)
        } else {
            val tree = prefs.getString("localTree", null) ?: return
            io.github.momonekomata.sidea.library.LibrarySource(name = "Phone · ${b.currentName}", tree = tree, root = b.currentPath)
        }
        io.github.momonekomata.sidea.library.Library.add(src)
        android.widget.Toast.makeText(getApplication(), "Adding ${b.currentName} to the Library…", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** A folder picked for the Library straight from the Sources tab. */
    fun addLibraryTree(tree: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val root = android.provider.DocumentsContract.getTreeDocumentId(tree)
        library
        io.github.momonekomata.sidea.library.Library.add(
            io.github.momonekomata.sidea.library.LibrarySource(name = "Phone · " + root.substringAfterLast(':').substringAfterLast('/').ifBlank { "Storage" }, tree = tree.toString(), root = root),
        )
    }

    /** The song menu for a Library song. */
    fun openSongMenu(t: io.github.momonekomata.sidea.library.LibTrack) {
        val track = library.trackOf(t) ?: return
        openSongMenu(track)
    }

    // ---- where each side was left ----

    private fun rememberPlace(isNas: Boolean, b: BrowseState) {
        val crumbs = org.json.JSONArray(b.path.map { (n, p) -> org.json.JSONArray().put(n).put(p) })
        prefs.edit().putString(if (isNas) "place:nas" else "place:local", crumbs.toString()).apply()
        if (isNas) _ui.value.nasServer?.let { prefs.edit().putString("place:nasServer", it.id).apply() }
    }

    private fun savedPlace(isNas: Boolean): List<Pair<String, String>>? = runCatching {
        val a = org.json.JSONArray(prefs.getString(if (isNas) "place:nas" else "place:local", null) ?: return null)
        (0 until a.length()).map { a.getJSONArray(it).let { c -> c.getString(0) to c.getString(1) } }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ---- NAS ----

    fun saveServer(server: NasServer) {
        nasStore.save(server)
        // Edited settings: the next listing connects afresh.
        io.github.momonekomata.sidea.sources.RemoteStores.forget(server.id)
        _ui.update { it.copy(nasServers = nasStore.all()) }
    }

    /** What a store learns while working (a pinned SSH host key, a new cloud token) is kept, quietly. */
    // A function, not a lambda property: start-up opens the last server before properties
    // declared down here would be set.
    private fun keepServer(s: NasServer) {
        nasStore.save(s)
        io.github.momonekomata.sidea.sources.RemoteStores.update(s)
        _ui.update { it.copy(nasServers = nasStore.all()) }
    }

    /** Opens the cloud's sign-in page in the browser, keeps the token it gives, and opens the server. */
    fun signInCloud(server: NasServer) {
        nasStore.save(server)
        _ui.update { it.copy(nasServers = nasStore.all()) }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                val token = io.github.momonekomata.sidea.sources.CloudSignIn.signIn(ctx, server)
                val signed = server.copy(refreshToken = token)
                saveServer(signed)
                android.widget.Toast.makeText(ctx, "Signed in to ${server.kind.label}", android.widget.Toast.LENGTH_SHORT).show()
                openServer(signed)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.widget.Toast.makeText(ctx, e.message ?: "Signing in failed", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteServer(server: NasServer) {
        io.github.momonekomata.sidea.sources.RemoteStores.forget(server.id)
        nasStore.delete(server.id)
        _ui.update { it.copy(nasServers = nasStore.all(), nas = if (it.nasServer?.id == server.id) null else it.nas) }
    }

    fun openServer(server: NasServer, resume: Boolean = false) {
        val b = when (server.kind) {
            NasKind.WEBDAV -> runCatching { WebDavBrowser(server) }.getOrElse { e ->
                _ui.update { it.copy(nasServer = server, nas = null) }
                return failNas(server, e)
            }
            NasKind.FTPS -> FtpsBrowser(getApplication(), server)
            else -> io.github.momonekomata.sidea.sources.RemoteBrowser(
                getApplication(), server, io.github.momonekomata.sidea.sources.RemoteStores.get(getApplication(), server, ::keepServer),
            )
        }
        val root = listOf(server.name to b.rootPath)
        val path = if (resume) savedPlace(true)?.takeIf { it.firstOrNull()?.second == b.rootPath } ?: root else root
        _ui.update { it.copy(nasServer = server, nas = BrowseState(b, path)) }
        load(isNas = true)
    }

    fun closeServer() {
        prefs.edit().remove("place:nasServer").remove("place:nas").apply()
        _ui.update { it.copy(nas = null, nasServer = null) }
    }

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
        rememberPlace(isNas, b)
        val slot = if (isNas) 1 else 0
        loadJobs[slot]?.cancel()
        loadJobs[slot] = viewModelScope.launch {
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

    fun openSongMenu(t: io.github.momonekomata.sidea.sources.Track, playlistId: String? = null, index: Int = -1, queueIndex: Int = -1) {
        _songMenu.value = SongMenu(t, playlistId, index, queueIndex)
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

    /**
     * Searches the folder that's open and everything under it. An index saved for it (or for a
     * folder above it) is used straight away; otherwise the folders are read once and the index
     * is kept on the phone for next time. [rescan] reads them again.
     */
    fun openSearch(isNas: Boolean, rescan: Boolean = false) {
        val b = browse(isNas) ?: return
        val here = b.path
        val query = _search.value?.takeIf { it.isNas == isNas }?.query.orEmpty()
        if (!rescan) {
            for (i in here.indices.reversed()) {
                val k = indexKey(isNas, b, here[i].second)
                val idx = indexes[k] ?: searchStore.load(k, b.browser)?.also { indexes[k] = it } ?: continue
                // An index made higher up: just what's under this folder (not the folder itself).
                val cur = here.last().second
                pool = if (i == here.lastIndex) idx.items else idx.items.filter { f ->
                    f.crumbs.any { it.second == cur } && !(f.entry is Entry.Folder && f.crumbs.last().second == cur)
                }
                _search.value = SearchState(isNas, query, indexing = false, foldersRead = idx.folders, scopeName = here.last().first, indexedAt = idx.atMs)
                setQuery(query)
                return
            }
        }
        val key = indexKey(isNas, b, here.last().second)
        pool = emptyList()
        _search.value = SearchState(isNas, query, indexing = true, scopeName = here.last().first)
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            val found = mutableListOf<Found>()
            // Breadth-first from here; each folder is one listing, like opening it.
            val queue = ArrayDeque(listOf(here))
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
            val idx = SearchIndex(found, System.currentTimeMillis(), read)
            indexes[key] = idx
            pool = found
            withContext(Dispatchers.IO) { searchStore.save(key, idx, TagStore.tags.value) }
            _search.update { it?.copy(indexing = false, foldersRead = read, indexedAt = idx.atMs) }
            setQuery(_search.value?.query.orEmpty())
            // Tags for the songs make titles and artists searchable too; saved with the index.
            val ctx = getApplication<Application>()
            found.mapNotNull { (it.entry as? Entry.Song)?.track }.forEach { TagStore.load(ctx, it) }
            withContext(Dispatchers.IO) { searchStore.save(key, idx, TagStore.tags.value) }
        }
    }

    /** Reads the folders again, for files added since the index was made. */
    fun rescanSearch() {
        val s = _search.value ?: return
        openSearch(s.isNas, rescan = true)
    }

    fun closeSearch() {
        _search.value = null
    }

    fun setQuery(q: String) {
        val s = _search.value ?: return
        val all = pool.takeIf { !s.indexing || it.isNotEmpty() }
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

    private fun indexKey(isNas: Boolean, b: BrowseState, folder: String) = (if (isNas) "nas:" else "local:") + b.browser.title + ":" + folder
}
