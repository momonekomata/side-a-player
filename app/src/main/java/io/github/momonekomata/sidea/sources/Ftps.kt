package io.github.momonekomata.sidea.sources

import android.content.Context
import android.media.MediaDataSource
import io.github.momonekomata.sidea.audio.PlayableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Opens a logged-in, private-data-channel FTPS session. Caller must [disconnectQuietly]. */
fun NasServer.connectFtps(): FTPSClient {
    val tm = PinningTrustManager(allowSelfSigned, pinnedSha256)
    val ftp = FTPSClient("TLS", implicitTls).apply {
        trustManager = tm
        controlEncoding = "UTF-8"
        setAutodetectUTF8(true)
        connectTimeout = 10_000
        setDataTimeout(java.time.Duration.ofSeconds(30))
    }
    val p = if (port > 0) port else if (implicitTls) 990 else 21
    ftp.connect(address, p)
    if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
        ftp.disconnectQuietly()
        throw IOException("FTPS server refused the connection: ${ftp.replyString.trim()}")
    }
    if (!ftp.login(username.ifEmpty { "anonymous" }, password)) {
        ftp.disconnectQuietly()
        throw IOException("Wrong user name or password")
    }
    ftp.execPBSZ(0)
    ftp.execPROT("P")
    ftp.enterLocalPassiveMode()
    ftp.setFileType(FTP.BINARY_FILE_TYPE)
    return ftp
}

fun FTPSClient.disconnectQuietly() {
    runCatching { if (isConnected) { logout(); disconnect() } }
}

/**
 * FTPS has no cheap random access, so a song is downloaded to the cache and played while the
 * download is still running ([GrowingFileDataSource]). Finished files stay cached for replays.
 */
class FtpsBrowser(private val context: Context, private val server: NasServer) : Browser {
    override val title = server.name
    override val rootPath: String = server.basePath.ifBlank { "/" }

    override suspend fun list(path: String): List<Entry> = withContext(Dispatchers.IO) {
        val ftp = server.connectFtps()
        try {
            val files = ftp.listFiles(path) ?: emptyArray()
            if (!FTPReply.isPositiveCompletion(ftp.replyCode) && files.isEmpty()) {
                throw IOException("Listing $path failed: ${ftp.replyString.trim()}")
            }
            val lrc = files.filter { it.isFile && isLrcFile(it.name) }
                .associate { lrcKey(it.name) to path.trimEnd('/') + "/" + it.name }
            val cover = pickFolderCover(files.filter { it.isFile }.map { it.name })?.let { path.trimEnd('/') + "/" + it }
            val folderArt: ((Context) -> ByteArray)? = cover?.let { c -> { fetchBytes(c) } }
            files.filter { it.name != "." && it.name != ".." }.mapNotNull { f ->
                val full = path.trimEnd('/') + "/" + f.name
                when {
                    f.isDirectory -> Entry.Folder(f.name, full)
                    isAudioFile(f.name) -> Entry.Song(
                        f.name,
                        Track(
                            id = "ftps://${server.id}$full",
                            title = titleFromFileName(f.name),
                            album = path.trimEnd('/').substringAfterLast('/'),
                            sizeBytes = f.size,
                            opener = { open(full, f.size) },
                            // Tags only once the song is cached: FTPS can't fetch just the header cheaply.
                            tagSource = { cachedFile(full, f.size)?.let(::openFileAccess) },
                            lyricsFile = lrc[lrcKey(f.name)]?.let { lrcPath -> { fetchText(lrcPath) } },
                            folderArt = folderArt,
                        ),
                    )
                    else -> null
                }
            }.sortedForBrowsing()
        } finally {
            ftp.disconnectQuietly()
        }
    }

    private val cacheDir get() = File(context.cacheDir, "ftps").apply { mkdirs() }

    private fun cacheFile(path: String): File {
        val key = MessageDigest.getInstance("SHA-1").digest("${server.id}$path".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, key + "." + path.substringAfterLast('.', "bin"))
    }

    private fun cachedFile(path: String, size: Long) = cacheFile(path).takeIf { size > 0 && it.length() == size }

    private fun fetchText(path: String): String = fetchBytes(path).toString(Charsets.UTF_8)

    private fun fetchBytes(path: String): ByteArray = withFtps { ftp ->
        val out = java.io.ByteArrayOutputStream()
        if (!ftp.retrieveFile(path, out)) throw IOException("Can't read $path: ${ftp.replyString.trim()}")
        out.toByteArray()
    }

    private inline fun <T> withFtps(block: (FTPSClient) -> T): T {
        val ftp = server.connectFtps()
        try {
            return block(ftp)
        } finally {
            ftp.disconnectQuietly()
        }
    }

    private fun open(path: String, size: Long): PlayableSource {
        val file = cacheFile(path)
        if (file.length() == size && size > 0) {
            file.setLastModified(System.currentTimeMillis())
            return PlayableSource.File(file.path)
        }
        trimCache(cacheDir, keep = file)
        return PlayableSource.Stream(GrowingFileDataSource(file, size) { sink -> download(path, sink) })
    }

    private fun download(path: String, sink: (ByteArray, Int) -> Boolean) {
        val ftp = server.connectFtps()
        try {
            val input = ftp.retrieveFileStream(path) ?: throw IOException("Can't open $path: ${ftp.replyString.trim()}")
            input.use {
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    if (!sink(buf, n)) return // playback moved on
                }
            }
            ftp.completePendingCommand()
        } finally {
            ftp.disconnectQuietly()
        }
    }

    /** Keeps the cache under ~2 GB by dropping the least recently played songs. */
    private fun trimCache(dir: File, keep: File) {
        val files = dir.listFiles()?.filter { it != keep }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 2L * 1024 * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
    }
}

/**
 * A data source over a file that a background thread is still writing. Reads past the
 * downloaded part wait for it, so playback starts after the first few hundred kilobytes.
 */
class GrowingFileDataSource(
    private val file: File,
    private val expectedSize: Long,
    private val fetch: ((ByteArray, Int) -> Boolean) -> Unit,
) : MediaDataSource() {
    private val lock = Object()
    private var written = 0L
    private var done = false
    private var failure: Throwable? = null
    @Volatile private var closed = false
    private val partial = File(file.path + ".part")
    private val reader: RandomAccessFile

    init {
        partial.delete()
        RandomAccessFile(partial, "rw").close()
        reader = RandomAccessFile(partial, "r")
        Thread({
            try {
                RandomAccessFile(partial, "rw").use { out ->
                    fetch(sink@{ buf, n ->
                        if (closed) return@sink false
                        out.write(buf, 0, n)
                        synchronized(lock) { written += n; lock.notifyAll() }
                        true
                    })
                }
                if (!closed) partial.renameTo(file)
            } catch (t: Throwable) {
                failure = t
            } finally {
                synchronized(lock) { done = true; lock.notifyAll() }
            }
        }, "SideA-ftps").start()
    }

    override fun getSize(): Long = expectedSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (expectedSize in 0..position) return -1
        synchronized(lock) {
            while (!done && !closed && written < position + size && written < expectedSize) lock.wait(200)
            failure?.let { if (written <= position) throw IOException("Download failed", it) }
            if (written <= position) return -1
        }
        val n = minOf(size.toLong(), written - position).toInt()
        // The reader's file handle survives the rename on Linux, so this stays valid.
        reader.seek(position)
        return reader.read(buffer, offset, n)
    }

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        runCatching { reader.close() }
    }
}
