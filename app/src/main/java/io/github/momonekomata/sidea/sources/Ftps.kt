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
                        trackFor(full, f.size).copy(
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

    /** A playable track for [full] on this server; also used to bring back favourites. */
    fun trackFor(full: String, size: Long) = Track(
        id = "ftps://${server.id}$full",
        title = titleFromFileName(full.substringAfterLast('/')),
        album = full.substringBeforeLast('/').substringAfterLast('/'),
        sizeBytes = size,
        opener = { open(full, size) },
        // Tags only once the song is cached: FTPS can't fetch just the header cheaply.
        tagSource = { cachedFile(full, size)?.let(::openFileAccess) },
        lyricsWriter = { _, text ->
            val target = full.substringBeforeLast('/') + "/" + lrcName(full.substringAfterLast('/'))
            withFtps { ftp ->
                if (!ftp.storeFile(target, text.toByteArray().inputStream())) throw IOException("Can't save $target: ${ftp.replyString.trim()}")
            }
        },
    )

    override fun songFromIndex(id: String, name: String, size: Long, folderPath: String, folderName: String): Track? {
        val prefix = "ftps://${server.id}"
        if (!id.startsWith(prefix)) return null
        return trackFor(id.removePrefix(prefix), size)
    }

    private fun cacheFile(path: String): File = MediaCache.file(context, "${server.id}$path", path.substringAfterLast('.', "bin"))

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

    private fun open(path: String, size: Long): PlayableSource =
        MediaCache.open(cacheFile(path), size) { sink -> download(path, sink) }

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
}
