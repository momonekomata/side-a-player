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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                updateBrowse(isNas) { it.copy(loading = false, error = e.findUnpinned()?.let { "Certificate not trusted yet" } ?: e.message ?: e.javaClass.simpleName) }
                if (isNas) _ui.value.nasServer?.let { failNas(it, e) }
            }
        }
    }

    override fun onCleared() {
        streaming.stop()
    }
}
