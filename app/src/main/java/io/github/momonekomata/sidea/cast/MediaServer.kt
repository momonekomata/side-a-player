package io.github.momonekomata.sidea.cast

import android.content.Context
import android.graphics.Bitmap
import io.github.momonekomata.sidea.audio.PlayableSource
import io.github.momonekomata.sidea.sources.ArtworkStore
import io.github.momonekomata.sidea.sources.Track
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The phone's little web server for casting: the renderer (a Chord Poly, say) fetches each song
 * from here, byte for byte as the file is, wherever it lives (the phone, a NAS, the cloud), and
 * decodes it itself. Ranges are served, so the renderer can seek; the cover is served too.
 */
object MediaServer {
    private const val PREFERRED_PORT = 52525
    private lateinit var appContext: Context
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "cast-http").apply { isDaemon = true } }
    private val songs = ConcurrentHashMap<String, Track>()
    private val mimes = ConcurrentHashMap<String, String>()

    val port: Int get() = server?.localPort ?: 0

    fun start(context: Context) {
        appContext = context.applicationContext
        if (server != null) return
        val s = runCatching { ServerSocket(PREFERRED_PORT, 16, null as InetAddress?) }.getOrElse { ServerSocket(0, 16, null as InetAddress?) }
        server = s
        thread(name = "cast-http-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: continue
                pool.execute { runCatching { c.use { serve(it) } } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** The song's token in URLs: stable for the song, so a renderer can come back for more. */
    fun register(track: Track): String {
        val token = "s" + Integer.toHexString(track.id.hashCode()).padStart(8, '0') + "-" + (track.sizeBytes and 0xFFFFFF).toString(16)
        songs[token] = track
        return token
    }

    fun songUrl(token: String, host: InetAddress) = "http://${host.hostAddress}:$port/song/$token"
    fun artUrl(token: String, host: InetAddress) = "http://${host.hostAddress}:$port/art/$token.jpg"

    /** The song's MIME type, from its first bytes (opening it if it hasn't been yet). */
    fun mimeFor(token: String): String {
        mimes[token]?.let { return it }
        val track = songs[token] ?: return "audio/mpeg"
        val m = runCatching { open(track).use { sniff(it) } }
            .onFailure { io.github.momonekomata.sidea.sources.MissingFiles.report(track.id, it) }
            .getOrNull() ?: guessFromName(track.id)
        mimes[token] = m
        return m
    }

    private val codecs = ConcurrentHashMap<String, String>()

    /** The song's codec as the Library names it ("FLAC", "ALAC", "AAC"…), "" when unknown. */
    fun codecFor(token: String): String {
        codecs[token]?.let { return it }
        val track = songs[token] ?: return ""
        val c = runCatching {
            open(track).use { b ->
                val ra = object : io.github.momonekomata.sidea.sources.RandomAccess {
                    override val size get() = b.size
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) = b.readAt(position, buffer, offset, length)
                    override fun close() = Unit
                }
                io.github.momonekomata.sidea.sources.FormatProbe.probe(ra, track.id)?.codec
            }
        }.getOrNull().orEmpty()
        codecs[token] = c
        return c
    }

    // ---- serving ----

    private fun serve(c: Socket) {
        c.soTimeout = 30_000
        val input = c.getInputStream().buffered()
        val request = readHead(input) ?: return
        val first = request.first().split(' ')
        val method = first.getOrNull(0) ?: return
        val path = first.getOrNull(1)?.substringBefore('?') ?: return
        val headers = request.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val out = BufferedOutputStream(c.getOutputStream(), 64 * 1024)
        when {
            path.startsWith("/song/") -> serveSong(path.removePrefix("/song/").substringBefore('.'), method, headers, out)
            path.startsWith("/art/") -> serveArt(path.removePrefix("/art/").substringBefore('.'), method, out)
            else -> status(out, 404, "Not Found")
        }
        out.flush()
    }

    private fun serveSong(token: String, method: String, headers: Map<String, String>, out: OutputStream) {
        val track = songs[token] ?: return status(out, 404, "Not Found")
        val opened = try {
            open(track)
        } catch (e: Exception) {
            io.github.momonekomata.sidea.sources.MissingFiles.report(track.id, e)
            throw e
        }
        opened.use { src ->
            val total = src.size
            val mime = mimes.getOrPut(token) { sniff(src) }
            var start = 0L
            var end = if (total > 0) total - 1 else -1L
            val range = headers["range"]?.removePrefix("bytes=")?.split(',')?.firstOrNull()
            val partial = range != null && total > 0
            if (partial) {
                val (a, b) = range!!.split('-').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
                if (a.isEmpty()) {
                    start = (total - (b.toLongOrNull() ?: 0)).coerceAtLeast(0)
                } else {
                    start = a.toLongOrNull() ?: 0
                    b.toLongOrNull()?.let { end = minOf(it, total - 1) }
                }
                if (start >= total) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
            }
            val head = StringBuilder()
                .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                .append("Content-Type: ").append(mime).append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("transferMode.dlna.org: Streaming\r\n")
                .append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                .append("Server: Android/14 UPnP/1.1 WALKDECK/1.0\r\n")
                .append("Connection: close\r\n")
            if (total > 0) {
                head.append("Content-Length: ").append(end - start + 1).append("\r\n")
                if (partial) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
            }
            head.append("\r\n")
            out.write(head.toString().toByteArray())
            if (method == "HEAD") return
            val buf = ByteArray(64 * 1024)
            var pos = start
            while (end < 0 || pos <= end) {
                val want = if (end < 0) buf.size else minOf(buf.size.toLong(), end - pos + 1).toInt()
                val n = src.readAt(pos, buf, 0, want)
                if (n <= 0) break
                out.write(buf, 0, n)
                pos += n
            }
        }
    }

    private fun serveArt(token: String, method: String, out: OutputStream) {
        val track = songs[token] ?: return status(out, 404, "Not Found")
        val bmp = ArtworkStore.cached(track) ?: return status(out, 404, "Not Found")
        val jpg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpg.size}\r\nConnection: close\r\n\r\n".toByteArray())
        if (method != "HEAD") out.write(jpg)
    }

    private fun status(out: OutputStream, code: Int, text: String) {
        out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    private fun readHead(input: java.io.InputStream): List<String>? {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (lines.isEmpty()) null else lines
            if (c == '\n'.code) {
                val l = line.toString().trimEnd('\r')
                if (l.isEmpty()) return lines
                lines += l
                line.setLength(0)
                if (lines.size > 100) return lines
            } else {
                line.append(c.toChar())
            }
        }
    }

    // ---- reading songs wherever they are ----

    /** Positioned reads over a song, whatever opened it. */
    interface Bytes : Closeable {
        val size: Long
        fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    }

    private fun open(track: Track): Bytes = when (val src = runBlocking { track.opener(appContext) }) {
        is PlayableSource.File -> {
            val f = RandomAccessFile(src.path, "r")
            channelBytes(f.channel, f)
        }
        is PlayableSource.Content -> {
            val pfd = appContext.contentResolver.openFileDescriptor(src.uri, "r") ?: throw java.io.IOException("Can't open ${src.uri}")
            val stream = FileInputStream(pfd.fileDescriptor)
            channelBytes(stream.channel, Closeable { stream.close(); pfd.close() })
        }
        is PlayableSource.Stream -> object : Bytes {
            override val size get() = src.dataSource.size
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) = src.dataSource.readAt(position, buffer, offset, length)
            override fun close() = src.dataSource.close()
        }
    }

    private fun channelBytes(ch: FileChannel, owner: Closeable) = object : Bytes {
        override val size get() = ch.size()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) = ch.read(ByteBuffer.wrap(buffer, offset, length), position)
        override fun close() = owner.close()
    }

    private fun sniff(src: Bytes): String {
        val head = ByteArray(16)
        var n = 0
        while (n < head.size) {
            val r = src.readAt(n.toLong(), head, n, head.size - n)
            if (r <= 0) break
            n += r
        }
        return mimeFromHeader(head.copyOf(n))
    }

    private fun guessFromName(id: String) = when (id.substringAfterLast('.', "").lowercase()) {
        "flac" -> "audio/flac"
        "wav", "wave" -> "audio/wav"
        "aif", "aiff", "aifc" -> "audio/aiff"
        "m4a", "alac", "mp4" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "aac" -> "audio/aac"
        "dsf" -> "audio/dsf"
        "dff" -> "audio/dff"
        else -> "audio/mpeg"
    }

    /** A song's MIME type from its first bytes. */
    fun mimeFromHeader(h: ByteArray): String {
        fun at(i: Int, s: String) = h.size >= i + s.length && s.indices.all { h[i + it] == s[it].code.toByte() }
        return when {
            at(0, "fLaC") -> "audio/flac"
            at(0, "RIFF") && at(8, "WAVE") -> "audio/wav"
            at(0, "FORM") && (at(8, "AIFF") || at(8, "AIFC")) -> "audio/aiff"
            at(0, "OggS") -> "audio/ogg"
            at(4, "ftyp") -> "audio/mp4"
            at(0, "DSD ") -> "audio/dsf"
            at(0, "FRM8") -> "audio/dff"
            at(0, "ID3") -> "audio/mpeg"
            h.size >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xF0) == 0xF0 ->
                // An ADTS AAC frame has layer bits 00; an MPEG audio frame doesn't.
                if ((h[1].toInt() and 0x06) == 0) "audio/aac" else "audio/mpeg"
            h.size >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
            else -> "audio/mpeg"
        }
    }
}
