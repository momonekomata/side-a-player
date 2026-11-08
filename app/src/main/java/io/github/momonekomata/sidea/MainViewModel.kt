package io.github.momonekomata.sidea

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.momonekomata.sidea.audio.Player
import io.github.momonekomata.sidea.library.FAVORITES_LIST
import io.github.momonekomata.sidea.library.Library
import io.github.momonekomata.sidea.library.LibrarySource
import io.github.momonekomata.sidea.library.PlaylistFiles
import io.github.momonekomata.sidea.sources.Browser
import io.github.momonekomata.sidea.sources.Entry
import io.github.momonekomata.sidea.sources.Favorite
import io.github.momonekomata.sidea.sources.Playlist
import io.github.momonekomata.sidea.sources.Playlists
import io.github.momonekomata.sidea.sources.FtpsBrowser
import io.github.momonekomata.sidea.sources.NasKind
import io.github.momonekomata.sidea.sources.NasServer
import io.github.momonekomata.sidea.sources.NasStore
import io.github.momonekomata.sidea.sources.WebDavBrowser
import io.github.momonekomata.sidea.sources.findUnpinned
import io.github.momonekomata.sidea.sources.friendlyError
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the folder picker is in a server's folder tree: the browser, and the folders opened. */
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

/** The long-press menu for an album, an artist or a genre: the same as a song's, for all its songs. */
data class GroupMenu(val title: String, val subtitle: String, val tracks: List<io.github.momonekomata.sidea.sources.Track>?)

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

/**
 * The music sources, as Settings shows them: the NAS and cloud servers, and the one open in the
 * folder picker (to choose a folder of it for the Library).
 */
data class UiState(
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
    private var loadJob: Job? = null

    private val _nowArt = MutableStateFlow<Bitmap?>(null)
    /** Cover of the song playing. */
    val nowArt = _nowArt.asStateFlow()

    private val favoriteStore = io.github.momonekomata.sidea.sources.FavoritesStore(app)
    private val _favorites = MutableStateFlow(favoriteStore.all())
    val favorites = _favorites.asStateFlow()

    private val lyricsRepo = LyricsRepository(app)
    private val _lyrics = MutableStateFlow(LyricsState())
    val lyrics = _lyrics.asStateFlow()

    private val _playlists = Playlists.apply { init(app) }.all
    val playlists = _playlists

    /** The Library: the one place songs are browsed. Opens its database on first use. */
    val library by lazy {
        Library.init(getApplication())
        io.github.momonekomata.sidea.library.LibraryModel(viewModelScope, prefs)
    }

    init {
        Player.init(app)
        _ui.update { it.copy(nasServers = nasStore.all()) }
        library
        moveLocalFolderIntoLibrary()
        // Whenever the playlists change (here, or a scan finding playlist files): Focus gets them.
        viewModelScope.launch { _playlists.collect { syncLists() } }
        // The last queue, paused where it was, if the player has nothing (the app was closed).
        viewModelScope.launch(Dispatchers.IO) {
            Player.restore(::resolve)
        }
        followLyrics()
        followArtwork()
    }

    /**
     * Before the Library was the only way in, a folder could be played from the Local tab
     * without being in the Library. That folder joins the Library, so nothing goes missing.
     */
    private fun moveLocalFolderIntoLibrary() {
        val tree = prefs.getString("localTree", null) ?: return
        prefs.edit().remove("localTree").remove("place:local").remove("tab").apply()
        val root = runCatching { android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree)) }.getOrNull() ?: return
        if (Library.sources.value.none { it.tree == tree }) {
            Library.add(LibrarySource(name = "Phone · " + root.substringAfterLast(':').substringAfterLast('/').ifBlank { "Storage" }, tree = tree, root = root))
        }
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
     * players read too and which outlives the cache. A .lrc that's already there is never
     * replaced: it may be one you wrote. Plain lyrics don't replace lyrics in the file's tags
     * either. Never into the audio file itself.
     */
    private suspend fun saveLyrics(t: io.github.momonekomata.sidea.sources.Track, l: io.github.momonekomata.sidea.lyrics.Lyrics, q: LyricsQuery) {
        val write = t.lyricsWriter ?: return
        if (!Settings.current.saveLyrics || l.source == "file tags" || l.source == ".lrc file") return
        if (q.embedded != null && !l.synced) return
        if (!lyricsSaved.add(t.id)) return
        withContext(Dispatchers.IO) {
            // Library songs can always ask for their .lrc; whether one is there is what counts.
            val hasFile = runCatching { t.lyricsFile?.invoke(getApplication()) }.getOrNull() != null
            if (hasFile) return@withContext
            val text = io.github.momonekomata.sidea.lyrics.Lrc.format(l, q.title, q.artist, q.album)
            runCatching { write(getApplication(), text) }.onFailure { e ->
                Log.w("SideA", "couldn't save lyrics for ${t.id}", e)
                if (e is SecurityException && !toldNoWrite) {
                    toldNoWrite = true
                    launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(getApplication(), "To save lyrics beside your songs, add the music folder again (Settings → Music sources)", android.widget.Toast.LENGTH_LONG).show()
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

    // ---- the Library's sources ----

    /** Whether the folder open in the picker is already one of the Library's sources. */
    fun inLibrary(): Boolean {
        val b = _ui.value.nas ?: return false
        return Library.contains(null, _ui.value.nasServer?.id, b.currentPath)
    }

    /** Adds the folder open in the picker (and everything in it) to the Library, and scans it. */
    fun addToLibrary() {
        val b = _ui.value.nas ?: return
        val server = _ui.value.nasServer ?: return
        Library.add(LibrarySource(name = "${server.name} · ${b.currentName}", serverId = server.id, root = b.currentPath))
        android.widget.Toast.makeText(getApplication(), "Adding ${b.currentName} to the Library…", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** A folder on the phone picked for the Library with Android's folder picker. */
    fun addLibraryTree(tree: Uri) {
        val resolver = getApplication<Application>().contentResolver
        // Write too, for saving found lyrics as .lrc files beside the songs.
        runCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val root = android.provider.DocumentsContract.getTreeDocumentId(tree)
        Library.add(
            LibrarySource(name = "Phone · " + root.substringAfterLast(':').substringAfterLast('/').ifBlank { "Storage" }, tree = tree.toString(), root = root),
        )
    }

    /** The song menu for a Library song. */
    fun openSongMenu(t: io.github.momonekomata.sidea.library.LibTrack, playlistId: String? = null, index: Int = -1) {
        val track = library.trackOf(t) ?: return
        openSongMenu(track, playlistId, index)
    }

    // ---- NAS and cloud servers ----

    fun saveServer(server: NasServer) {
        nasStore.save(server)
        // Edited settings: the next listing connects afresh.
        io.github.momonekomata.sidea.sources.RemoteStores.forget(server.id)
        _ui.update { it.copy(nasServers = nasStore.all()) }
    }

    /** What a store learns while working (a pinned SSH host key, a new cloud token) is kept, quietly. */
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

    /** Opens [server] in the folder picker, at its start folder. */
    fun openServer(server: NasServer) {
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
        _ui.update { it.copy(nasServer = server, nas = BrowseState(b, listOf(server.name to b.rootPath))) }
        load()
    }

    fun closeServer() {
        loadJob?.cancel()
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

    // ---- the folder picker ----

    fun openFolder(folder: Entry.Folder) {
        updateBrowse { it.copy(path = it.path + (folder.name to folder.path)) }
        load()
    }

    /** Back up a folder; at the server's start folder, closes it. */
    fun up() {
        val b = _ui.value.nas ?: return
        if (b.path.size <= 1) {
            closeServer()
            return
        }
        updateBrowse { it.copy(path = it.path.dropLast(1)) }
        load()
    }

    fun retry() = load()

    private fun updateBrowse(f: (BrowseState) -> BrowseState) = _ui.update { it.copy(nas = it.nas?.let(f)) }

    private fun load() {
        val b = _ui.value.nas ?: return
        val path = b.currentPath
        updateBrowse { it.copy(loading = true, error = null) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                // Hidden and NAS system folders (.thumbnails, #recycle, @eaDir) aren't music.
                val entries = b.browser.list(path).filterNot { it is Entry.Folder && io.github.momonekomata.sidea.sources.isSystemFolder(it.name) }
                updateBrowse { if (it.currentPath == path) it.copy(entries = entries, loading = false) else it }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                updateBrowse { it.copy(loading = false, error = friendlyError(e)) }
                _ui.value.nasServer?.let { failNas(it, e) }
            }
        }
    }

    // ---- favourites and playlists ----

    fun toggleFavorite(t: io.github.momonekomata.sidea.sources.Track) {
        val list = _favorites.value
        val next = if (list.any { it.id == t.id }) list.filterNot { it.id == t.id } else listOf(saved(t)) + list
        _favorites.value = next
        favoriteStore.save(next)
        syncLists()
    }

    /** The Library's Focus filters on favourites and playlists: it gets them as they are now. */
    private fun syncLists() {
        Library.syncLists(
            mapOf(FAVORITES_LIST to _favorites.value.map { it.id }) + _playlists.value.associate { p -> p.id to p.songs.map { it.id } },
        )
    }

    /** What's kept of a song to show it and find it again (favourites, playlists). */
    private fun saved(t: io.github.momonekomata.sidea.sources.Track): Favorite {
        val tags = TagStore.tags.value
        return Favorite(t.id, t.displayTitle(tags), t.displayArtist(tags), t.displayAlbum(tags), t.sizeBytes)
    }

    /**
     * A saved song (favourite, playlist, history, the queue from last time) as a playable track:
     * the Library's own when it has the song (its .lrc, cover and names come with it), otherwise
     * found where it lives.
     */
    private fun resolve(f: Favorite) = Library.trackById(f.id)
        ?: io.github.momonekomata.sidea.sources.TrackResolver.resolve(getApplication(), f, nasStore.all())

    /** Plays [songs] (a playlist, the history) from the one at [index]. */
    private fun playSaved(songs: List<Favorite>, index: Int) = viewModelScope.launch {
        val tracks = withContext(Dispatchers.IO) { songs.map { it to resolve(it) } }
        val playable = tracks.mapNotNull { it.second }
        // Songs whose server was removed drop out; start from the one tapped, wherever it ended up.
        val start = tracks.take(index).count { it.second != null }.coerceAtMost(playable.size - 1)
        if (playable.isNotEmpty()) Player.playQueue(playable, start)
    }

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
            android.widget.Toast.makeText(getApplication(), "That song's server was removed", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        openSongMenu(t, playlistId, index)
    }

    fun closeSongMenu() { _songMenu.value = null }

    private val _groupMenu = MutableStateFlow<GroupMenu?>(null)
    val groupMenu = _groupMenu.asStateFlow()
    private var groupJob: kotlinx.coroutines.Job? = null

    /** The menu for an artist, an album ([album] is its key) or a genre; its songs load as it opens. */
    fun openGroupMenu(title: String, subtitle: String, artist: String? = null, album: String? = null, genre: String? = null) {
        _groupMenu.value = GroupMenu(title, subtitle, null)
        groupJob?.cancel()
        groupJob = viewModelScope.launch {
            val songs = library.songsOf(artist = artist, album = album, genre = genre)
            _groupMenu.update { it?.copy(tracks = songs) }
        }
    }

    fun closeGroupMenu() {
        groupJob?.cancel()
        _groupMenu.value = null
    }

    fun playNext(t: io.github.momonekomata.sidea.sources.Track) = Player.playNext(t)
    fun addToQueue(t: io.github.momonekomata.sidea.sources.Track) = Player.addToQueue(t)
    fun playNext(songs: List<io.github.momonekomata.sidea.sources.Track>) = Player.playNext(songs)
    fun addToQueue(songs: List<io.github.momonekomata.sidea.sources.Track>) = Player.addToQueue(songs)

    /** Makes all of [songs] favourites, or (when they all are already) none of them. */
    fun toggleFavorites(songs: List<io.github.momonekomata.sidea.sources.Track>) {
        if (songs.isEmpty()) return
        val list = _favorites.value
        val ids = songs.map { it.id }.toSet()
        val next = if (songs.all { s -> list.any { it.id == s.id } }) {
            list.filterNot { it.id in ids }
        } else {
            songs.filter { s -> list.none { it.id == s.id } }.map(::saved) + list
        }
        _favorites.value = next
        favoriteStore.save(next)
        syncLists()
    }

    fun addToPlaylist(id: String, songs: List<io.github.momonekomata.sidea.sources.Track>) =
        savePlaylists(_playlists.value.map { if (it.id == id) it.copy(songs = it.songs + songs.map(::saved)) else it })

    fun newPlaylist(name: String, songs: List<io.github.momonekomata.sidea.sources.Track>) {
        val p = Playlist(java.util.UUID.randomUUID().toString(), name.trim().ifEmpty { "Playlist" }, songs.map(::saved))
        savePlaylists(_playlists.value + p)
    }

    private fun savePlaylists(list: List<Playlist>) {
        Playlists.update { list }
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
        // A Focus on it goes too.
        if (library.ui.value.filter.list == id) library.closeList()
        // One read from a playlist file stays gone: the next scan doesn't bring it back.
        _playlists.value.firstOrNull { it.id == id }?.origin?.let(Playlists::dismiss)
        savePlaylists(_playlists.value.filterNot { it.id == id })
    }

    // ---- playlist files: import and export ----

    /** Reads a playlist file picked on the phone (.m3u, .m3u8, .pls) into a new playlist of the Library's songs. */
    fun importPlaylist(uri: Uri) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val name = displayName(uri) ?: "Playlist"
                PlaylistFiles.import(name, bytes)
            }
        }
        result.onSuccess { (p, missing) ->
            if (p.songs.isEmpty()) {
                toast("None of that playlist's ${missing} songs are in the Library")
                return@onSuccess
            }
            savePlaylists(_playlists.value + p)
            library.showList(p.id)
            toast("“${p.name}”: ${p.songs.size} songs" + if (missing > 0) " · $missing not in the Library" else "")
        }.onFailure { toast("Couldn't read that playlist (${friendlyError(it)})") }
    }

    /** Writes playlist [id] as an .m3u8 to [uri] (made with the system's Save dialog). */
    fun exportPlaylist(id: String, uri: Uri) = viewModelScope.launch {
        val p = _playlists.value.firstOrNull { it.id == id } ?: return@launch
        val ctx = getApplication<Application>()
        withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(PlaylistFiles.export(p).toByteArray(Charsets.UTF_8)) } }
        }.onSuccess { toast("Saved “${p.name}” (${p.songs.size} songs)") }
            .onFailure { toast("Couldn't save it (${friendlyError(it)})") }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun toast(text: String) = android.widget.Toast.makeText(getApplication(), text, android.widget.Toast.LENGTH_LONG).show()
}
