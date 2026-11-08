package io.github.momonekomata.sidea.cast

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * The Google Cast (CASTV2) wire format: each message is a protobuf CastMessage behind a 4-byte
 * big-endian length, over TLS to port 8009. Only the fields casting needs are here.
 */
object CastWire {
    data class Message(val source: String, val destination: String, val namespace: String, val payload: String)

    /** One CastMessage, framed. */
    fun encode(m: Message): ByteArray {
        val body = ByteArrayOutputStream()
        fun varint(field: Int, v: Int) { body.write(field shl 3); writeVarint(body, v.toLong()) }
        fun string(field: Int, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            body.write((field shl 3) or 2)
            writeVarint(body, b.size.toLong())
            body.write(b)
        }
        varint(1, 0) // protocol_version CASTV2_1_0
        string(2, m.source)
        string(3, m.destination)
        string(4, m.namespace)
        varint(5, 0) // payload_type STRING
        string(6, m.payload)
        val b = body.toByteArray()
        return byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()) + b
    }

    /** Reads one CastMessage's body (the length already taken off). */
    fun decode(b: ByteArray): Message {
        var pos = 0
        val strings = HashMap<Int, String>()
        fun varint(): Long {
            var shift = 0
            var v = 0L
            while (pos < b.size) {
                val x = b[pos++].toInt() and 0xFF
                v = v or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) break
                shift += 7
            }
            return v
        }
        while (pos < b.size) {
            val key = varint().toInt()
            val field = key ushr 3
            when (key and 7) {
                0 -> varint()
                2 -> {
                    val len = varint().toInt()
                    if (field in 2..6) strings[field] = String(b, pos, len, Charsets.UTF_8)
                    pos += len
                }
                5 -> pos += 4
                1 -> pos += 8
                else -> break
            }
        }
        return Message(strings[2].orEmpty(), strings[3].orEmpty(), strings[4].orEmpty(), strings[6].orEmpty())
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7F.inv().toLong() == 0L) { out.write(v.toInt()); return }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}

/**
 * A session with one Cast device: the connection, its heartbeat, the Default Media Receiver
 * launched on it, and the media on that receiver. Status from the device arrives on [onStatus]
 * and [onReceiver], on the reading thread.
 */
class CastSession(private val host: Inet4Address, private val port: Int) {
    companion object {
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        /** Google's Default Media Receiver: plays a URL, no app of our own to register. */
        const val DEFAULT_RECEIVER = "CC1AD845"
        private const val SENDER = "sender-walkdeck"
        /** Longer than a few missed heartbeats (one each way every 5 s). */
        const val READ_TIMEOUT_MS = 20_000
    }

    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private val requestId = AtomicInteger(1)
    @Volatile var transportId: String? = null; private set
    @Volatile var sessionId: String? = null; private set
    @Volatile var mediaSessionId: Int? = null; private set
    @Volatile var closed = false; private set

    /** A MEDIA_STATUS entry from the receiver. */
    var onStatus: (JSONObject) -> Unit = {}
    /** A RECEIVER_STATUS (volume, running apps). */
    var onReceiver: (JSONObject) -> Unit = {}
    /**
     * The connection went. [lost] is true when it dropped (no answer, the network went) rather
     * than the device ending it on purpose (it closed the player, another app took over).
     */
    var onClosed: (why: String, lost: Boolean) -> Unit = { _, _ -> }

    /** Connects, launches the Default Media Receiver (or joins it if it runs) and waits until it's ready. */
    fun open(timeoutMs: Long = 15_000) {
        val raw = Socket()
        Lan.localFor(host)?.let { raw.bind(InetSocketAddress(it, 0)) }
        raw.connect(InetSocketAddress(host, port), 6000)
        // Cast devices present a certificate from Google's device CA, which no Android trust
        // store holds; the connection is to a device on the phone's own network, chosen by the user.
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(TrustAll), SecureRandom()) }
        val s = tls.socketFactory.createSocket(raw, host.hostAddress, port, true) as SSLSocket
        socket = s
        // Never wait for ever: a device that stops answering (the phone's Wi-Fi dozed, the device
        // restarted) would otherwise hold every command after it. The device pings every few
        // seconds and answers ours, so a quiet read means the connection is gone.
        s.soTimeout = READ_TIMEOUT_MS
        s.startHandshake()
        out = s.outputStream
        send("receiver-0", NS_CONNECTION, JSONObject().put("type", "CONNECT"))
        thread(name = "cast-read", isDaemon = true) { readLoop(DataInputStream(s.inputStream)) }
        thread(name = "cast-ping", isDaemon = true) {
            while (!closed) {
                runCatching { send("receiver-0", NS_HEARTBEAT, JSONObject().put("type", "PING")) }
                Thread.sleep(5000)
            }
        }
        send("receiver-0", NS_RECEIVER, JSONObject().put("type", "GET_STATUS").put("requestId", requestId.getAndIncrement()))
        val until = System.currentTimeMillis() + timeoutMs
        var launched = false
        while (transportId == null && System.currentTimeMillis() < until && !closed) {
            if (!launched && System.currentTimeMillis() > until - timeoutMs + 1500) {
                launched = true
                send("receiver-0", NS_RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", DEFAULT_RECEIVER).put("requestId", requestId.getAndIncrement()))
            }
            Thread.sleep(100)
        }
        val t = transportId ?: throw java.io.IOException(if (closed) "The device closed the connection" else "The device didn't start its media player")
        send(t, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    /** Ends the session from this side for [why], once, and says so. */
    private fun end(why: String, lost: Boolean) {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        runCatching { socket?.close() }
        onClosed(why, lost)
    }

    /** Plays [media] (a Cast MediaInformation) from [startSec], or leaves it paused there. */
    fun load(media: JSONObject, startSec: Double, autoplay: Boolean) = mediaRequest(
        JSONObject().put("type", "LOAD").put("media", media).put("autoplay", autoplay).put("currentTime", startSec),
        withMediaSession = false,
    )

    /** Queues [media] after the current item, preloaded so the change is gapless. */
    fun queueNext(media: JSONObject) = mediaRequest(
        JSONObject().put("type", "QUEUE_INSERT").put("items", JSONArray().put(JSONObject().put("media", media).put("autoplay", true).put("preloadTime", 20))),
    )

    /** Takes queued items out again (a song queued next that isn't next any more). */
    fun queueRemove(itemIds: List<Int>) = mediaRequest(
        JSONObject().put("type", "QUEUE_REMOVE").put("itemIds", JSONArray(itemIds)),
    )

    /** Closes the media player on the device, so it goes back to its own screen. */
    fun stopApp() {
        val s = sessionId ?: return
        send("receiver-0", NS_RECEIVER, JSONObject().put("type", "STOP").put("sessionId", s).put("requestId", requestId.getAndIncrement()))
    }

    fun play() = mediaRequest(JSONObject().put("type", "PLAY"))
    fun pause() = mediaRequest(JSONObject().put("type", "PAUSE"))
    fun seek(sec: Double) = mediaRequest(JSONObject().put("type", "SEEK").put("currentTime", sec))
    fun stop() = mediaRequest(JSONObject().put("type", "STOP"))
    fun getStatus() = mediaRequest(JSONObject().put("type", "GET_STATUS"), withMediaSession = false)

    fun setVolume(level: Double) = send(
        "receiver-0", NS_RECEIVER,
        JSONObject().put("type", "SET_VOLUME").put("volume", JSONObject().put("level", level.coerceIn(0.0, 1.0))).put("requestId", requestId.getAndIncrement()),
    )

    private fun mediaRequest(o: JSONObject, withMediaSession: Boolean = true) {
        val t = transportId ?: throw java.io.IOException("Not connected")
        o.put("requestId", requestId.getAndIncrement())
        sessionId?.let { o.put("sessionId", it) }
        if (withMediaSession) mediaSessionId?.let { o.put("mediaSessionId", it) }
        send(t, NS_MEDIA, o)
    }

    @Synchronized
    private fun send(destination: String, namespace: String, payload: JSONObject) {
        val o = out ?: throw java.io.IOException("Not connected")
        o.write(CastWire.encode(CastWire.Message(SENDER, destination, namespace, payload.toString())))
        o.flush()
    }

    private fun readLoop(input: DataInputStream) {
        try {
            while (!closed) {
                val len = input.readInt()
                if (len <= 0 || len > 4 * 1024 * 1024) throw java.io.IOException("Bad message length $len")
                val b = ByteArray(len)
                input.readFully(b)
                handle(CastWire.decode(b))
            }
        } catch (t: Throwable) {
            end(if (t is java.net.SocketTimeoutException) "it stopped answering" else "the connection dropped", lost = true)
        }
    }

    private fun handle(m: CastWire.Message) {
        val o = runCatching { JSONObject(m.payload) }.getOrNull() ?: return
        when (o.optString("type")) {
            "PING" -> runCatching { send(m.source, NS_HEARTBEAT, JSONObject().put("type", "PONG")) }
            "CLOSE" -> if (m.source == transportId) end("it closed the player", lost = false)
            "RECEIVER_STATUS" -> {
                val status = o.optJSONObject("status") ?: return
                val apps = status.optJSONArray("applications")
                val app = (0 until (apps?.length() ?: 0)).map { apps!!.getJSONObject(it) }.firstOrNull { it.optString("appId") == DEFAULT_RECEIVER }
                if (app != null) {
                    transportId = app.optString("transportId")
                    sessionId = app.optString("sessionId")
                } else if (transportId != null && apps != null && apps.length() > 0) {
                    // Another app took the device over.
                    onReceiver(status)
                    end("another app is using it now", lost = false)
                    return
                }
                onReceiver(status)
            }
            "MEDIA_STATUS" -> {
                val list = o.optJSONArray("status") ?: return
                if (list.length() == 0) return
                val s = list.getJSONObject(0)
                if (s.has("mediaSessionId")) mediaSessionId = s.optInt("mediaSessionId")
                onStatus(s)
            }
            "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" -> onStatus(JSONObject().put("playerState", "IDLE").put("idleReason", "ERROR").put("detail", o.optString("type") + " " + o.optString("reason")))
        }
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
