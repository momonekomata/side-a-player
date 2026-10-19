package io.github.momonekomata.sidea.sources

import android.media.MediaDataSource
import android.util.Xml
import io.github.momonekomata.sidea.audio.PlayableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.URLDecoder
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

private val PROPFIND_BODY = """
    <?xml version="1.0" encoding="utf-8"?>
    <d:propfind xmlns:d="DAV:"><d:prop>
      <d:displayname/><d:resourcetype/><d:getcontentlength/><d:getcontenttype/>
    </d:prop></d:propfind>
""".trimIndent()

fun NasServer.httpClient(): OkHttpClient {
    val tm = PinningTrustManager(allowSelfSigned, pinnedSha256)
    val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    return OkHttpClient.Builder()
        .sslSocketFactory(tm.socketFactory(), tm)
        .hostnameVerifier { host, session ->
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            tm.isPinned(leaf) || defaultVerifier.verify(host, session)
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

/** Paths are percent-encoded URL paths on the server, exactly as the server's hrefs give them. */
class WebDavBrowser(private val server: NasServer) : Browser {
    private val base: HttpUrl = server.address.trimEnd('/').plus("/").toHttpUrl()
    private val client = server.httpClient()
    private val auth = if (server.username.isNotEmpty()) Credentials.basic(server.username, server.password) else null

    override val title = server.name
    override val rootPath: String = base.encodedPath

    override suspend fun list(path: String): List<Entry> = withContext(Dispatchers.IO) {
        val url = base.newBuilder().encodedPath(path).build()
        val req = Request.Builder().url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .apply { auth?.let { header("Authorization", it) } }
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("Wrong user name or password")
            if (resp.code != 207) throw IOException("PROPFIND failed: HTTP ${resp.code}")
            parse(resp.body!!.byteStream(), path)
        }
    }

    private fun parse(stream: java.io.InputStream, requestPath: String): List<Entry> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(stream, null)
        }
        val out = mutableListOf<DavItem>()
        var href: String? = null
        var name: String? = null
        var isDir = false
        var size = -1L
        var mime: String? = null
        var text = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name) {
                        "response" -> { href = null; name = null; isDir = false; size = -1; mime = null }
                        "collection" -> isDir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "href" -> href = text.toString().trim()
                    "displayname" -> name = text.toString().trim().ifEmpty { null }
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: -1
                    "getcontenttype" -> mime = text.toString().trim()
                    "response" -> href?.let { out += DavItem(it, name, isDir, size, mime) }
                }
            }
        }
        val lrc = out.filter { !it.isDir && isLrcFile(it.displayName()) }
            .associate { lrcKey(it.displayName()) to it.path() }
        return out.mapNotNull { entryFor(it, requestPath, lrc) }.sortedForBrowsing()
    }

    private fun getText(url: HttpUrl): String {
        val req = Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} reading $url")
            return resp.body!!.string()
        }
    }

    private class DavItem(val href: String, val name: String?, val isDir: Boolean, val size: Long, val mime: String?) {
        // Some servers return absolute URLs in href; keep only the path.
        fun path() = if (href.startsWith("http")) href.toHttpUrl().encodedPath else href
        fun displayName() = name ?: URLDecoder.decode(path().trimEnd('/').substringAfterLast('/'), "UTF-8")
    }

    private fun entryFor(item: DavItem, requestPath: String, lrc: Map<String, String>): Entry? {
        val path = item.path()
        if (path.trimEnd('/') == requestPath.trimEnd('/')) return null
        val name = item.displayName()
        if (item.isDir) return Entry.Folder(name, path.trimEnd('/') + "/")
        if (!isAudioFile(name) && item.mime?.startsWith("audio/") != true) return null
        val size = item.size
        val url = base.newBuilder().encodedPath(path).build()
        val lrcUrl = lrc[lrcKey(name)]?.let { base.newBuilder().encodedPath(it).build() }
        val folder = URLDecoder.decode(requestPath.trimEnd('/').substringAfterLast('/'), "UTF-8")
        return Entry.Song(
            name,
            Track(
                id = url.toString(),
                title = titleFromFileName(name),
                album = folder,
                sizeBytes = size,
                opener = { PlayableSource.Stream(HttpRangeDataSource(client, url, auth, size)) },
                // Tags sit in the first few KB, so read them with small range requests.
                tagSource = { MediaDataSourceAccess(HttpRangeDataSource(client, url, auth, size, chunk = 64 * 1024)) },
                lyricsFile = lrcUrl?.let { u -> { getText(u) } },
            ),
        )
    }
}

/**
 * Lets MediaExtractor read a file on a WebDAV share with HTTP range requests, through our own
 * OkHttp client so the NAS credentials and pinned certificate apply. Reads come from a 2 MB
 * window that is refilled when the extractor moves outside it.
 */
class HttpRangeDataSource(
    private val client: OkHttpClient,
    private val url: HttpUrl,
    private val auth: String?,
    knownSize: Long,
    private val chunk: Int = 2 * 1024 * 1024,
) : MediaDataSource() {
    private var total = knownSize
    private var winStart = 0L
    private var window = ByteArray(0)

    @Synchronized
    override fun getSize(): Long {
        if (total < 0) {
            val req = Request.Builder().url(url).head().apply { auth?.let { header("Authorization", it) } }.build()
            client.newCall(req).execute().use { total = it.header("Content-Length")?.toLongOrNull() ?: -1 }
        }
        return total
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val len = getSize()
        if (len in 0..position) return -1
        if (position < winStart || position >= winStart + window.size) fill(position)
        val from = (position - winStart).toInt()
        val n = minOf(size, window.size - from)
        if (n <= 0) return -1
        System.arraycopy(window, from, buffer, offset, n)
        return n
    }

    private fun fill(position: Long) {
        val end = position + chunk - 1
        val req = Request.Builder().url(url)
            .header("Range", "bytes=$position-$end")
            .apply { auth?.let { header("Authorization", it) } }
            .build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                206 -> {
                    window = resp.body!!.bytes()
                    winStart = position
                }
                200 -> {
                    // Server ignored Range: skip forward on the full stream.
                    val body = resp.body!!.byteStream()
                    var skipped = 0L
                    while (skipped < position) {
                        val s = body.skip(position - skipped)
                        if (s <= 0) throw IOException("Server doesn't support seeking")
                        skipped += s
                    }
                    window = body.readNBytesCompat(chunk)
                    winStart = position
                }
                else -> throw IOException("HTTP ${resp.code} reading $url")
            }
        }
    }

    override fun close() {}

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }
}
