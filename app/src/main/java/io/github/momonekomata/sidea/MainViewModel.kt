package io.github.momonekomata.sidea

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.momonekomata.sidea.audio.Player
import io.github.momonekomata.sidea.sources.Browser
import io.github.momonekomata.sidea.sources.Entry
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
import android.graphics.Bitmap
import io.github.momonekomata.sidea.sources.ArtworkStore
import io.github.momonekomata.sidea.sources.TagStore
import kotlinx.coroutines.delay
import io.github.momonekomata.sidea.sources.guessArtistTitle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SourceTab(val label: String, val app: StreamingApp? = null) {
    LOCAL("Local"),
    NAS("NAS"),
    SPOTIFY("Spotify", StreamingApp.SPOTIFY),
    APPLE_MUSIC("Apple Music", StreamingApp.APPLE_MUSIC),
    TIDAL("TIDAL", StreamingApp.TIDAL),
    YT_MUSIC("YT Music", StreamingApp.YT_MUSIC),
}

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

data class CertPrompt(val server: NasServer, val sha256: String, val subject: String)

/** Lyrics for whatever the deck is showing. [key] identifies the song they belong to. */
data class LyricsState(val key: String? = null, val lyrics: Lyrics? = null, val loading: Boolean = false)

/** The song lyrics are wanted for, reduced to what identifies it. */
private sealed interface Playing {
    val key: String
    data class Local(val track: io.github.momonekomata.sidea.sources.Track, val durationMs: Long) : Playing {
        override val key get() = "local:${track.id}"
    }
    data class Remote(val app: StreamingApp, val title: String, val artist: String, val album: String, val durationMs: Long) : Playing {
        override val key get() = "remote:${app.name}:$artist:$title"
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

    private val _nowArt = MutableStateFlow<Bitmap?>(null)
    /** Cover of the local song playing. */
    val nowArt = _nowArt.asStateFlow()

    private val lyricsRepo = LyricsRepository(app)
    private val _lyrics = MutableStateFlow(LyricsState())
    val lyrics = _lyrics.asStateFlow()

    init {
        Player.init(app)
        _ui.update {
            it.copy(
                tab = runCatching { SourceTab.valueOf(prefs.getString("tab", null)!!) }.getOrDefault(SourceTab.LOCAL),
                nasServers = nasStore.all(),
            )
        }
        prefs.getString("localTree", null)?.let { openLocal(Uri.parse(it), remember = false) }
        onResume()
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
     * Looks lyrics up for the song on the deck: the local queue, or the streaming app on the
     * selected tab. Waits for the duration so the online match can use it.
     */
    private fun followLyrics() = viewModelScope.launch {
        combine(
            _ui.map { it.tab }.distinctUntilChanged(),
            Player.queue,
            Player.state.map { it.durationMs }.distinctUntilChanged(),
            streaming.nowPlaying,
        ) { tab, queue, duration, remote ->
            val app = tab.app
            if (app != null) {
                remote[app]?.takeIf { it.title.isNotBlank() }?.let { Playing.Remote(app, it.title, it.artist, it.album, it.durationMs) }
            } else {
                queue.current?.takeIf { duration > 0 }?.let { Playing.Local(it, duration) }
            }
        }
            .distinctUntilChanged { a, b -> a?.key == b?.key }
            .collectLatest { playing ->
                if (playing == null) {
                    _lyrics.value = LyricsState()
                    return@collectLatest
                }
                _lyrics.value = LyricsState(key = playing.key, loading = true)
                val found = lyricsRepo.find(query(playing), online = Settings.current.onlineLyrics)
                _lyrics.value = LyricsState(key = playing.key, lyrics = found)
            }
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

    override fun onCleared() {
        streaming.stop()
    }
}
