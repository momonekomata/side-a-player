package io.github.momonekomata.sidea.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import io.github.momonekomata.sidea.audio.EngineState
import io.github.momonekomata.sidea.sources.ArtworkStore
import io.github.momonekomata.sidea.sources.TagStore
import io.github.momonekomata.sidea.sources.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL

/**
 * Casting to a UPnP/DLNA renderer (a Chord Poly on the phone's hotspot, say): finding renderers,
 * handing each song over by URL (the phone's own web server, [MediaServer], serves it), keeping
 * the deck in step with what the renderer reports, and queuing the next song on it for gapless.
 * The renderer decodes the file itself, so the music reaches it exactly as it is on disk; the
 * phone's EQ doesn't apply while casting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Cast {
    /** A renderer found on one of the phone's networks. */
    data class Device(val name: String, val location: String, val host: Inet4Address, val desc: Upnp.Description) {
        val key get() = desc.udn.ifEmpty { location }
        /** "hotspot · 192.168.43.17" */
        val where get() = (Lan.networks().firstOrNull { it.contains(host) }?.kind ?: "network") + " · " + host.hostAddress
    }

    private lateinit var appContext: Context
    /** Commands to the renderer, one at a time, in order. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    /** What the last search looked through: "Looked on hotspot 192.168.43.1/24 and Wi-Fi …". */
    private val _searchNote = MutableStateFlow<String?>(null)
    val searchNote: StateFlow<String?> = _searchNote.asStateFlow()
    private val _target = MutableStateFlow<Device?>(null)
    val target: StateFlow<Device?> = _target.asStateFlow()
    private val _state = MutableStateFlow(EngineState())
    /** The renderer's playback, as the deck shows it. */
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _volume = MutableStateFlow(-1)
    /** The renderer's volume 0..100, or -1 when it has none (or hasn't said). */
    val volume: StateFlow<Int> = _volume.asStateFlow()

    val active get() = _target.value != null

    /** The renderer stopped at the end of a song with nothing queued after it. Called on the main thread. */
    var onEnded: () -> Unit = {}
    /** The renderer moved on to the song queued with [setNext] by itself. Called on the main thread. */
    var onAdvanced: () -> Unit = {}

    @Volatile private var currentUri: String? = null
    @Volatile private var nextUri: String? = null
    @Volatile private var wasPlaying = false
    /** Until when a STOPPED is the renderer still settling after a command, not the end of a song. */
    @Volatile private var quietUntil = 0L
    @Volatile private var polledPos = 0L
    @Volatile private var polledAt = 0L
    private var pollJob: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** A song has been handed to the renderer (so Play can just resume it). */
    val loaded get() = currentUri != null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---- finding renderers ----

    /**
     * Looks for renderers on every network the phone is on, the hotspot included. [manual] is an
     * address typed in ("192.168.43.17", "192.168.43.17:49152" or the description's URL) for a
     * renderer that doesn't answer a search sent everywhere.
     */
    fun search(manual: String? = null) {
        if (_searching.value) return
        _searching.value = true
        bg.launch {
            val wifi = appContext.getSystemService(WifiManager::class.java)
            val lock = wifi?.createMulticastLock("walkdeck-cast")?.apply { setReferenceCounted(false); acquire() }
            try {
                val typed = manual?.trim()?.takeIf { it.isNotEmpty() }
                val urls = LinkedHashSet<String>()
                val extra = ArrayList<Inet4Address>()
                if (typed != null) {
                    if (typed.startsWith("http")) {
                        urls += typed
                    } else {
                        val host = typed.substringBefore(':').substringBefore('/')
                        (runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address)?.let { extra += it }
                        typed.substringAfter(':', "").substringBefore('/').toIntOrNull()?.let { port ->
                            listOf("/description.xml", "/rootDesc.xml", "/DeviceDescription.xml", "/upnp/description.xml", "/").forEach { urls += "http://$host:$port$it" }
                        }
                    }
                }
                val nets = Lan.networks()
                urls += Lan.search(timeoutMs = 3500, extra = extra)
                val found = urls.mapNotNull { runCatching { describe(it) }.getOrNull() }
                _devices.value = (found + listOfNotNull(_target.value)).distinctBy { it.key }.sortedBy { it.name.lowercase() }
                _searchNote.value = if (nets.isEmpty()) {
                    "The phone isn't on any Wi-Fi or hotspot network right now."
                } else {
                    "Looked on " + nets.joinToString(" and ") { "${it.kind} ${it.address.hostAddress}/${it.prefix}" } + "."
                }
            } finally {
                lock?.release()
                _searching.value = false
            }
        }
    }

    private fun describe(location: String): Device? {
        val url = URL(location)
        val r = Lan.http(url, timeoutMs = 4000)
        if (r.code != 200) return null
        val d = Upnp.parseDescription(r.body, url) ?: return null
        val host = InetAddress.getByName(url.host) as? Inet4Address ?: return null
        return Device(d.name, location, host, d)
    }

    // ---- connecting ----

    fun connect(d: Device) {
        _target.value = d
        currentUri = null
        nextUri = null
        wasPlaying = false
        _state.value = EngineState()
        MediaServer.start(appContext)
        holdAwake(true)
        io.launch { runCatching { _volume.value = getVolume(d) } }
        pollJob?.cancel()
        pollJob = io.launch { pollLoop() }
    }

    /** Stops the renderer and lets it go. */
    fun disconnect() {
        val d = _target.value ?: return
        pollJob?.cancel()
        _target.value = null
        currentUri = null
        nextUri = null
        _state.value = EngineState()
        _volume.value = -1
        holdAwake(false)
        io.launch { runCatching { avt(d, "Stop") } }
    }

    private fun holdAwake(on: Boolean) {
        if (on) {
            if (wake == null) {
                wake = appContext.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walkdeck:cast")?.apply { setReferenceCounted(false); acquire() }
            }
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                wifiLock = appContext.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "walkdeck:cast")?.apply { setReferenceCounted(false); acquire() }
            }
        } else {
            runCatching { wake?.release() }
            runCatching { wifiLock?.release() }
            wake = null
            wifiLock = null
        }
    }

    // ---- playing ----

    /**
     * Hands [track] to the renderer and plays it from [startMs] (or leaves it paused there), with
     * [next] queued after it for gapless.
     */
    fun load(track: Track, startMs: Long, paused: Boolean, next: Track?) {
        val d = _target.value ?: return
        quietUntil = System.currentTimeMillis() + 8000
        wasPlaying = false
        _state.value = EngineState(isBuffering = true, positionMs = startMs, durationMs = _state.value.durationMs.takeIf { currentUri != null } ?: 0)
        io.launch {
            try {
                val (uri, meta) = item(d, track)
                avt(d, "SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to meta)
                currentUri = uri
                nextUri = null
                avt(d, "Play", "Speed" to "1")
                if (startMs > 1500) {
                    waitForPlaying(d)
                    runCatching { avt(d, "Seek", "Unit" to "REL_TIME", "Target" to Upnp.formatTime(startMs)) }
                }
                if (paused) avt(d, "Pause")
                polledPos = startMs
                polledAt = System.currentTimeMillis()
                quietUntil = System.currentTimeMillis() + 5000
                queueNext(d, next)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { it.copy(isBuffering = false, isPlaying = false, error = "${d.name}: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    /** Queues [track] on the renderer to follow the current song (or clears it with null). */
    fun setNext(track: Track?) {
        val d = _target.value ?: return
        if (currentUri == null) return
        io.launch { runCatching { queueNext(d, track) } }
    }

    private fun queueNext(d: Device, track: Track?) {
        if (track == null) {
            if (nextUri != null) runCatching { avt(d, "SetNextAVTransportURI", "NextURI" to "", "NextURIMetaData" to "") }
            nextUri = null
            return
        }
        val (uri, meta) = item(d, track)
        if (uri == nextUri) return
        // Renderers without gapless don't know this action: the song after then starts when
        // this one ends (see the poll).
        nextUri = runCatching { avt(d, "SetNextAVTransportURI", "NextURI" to uri, "NextURIMetaData" to meta); uri }.getOrNull()
    }

    fun resume() = command { d ->
        avt(d, "Play", "Speed" to "1")
        _state.update { it.copy(isPlaying = true) }
        polledAt = System.currentTimeMillis()
    }

    fun pause() = command { d ->
        refresh()
        avt(d, "Pause")
        polledPos = _state.value.positionMs
        _state.update { it.copy(isPlaying = false) }
    }

    fun seek(ms: Long) {
        _state.update { it.copy(positionMs = ms) }
        polledPos = ms
        polledAt = System.currentTimeMillis()
        quietUntil = System.currentTimeMillis() + 4000
        command { d -> avt(d, "Seek", "Unit" to "REL_TIME", "Target" to Upnp.formatTime(ms)) }
    }

    fun stopRemote() = command { d ->
        quietUntil = System.currentTimeMillis() + 4000
        avt(d, "Stop")
        currentUri = null
        nextUri = null
        _state.update { it.copy(isPlaying = false) }
    }

    fun setVolume(v: Int) {
        val c = v.coerceIn(0, 100)
        _volume.value = c
        command { d -> rc(d, "SetVolume", "Channel" to "Master", "DesiredVolume" to c.toString()) }
    }

    private fun command(block: (Device) -> Unit) {
        val d = _target.value ?: return
        io.launch {
            try {
                block(d)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { it.copy(error = "${d.name}: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    /** Moves the position on by the time since the last poll, so the deck's counter runs smoothly. */
    fun refresh() {
        val s = _state.value
        if (!s.isPlaying || polledAt == 0L) return
        val pos = polledPos + (System.currentTimeMillis() - polledAt)
        _state.value = s.copy(positionMs = if (s.durationMs > 0) minOf(pos, s.durationMs) else pos)
    }

    // ---- following the renderer ----

    private suspend fun pollLoop() {
        var fails = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val d = _target.value ?: return
            try {
                poll(d)
                fails = 0
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (++fails >= 4) _state.update { it.copy(isPlaying = false, error = "Can't reach ${d.name} (${t.message ?: "no answer"})") }
            }
            delay(1000)
        }
    }

    private suspend fun poll(d: Device) {
        val transport = avt(d, "GetTransportInfo")["CurrentTransportState"].orEmpty()
        val pos = avt(d, "GetPositionInfo")
        val rel = Upnp.parseTime(pos["RelTime"])
        val dur = Upnp.parseTime(pos["TrackDuration"])
        val uri = pos["TrackURI"].orEmpty()
        val now = System.currentTimeMillis()
        // Moved on by itself to the song queued next: gapless, as it should.
        val queued = nextUri
        if (queued != null && uri == queued) {
            currentUri = queued
            nextUri = null
            withContext(Dispatchers.Main) { onAdvanced() }
        }
        val playing = transport == "PLAYING"
        if (wasPlaying && (transport == "STOPPED" || transport == "NO_MEDIA_PRESENT") && now > quietUntil && currentUri != null) {
            // The song ran out with nothing queued after it on the renderer.
            wasPlaying = false
            currentUri = null
            withContext(Dispatchers.Main) { onEnded() }
            return
        }
        if (playing) wasPlaying = true else if (transport == "PAUSED_PLAYBACK") wasPlaying = false
        polledPos = if (rel >= 0) rel else polledPos
        polledAt = now
        _state.value = EngineState(
            isPlaying = playing,
            isBuffering = transport == "TRANSITIONING",
            positionMs = polledPos,
            durationMs = if (dur > 0) dur else _state.value.durationMs,
        )
    }

    private fun waitForPlaying(d: Device) {
        val until = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < until) {
            if (runCatching { avt(d, "GetTransportInfo")["CurrentTransportState"] }.getOrNull() == "PLAYING") return
            Thread.sleep(250)
        }
    }

    // ---- the song as the renderer gets it ----

    private fun item(d: Device, track: Track): Pair<String, String> {
        val token = MediaServer.register(track)
        val local = Lan.localFor(d.host) ?: Lan.networks().firstOrNull()?.address ?: throw java.io.IOException("The phone has no network the renderer is on")
        val mime = MediaServer.mimeFor(token)
        val url = MediaServer.songUrl(token, local) + "." + extensionFor(mime)
        val tags = TagStore.tags.value[track.id]
        val art = if (ArtworkStore.cached(track) != null) MediaServer.artUrl(token, local) else null
        val meta = Upnp.didl(
            title = tags?.title?.takeIf { it.isNotBlank() } ?: track.title,
            artist = tags?.artist?.takeIf { it.isNotBlank() } ?: track.artist,
            album = tags?.album?.takeIf { it.isNotBlank() } ?: track.album,
            url = url, mime = mime, durationMs = 0, sizeBytes = track.sizeBytes, artUrl = art,
        )
        return url to meta
    }

    private fun extensionFor(mime: String) = when (mime) {
        "audio/flac" -> "flac"
        "audio/wav" -> "wav"
        "audio/aiff" -> "aiff"
        "audio/mp4" -> "m4a"
        "audio/ogg" -> "ogg"
        "audio/aac" -> "aac"
        "audio/dsf" -> "dsf"
        "audio/dff" -> "dff"
        else -> "mp3"
    }

    // ---- SOAP ----

    private fun avt(d: Device, action: String, vararg args: Pair<String, String>) =
        soap(d.desc.avTransport, action, listOf("InstanceID" to "0") + args)

    private fun rc(d: Device, action: String, vararg args: Pair<String, String>): Map<String, String> {
        val s = d.desc.renderingControl ?: throw java.io.IOException("No volume control")
        return soap(s, action, listOf("InstanceID" to "0") + args)
    }

    private fun getVolume(d: Device) = rc(d, "GetVolume", "Channel" to "Master")["CurrentVolume"]?.toIntOrNull() ?: -1

    private fun soap(service: Upnp.Service, action: String, args: List<Pair<String, String>>): Map<String, String> {
        val body = Upnp.soapEnvelope(service.type, action, args).toByteArray(Charsets.UTF_8)
        val r = Lan.http(
            service.controlUrl, "POST",
            mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"${service.type}#$action\""),
            body,
        )
        return when (val res = Upnp.parseSoap(r.body)) {
            is Upnp.SoapResult.Ok -> if (r.code in 200..299) res.values else throw java.io.IOException("$action: HTTP ${r.code}")
            is Upnp.SoapResult.Fault -> throw java.io.IOException("$action refused (${res.code} ${res.description})".trim())
        }
    }
}
