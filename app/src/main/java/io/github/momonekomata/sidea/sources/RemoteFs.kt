package io.github.momonekomata.sidea.sources

import android.content.Context
import android.media.MediaDataSource
import io.github.momonekomata.sidea.audio.PlayableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * One file or folder on a remote store. [id] is how the store finds it again: a path for SMB,
 * SFTP, FTP and Dropbox, an item id for Google Drive and OneDrive.
 */
data class RemoteItem(val name: String, val id: String, val isFolder: Boolean, val size: Long, val modified: Long = 0)

/**
 * The few things Side A needs from a file store: list a folder, read bytes from an offset, and
 * write a small file (a .lrc). Everything else (the cache, tags, lyrics, covers, search) is
 * shared, in [RemoteBrowser]. Calls block; they run off the main thread.
 */
interface RemoteFs {
    /** Where browsing starts. */
    val root: String

    /** Whether [read] can start anywhere (parallel downloads, tags read without downloading). */
    val ranges: Boolean

    fun list(folder: String): List<RemoteItem>

    /** Feeds bytes [from, to) of [id] to [sink] until it returns false; [to] -1 means the end. */
    fun read(id: String, from: Long, to: Long, sink: (ByteArray, Int) -> Boolean)

    /** Writes [bytes] as [name] in [folder], replacing a file of that name. */
    fun write(folder: String, name: String, bytes: ByteArray)
}

fun RemoteFs.readAll(id: String): ByteArray {
    val out = ByteArrayOutputStream()
    read(id, 0, -1) { b, n -> out.write(b, 0, n); true }
    return out.toByteArray()
}

fun RemoteFs.readRange(id: String, from: Long, length: Int): ByteArray {
    val out = ByteArrayOutputStream(length)
    read(id, from, from + length) { b, n ->
        out.write(b, 0, minOf(n, length - out.size()))
        out.size() < length
    }
    return out.toByteArray()
}

/** Copies an input stream to a sink in 256 KB pieces; stops when the sink says so or [limit] is read. */
fun java.io.InputStream.pump(limit: Long, sink: (ByteArray, Int) -> Boolean) {
    val buf = ByteArray(256 * 1024)
    var left = limit
    while (left != 0L) {
        val want = if (left < 0) buf.size else minOf(buf.size.toLong(), left).toInt()
        val n = read(buf, 0, want)
        if (n < 0) break
        if (left > 0) left -= n
        if (!sink(buf, n)) return
    }
}

/**
 * A song's id says which server, which folder, which file and its name, so it can be found
 * again from favourites, playlists and a saved search index: `smb://<server>:<folder>|<id>|<name>`.
 */
object RemoteIds {
    fun make(server: NasServer, folder: String, id: String, name: String) =
        "${server.kind.scheme}://${server.id}:" + listOf(folder, id, name).joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    /** (server id, folder, id, name), or null if [trackId] isn't one of these. */
    fun parse(trackId: String): List<String>? {
        val scheme = trackId.substringBefore("://", "")
        if (NasKind.entries.none { it.remote && it.scheme == scheme }) return null
        val rest = trackId.substringAfter("://")
        val server = rest.substringBefore(':')
        val parts = rest.substringAfter(':').split('|')
        if (parts.size != 3) return null
        return listOf(server) + parts.map { URLDecoder.decode(it, "UTF-8") }
    }
}

/** Browses any [RemoteFs]: folders, songs, their .lrc files and folder covers. */
class RemoteBrowser(private val context: Context, private val server: NasServer, private val fs: RemoteFs) : Browser {
    override val title = server.name
    override val rootPath: String = fs.root

    override suspend fun list(path: String): List<Entry> = withContext(Dispatchers.IO) {
        val items = fs.list(path)
        val files = items.filter { !it.isFolder }
        val lrc = files.filter { isLrcFile(it.name) }.associate { lrcKey(it.name) to it.id }
        val cover = pickFolderCover(files.map { it.name })?.let { n -> files.first { it.name == n }.id }
        val folderArt: ((Context) -> ByteArray)? = cover?.let { c -> { fs.readAll(c) } }
        // Paths name their folder; item ids (Drive, OneDrive) don't, and tags fill the album in anyway.
        val folderName = if (path.contains('/')) path.trimEnd('/').substringAfterLast('/') else ""
        items.mapNotNull { f ->
            when {
                f.isFolder -> Entry.Folder(f.name, f.id)
                isAudioFile(f.name) -> Entry.Song(
                    f.name,
                    trackFor(path, f.id, f.name, f.size, folderName).copy(
                        lyricsFile = lrc[lrcKey(f.name)]?.let { id -> { fs.readAll(id).toString(Charsets.UTF_8) } },
                        folderArt = folderArt,
                        modifiedMs = f.modified,
                    ),
                )
                else -> null
            }
        }.sortedForBrowsing()
    }

    fun trackFor(folder: String, id: String, name: String, size: Long, album: String) = Track(
        id = RemoteIds.make(server, folder, id, name),
        title = titleFromFileName(name),
        album = album,
        sizeBytes = size,
        opener = { ctx -> open(ctx, folder, id, name, size) },
        // With ranges, tags come from a few small reads; otherwise once the song is cached.
        tagSource = { ctx ->
            if (fs.ranges && size > 0) MediaDataSourceAccess(RangeReadDataSource(size) { pos, len -> fs.readRange(id, pos, len) })
            else cacheFile(ctx, folder, id, name).takeIf { size > 0 && it.length() == size }?.let(::openFileAccess)
        },
        lyricsWriter = { _, text -> fs.write(folder, lrcName(name), text.toByteArray()) },
        cacheFile = { ctx -> cacheFile(ctx, folder, id, name) },
    )

    override fun songFromIndex(id: String, name: String, size: Long, folderPath: String, folderName: String): Track? {
        val p = RemoteIds.parse(id) ?: return null
        if (p[0] != server.id) return null
        return trackFor(p[1], p[2], p[3], size, folderName)
    }

    private fun cacheFile(ctx: Context, folder: String, id: String, name: String) =
        MediaCache.file(ctx, RemoteIds.make(server, folder, id, name), name.substringAfterLast('.', "bin"))

    private fun open(ctx: Context, folder: String, id: String, name: String, size: Long): PlayableSource = MediaCache.open(
        cacheFile(ctx, folder, id, name),
        size,
        ranged = if (fs.ranges) { from, to, sink -> fs.read(id, from, to, sink) } else null,
    ) { sink -> fs.read(id, 0, -1, sink) }
}

/**
 * A data source over positioned reads, a window at a time: reading tags costs a couple of small
 * requests instead of the whole file.
 */
class RangeReadDataSource(
    private val total: Long,
    private val chunk: Int = 64 * 1024,
    private val fetch: (position: Long, length: Int) -> ByteArray,
) : MediaDataSource() {
    private var winStart = 0L
    private var window = ByteArray(0)

    override fun getSize(): Long = total

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= total) return -1
        if (position < winStart || position >= winStart + window.size) {
            window = fetch(position, minOf(chunk.toLong(), total - position).toInt())
            winStart = position
            if (window.isEmpty()) throw IOException("Nothing came back at $position")
        }
        val from = (position - winStart).toInt()
        val n = minOf(size, window.size - from)
        System.arraycopy(window, from, buffer, offset, n)
        return n
    }

    override fun close() {}
}

/** One store per server for the whole app, so browsing, playback and search share connections. */
object RemoteStores {
    private val stores = HashMap<String, Pair<NasServer, RemoteFs>>()

    /** [save] keeps what a store learns about the server (a pinned host key, a new token). */
    @Synchronized
    fun get(context: Context, server: NasServer, save: (NasServer) -> Unit): RemoteFs {
        stores[server.id]?.let { (s, fs) -> if (s == server) return fs }
        val fs = when (server.kind) {
            NasKind.SMB -> SmbFs(server)
            NasKind.SFTP -> SftpFs(server, save)
            NasKind.FTP -> FtpFs(server)
            NasKind.GDRIVE -> GoogleDriveFs(CloudAuth(server, save))
            NasKind.ONEDRIVE -> OneDriveFs(CloudAuth(server, save))
            NasKind.DROPBOX -> DropboxFs(CloudAuth(server, save))
            NasKind.WEBDAV, NasKind.FTPS -> error("${server.kind} has its own browser")
        }
        stores[server.id] = server to fs
        return fs
    }

    /** The server changed (edited, a token or host key saved): later calls use the new settings. */
    @Synchronized
    fun update(server: NasServer) {
        stores[server.id]?.let { (_, fs) -> stores[server.id] = server to fs }
    }

    @Synchronized
    fun forget(id: String) {
        stores.remove(id)
    }
}
