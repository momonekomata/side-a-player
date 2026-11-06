package io.github.momonekomata.sidea.library

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.momonekomata.sidea.sources.AudioFormat
import io.github.momonekomata.sidea.sources.Browser
import io.github.momonekomata.sidea.sources.Entry
import io.github.momonekomata.sidea.sources.FormatProbe
import io.github.momonekomata.sidea.sources.FtpsBrowser
import io.github.momonekomata.sidea.sources.LocalBrowser
import io.github.momonekomata.sidea.sources.NasKind
import io.github.momonekomata.sidea.sources.NasServer
import io.github.momonekomata.sidea.sources.NasStore
import io.github.momonekomata.sidea.sources.RemoteBrowser
import io.github.momonekomata.sidea.sources.RemoteStores
import io.github.momonekomata.sidea.sources.TagReader
import io.github.momonekomata.sidea.sources.Tags
import io.github.momonekomata.sidea.sources.Track
import io.github.momonekomata.sidea.sources.WebDavBrowser
import io.github.momonekomata.sidea.sources.titleFromFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A folder the Library is built from: on the phone ([tree] is the folder picked with Android's
 * picker) or on a NAS or cloud server ([serverId]); [root] is the folder's path or id there.
 */
data class LibrarySource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val tree: String? = null,
    val serverId: String? = null,
    val root: String,
    val lastScan: Long = 0,
    val lastError: String? = null,
) {
    val isLocal get() = tree != null

    fun toJson() = JSONObject().put("id", id).put("name", name).put("tree", tree ?: JSONObject.NULL)
        .put("serverId", serverId ?: JSONObject.NULL).put("root", root).put("lastScan", lastScan).put("lastError", lastError ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = LibrarySource(
            o.getString("id"), o.getString("name"),
            if (o.isNull("tree")) null else o.getString("tree"),
            if (o.isNull("serverId")) null else o.getString("serverId"),
            o.getString("root"), o.optLong("lastScan"),
            if (o.isNull("lastError")) null else o.optString("lastError"),
        )
    }
}

/** What the scanner is doing: which source, how many folders read and songs found so far. */
data class ScanState(val running: Boolean = false, val source: String = "", val folders: Int = 0, val songs: Int = 0, val read: Int = 0, val toRead: Int = 0)

/**
 * The Library: every song under the folders you point it at, with its tags and format, in a
 * database on the phone. Scanning lists each folder like browsing does, and reads tags and the
 * format only for songs that are new or changed size, a few at a time so a NAS stays responsive.
 * Songs that are gone from a source are dropped after a complete scan of it.
 */
object Library {
    private const val TAG = "WALKDECK.Library"
    /** Sources are scanned again on start once this old. */
    private const val STALE_MS = 12 * 60 * 60 * 1000L

    private lateinit var appContext: Context
    lateinit var db: LibraryDb
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private val waiting = LinkedHashSet<String>()

    private val _sources = MutableStateFlow<List<LibrarySource>>(emptyList())
    val sources: StateFlow<List<LibrarySource>> = _sources.asStateFlow()
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    /** Goes up whenever the database changes, so what's on screen asks again. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun init(context: Context) {
        if (::db.isInitialized) return
        appContext = context.applicationContext
        db = LibraryDb(appContext)
        _sources.value = runCatching {
            val a = JSONArray(prefs().getString("sources", "[]"))
            (0 until a.length()).map { LibrarySource.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        // Catch up on what changed since last time, quietly.
        val now = System.currentTimeMillis()
        _sources.value.filter { now - it.lastScan > STALE_MS }.forEach { scan(it.id) }
    }

    private fun prefs() = appContext.getSharedPreferences("library", 0)

    private fun saveSources(list: List<LibrarySource>) {
        _sources.value = list
        prefs().edit().putString("sources", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    private fun updateSource(id: String, f: (LibrarySource) -> LibrarySource) = saveSources(_sources.value.map { if (it.id == id) f(it) else it })

    /** Adds a folder (unless it's already there) and scans it. */
    fun add(source: LibrarySource) {
        if (_sources.value.any { it.tree == source.tree && it.serverId == source.serverId && it.root == source.root }) return
        saveSources(_sources.value + source)
        scan(source.id)
    }

    fun contains(tree: String?, serverId: String?, root: String) = _sources.value.any { it.tree == tree && it.serverId == serverId && it.root == root }

    fun remove(id: String) {
        saveSources(_sources.value.filterNot { it.id == id })
        browsers.remove(id)
        scope.launch {
            db.dropSource(id)
            _version.update { it + 1 }
        }
    }

    fun scanAll() = _sources.value.forEach { scan(it.id) }

    /** Queues a scan of [id]; one source is scanned at a time. */
    @Synchronized
    fun scan(id: String) {
        waiting += id
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            while (isActive) {
                val next = synchronized(this@Library) { waiting.firstOrNull()?.also { waiting.remove(it) } } ?: break
                val src = _sources.value.firstOrNull { it.id == next } ?: continue
                try {
                    scanSource(src)
                    updateSource(src.id) { it.copy(lastScan = System.currentTimeMillis(), lastError = null) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "scan of ${src.name} failed", e)
                    updateSource(src.id) { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
                }
            }
            _scan.value = ScanState()
            _version.update { it + 1 }
        }
    }

    private suspend fun scanSource(src: LibrarySource) {
        val browser = browserFor(src) ?: throw java.io.IOException("Its server was removed")
        val generation = System.currentTimeMillis()
        val known = db.known(src.id)
        val unchanged = ArrayList<String>()
        val todo = ArrayList<Triple<Entry.Song, String, String>>()
        var complete = true
        var folders = 0
        _scan.value = ScanState(true, src.name)
        // Breadth-first, one listing per folder, like opening each one.
        val queue = ArrayDeque(listOf(src.root to src.name))
        while (queue.isNotEmpty()) {
            val (path, name) = queue.removeFirst()
            val entries = try {
                browser.list(path)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // One unreadable folder doesn't stop the scan, but nothing is dropped this time.
                Log.w(TAG, "can't list $path", e)
                complete = false
                continue
            }
            folders++
            for (e in entries) {
                when (e) {
                    is Entry.Folder -> queue.addLast(e.path to e.name)
                    is Entry.Song -> {
                        val k = known[e.track.id]
                        if (k != null && k.first == e.track.sizeBytes && k.second) unchanged += e.track.id else todo += Triple(e, path, name)
                    }
                }
            }
            _scan.update { it.copy(folders = folders, songs = unchanged.size + todo.size) }
        }
        db.markSeen(unchanged, generation)
        _scan.update { it.copy(toRead = todo.size) }
        // Tags and format for the new and changed songs, a few at a time, saved in batches.
        val gate = Semaphore(3)
        todo.chunked(40).forEach { batch ->
            val rows = coroutineScope { batch.map { (song, folder, folderName) -> async { gate.withPermit { read(src, song, folder, folderName) } } }.awaitAll() }
            db.put(rows.map { it.first }, rows.map { it.second }, generation)
            _scan.update { it.copy(read = it.read + batch.size) }
            _version.update { it + 1 }
        }
        if (complete) db.dropUnseen(src.id, generation)
        _version.update { it + 1 }
    }

    /** A song's row, and whether its tags and format could be read (FTPS only once it's cached). */
    private fun read(src: LibrarySource, song: Entry.Song, folder: String, folderName: String): Pair<LibTrack, Boolean> {
        val t = song.track
        val ra = runCatching { t.tagSource?.invoke(appContext) }.getOrNull()
        val (tags, format) = ra?.use { r ->
            runCatching { TagReader.read(r) }.getOrNull() to runCatching { FormatProbe.probe(r, song.name) }.getOrNull()
        } ?: Pair<Tags?, AudioFormat?>(null, null)
        val ext = song.name.substringAfterLast('.', "").lowercase()
        val codec = format?.codec ?: when (ext) {
            "flac" -> "FLAC"; "wav", "wave" -> "WAV"; "aif", "aiff", "aifc" -> "AIFF"; "dsf", "dff" -> "DSD"
            "mp3" -> "MP3"; "ogg", "oga" -> "Vorbis"; "opus" -> "Opus"; "m4a", "alac" -> "M4A"; "aac" -> "AAC"
            else -> ext.uppercase()
        }
        return LibTrack(
            id = t.id, source = src.id, folder = folder, folderName = folderName, file = song.name, size = t.sizeBytes,
            title = tags?.title?.takeIf { it.isNotBlank() } ?: titleFromFileName(song.name),
            artist = tags?.artist.orEmpty(), albumArtist = tags?.albumArtist.orEmpty(),
            // Untagged songs take their folder's name as the album, as most folders are albums.
            album = tags?.album?.takeIf { it.isNotBlank() } ?: folderName,
            genre = tags?.genre.orEmpty(), year = tags?.year, track = tags?.trackNumber, disc = tags?.discNumber,
            durationMs = format?.durationMs ?: 0, codec = codec, sampleRate = format?.sampleRate ?: 0,
            bits = format?.bits, channels = format?.channels ?: 0,
            composer = tags?.composer.orEmpty(), label = tags?.label.orEmpty(), compilation = tags?.compilation == true,
        ) to (ra != null)
    }

    // ---- plays ----

    /** A song started playing (from anywhere): counted for Most played and the play filters. */
    fun played(t: Track, title: String, artist: String, album: String) {
        if (!::db.isInitialized) return
        scope.launch {
            runCatching { db.played(t.id, title, artist, album, t.sizeBytes) }
            _version.update { it + 1 }
        }
    }

    // ---- getting songs back to play ----

    private val browsers = HashMap<String, Browser>()

    @Synchronized
    fun browserFor(src: LibrarySource): Browser? {
        browsers[src.id]?.let { return it }
        val b: Browser = if (src.tree != null) {
            LocalBrowser(appContext, Uri.parse(src.tree))
        } else {
            val store = NasStore(appContext)
            val server = store.all().firstOrNull { it.id == src.serverId } ?: return null
            browserFor(server, store)
        }
        browsers[src.id] = b
        return b
    }

    private fun browserFor(server: NasServer, store: NasStore): Browser = when (server.kind) {
        NasKind.WEBDAV -> WebDavBrowser(server)
        NasKind.FTPS -> FtpsBrowser(appContext, server)
        else -> RemoteBrowser(appContext, server, RemoteStores.get(appContext, server) { s -> store.save(s); RemoteStores.update(s) })
    }

    /** A playable track for [t], with the Library's names on it. */
    fun trackOf(t: LibTrack): Track? {
        val src = _sources.value.firstOrNull { it.id == t.source } ?: return null
        val track = runCatching { browserFor(src)?.songFromIndex(t.id, t.file, t.size, t.folder, t.folderName) }.getOrNull() ?: return null
        return track.copy(title = t.title, artist = t.artist.ifBlank { t.albumArtist }, album = t.album)
    }
}
