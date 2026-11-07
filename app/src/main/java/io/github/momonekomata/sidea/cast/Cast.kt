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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Casting, to a UPnP/DLNA renderer (a Chord Poly on the phone's hotspot, say) or to a Google Cast
 * device (a Chromecast, a Nest speaker, a TV with Cast built in): finding them, handing each song
 * over by URL (the phone's own web server, [MediaServer], serves it), keeping the deck in step
 * with what the device reports, and queuing the next song on it for gapless. The device decodes
 * the file itself, so the music reaches it exactly as it is on disk; the phone's EQ doesn't apply
 * while casting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Cast {
    enum class Kind { UPNP, GOOGLE_CAST }

    /** A device found on one of the phone's networks. [desc] is a UPnP renderer's description. */
    data class Device(
        val name: String,
        val location: String,
        val host: Inet4Address,
        val desc: Upnp.Description? = null,
        val kind: Kind = Kind.UPNP,
        val port: Int = 0,
        val model: String = desc?.model.orEmpty(),
        val id: String = "",
    ) {
        val key get() = if (kind == Kind.GOOGLE_CAST) "cast:" + id.ifEmpty { host.hostAddress } else desc?.udn?.ifEmpty { location } ?: location
        /** "hotspot · 192.168.43.17" */
        val where get() = (Lan.networks().firstOrNull { it.contains(host) }?.kind ?: "network") + " · " + host.hostAddress
        /** "Chromecast" for a Cast device, the model otherwise. */
        val note get() = if (kind == Kind.GOOGLE_CAST) listOf("Google Cast", model).filter { it.isNotBlank() }.distinct().joinToString(" ") else model
    }

    private lateinit var appContext: Context
    /** Commands to the device, one at a time, in order. */
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
    /** The device's playback, as the deck shows it. */
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _volume = MutableStateFlow(-1)
    /** The device's volume 0..100, or -1 when it has none (or hasn't said). */
    val volume: StateFlow<Int> = _volume.asStateFlow()

    val active get() = _target.value != null

    /** The device stopped at the end of a song with nothing queued after it. Called on the main thread. */
    var onEnded: () -> Unit = {}
    /** The device moved on to the song queued with [setNext] by itself. Called on the main thread. */
    var onAdvanced: () -> Unit = {}

    @Volatile private var currentUri: String? = null
    @Volatile private var nextUri: String? = null
    @Volatile private var wasPlaying = false
    /** Until when a STOPPED is the device still settling after a command, not the end of a song. */
    @Volatile private var quietUntil = 0L
    @Volatile private var polledPos = 0L
    @Volatile private var polledAt = 0L
    private var pollJob: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Google Cast: the session, and what its queue holds.
    @Volatile private var session: CastSession? = null
    @Volatile private var castItemId = -1
    @Volatile private var castContent: String? = null
    private val castItems = ConcurrentHashMap<Int, String>()

    /** A song has been handed to the device (so Play can just resume it). */
    val loaded get() = currentUri != null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---- finding devices ----

    /**
     * Looks for renderers and Cast devices on every network the phone is on, the hotspot
     * included. [manual] is an address typed in ("192.168.43.17", "192.168.43.17:49152" or a UPnP
     * description's URL) for a device that doesn't answer a search sent everywhere.
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
                val casts = async { runCatching { Mdns.search(timeoutMs = 3500, extra = extra) }.getOrDefault(emptyList()) }
                urls += Lan.search(timeoutMs = 3500, extra = extra)
                val renderers = urls.mapNotNull { runCatching { describe(it) }.getOrNull() }
                val found = casts.await().map { Device(it.name, "cast://${it.address.hostAddress}:${it.port}", it.address, kind = Kind.GOOGLE_CAST, port = it.port, model = it.model, id = it.id) }.toMutableList()
                // A Cast device typed in that didn't answer mDNS: ask it directly.
                extra.filter { a -> found.none { it.host == a } && renderers.none { it.host == a } }.forEach { a -> probeCast(a)?.let { found += it } }
                _devices.value = (renderers + found + listOfNotNull(_target.value)).distinctBy { it.key }.sortedBy { it.name.lowercase() }
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

    /** A Cast device at [a]: its port 8009 answers, and its setup page (if it has one) names it. */
    private fun probeCast(a: Inet4Address): Device? {
        val open = runCatching {
            Socket().use { s ->
                Lan.localFor(a)?.let { s.bind(InetSocketAddress(it, 0)) }
                s.connect(InetSocketAddress(a, 8009), 2000)
            }
        }.isSuccess
        if (!open) return null
        val info = runCatching {
            val r = Lan.http(URL("http://${a.hostAddress}:8008/setup/eureka_info?params=name,device_info"), timeoutMs = 2500)
            if (r.code == 200) JSONObject(String(r.body, Charsets.UTF_8)) else null
        }.getOrNull()
        val name = info?.optString("name")?.takeIf { it.isNotBlank() } ?: "Cast device ${a.hostAddress}"
        val model = info?.optJSONObject("device_info")?.optString("model_name").orEmpty()
        return Device(name, "cast://${a.hostAddress}:8009", a, kind = Kind.GOOGLE_CAST, port = 8009, model = model, id = a.hostAddress)
    }

    // ---- connecting ----

    fun connect(d: Device) {
        _target.value = d
        currentUri = null
        nextUri = null
        wasPlaying = false
        _state.value = EngineState(isBuffering = d.kind == Kind.GOOGLE_CAST)
        MediaServer.start(appContext)
        holdAwake(true)
        if (d.kind == Kind.GOOGLE_CAST) {
            io.launch { openSession(d) }
        } else {
            io.launch { runCatching { _volume.value = getVolume(d) } }
        }
        pollJob?.cancel()
        pollJob = io.launch { pollLoop() }
    }

    /** Stops the device and lets it go. */
    fun disconnect() {
        val d = _target.value ?: return
        pollJob?.cancel()
        _target.value = null
        currentUri = null
        nextUri = null
        _state.value = EngineState()
        _volume.value = -1
        holdAwake(false)
        val s = session
        session = null
        io.launch {
            if (d.kind == Kind.GOOGLE_CAST) {
                s?.onClosed = {}
                s?.onStatus = {}
                runCatching { s?.stopApp() }
                s?.close()
            } else {
                runCatching { avt(d, "Stop") }
            }
        }
    }

    private fun openSession(d: Device) {
        session?.close()
        castItemId = -1
        castContent = null
        castItems.clear()
        val s = CastSession(d.host, d.port.takeIf { it > 0 } ?: 8009)
        s.onStatus = { if (session === s) onCastStatus(d, it) }
        s.onReceiver = { st ->
            st.optJSONObject("volume")?.let { v -> if (v.has("level")) _volume.value = (v.optDouble("level") * 100).roundToInt().coerceIn(0, 100) }
        }
        s.onClosed = { why ->
            if (session === s) {
                currentUri = null
                _state.update { it.copy(isPlaying = false, isBuffering = false, error = "${d.name}: $why") }
            }
        }
        try {
            s.open()
            session = s
            _state.update { it.copy(isBuffering = false) }
        } catch (t: Throwable) {
            s.close()
            _state.value = EngineState(error = "Can't reach ${d.name} (${t.message ?: t.javaClass.simpleName})")
        }
    }

    /** The Cast session, opened again if it went (the device restarted, another phone took it). */
    private fun castSession(d: Device): CastSession {
        session?.takeIf { !it.closed }?.let { return it }
        openSession(d)
        return session?.takeIf { !it.closed } ?: throw java.io.IOException(_state.value.error ?: "Not connected")
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
     * Hands [track] to the device and plays it from [startMs] (or leaves it paused there), with
     * [next] queued after it for gapless.
     */
    fun load(track: Track, startMs: Long, paused: Boolean, next: Track?) {
        val d = _target.value ?: return
        quietUntil = System.currentTimeMillis() + 8000
        wasPlaying = false
        _state.value = EngineState(isBuffering = true, positionMs = startMs, durationMs = _state.value.durationMs.takeIf { currentUri != null } ?: 0)
        io.launch {
            try {
                if (d.kind == Kind.GOOGLE_CAST) loadCast(d, track, startMs, paused, next) else loadUpnp(d, track, startMs, paused, next)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { it.copy(isBuffering = false, isPlaying = false, error = "${d.name}: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    private fun loadUpnp(d: Device, track: Track, startMs: Long, paused: Boolean, next: Track?) {
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
    }

    private fun loadCast(d: Device, track: Track, startMs: Long, paused: Boolean, next: Track?) {
        val s = castSession(d)
        val (uri, media) = castMedia(d, track)
        currentUri = uri
        nextUri = null
        castItems.clear()
        castContent = null
        // The song before reports itself interrupted on its way out: that's not a move on.
        castItemId = -1
        s.load(media, startMs / 1000.0, autoplay = !paused)
        // The queue's ids come with the first status for the new song; QUEUE_INSERT needs them.
        val until = System.currentTimeMillis() + 10_000
        while (castContent != uri && System.currentTimeMillis() < until && !s.closed && _state.value.error == null) Thread.sleep(100)
        polledPos = startMs
        polledAt = System.currentTimeMillis()
        quietUntil = System.currentTimeMillis() + 4000
        if (castContent == uri) queueNext(d, next)
    }

    /** Queues [track] on the device to follow the current song (or clears it with null). */
    fun setNext(track: Track?) {
        val d = _target.value ?: return
        if (currentUri == null) return
        io.launch { runCatching { queueNext(d, track) } }
    }

    private fun queueNext(d: Device, track: Track?) {
        if (d.kind == Kind.GOOGLE_CAST) return queueNextCast(d, track)
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

    private fun queueNextCast(d: Device, track: Track?) {
        val s = session?.takeIf { !it.closed } ?: return
        val wanted = track?.let { runCatching { castMedia(d, it) }.getOrNull() }
        if (wanted != null && wanted.first == nextUri) return
        // Whatever is queued after the current song isn't next any more.
        val stale = castItems.filterKeys { it > castItemId }.keys.toList()
        if (stale.isNotEmpty()) runCatching { s.queueRemove(stale) }
        stale.forEach { castItems.remove(it) }
        nextUri = null
        // A song the device can't play isn't queued: the device stops at the end of this one,
        // and the phone then tells the deck to go on (and says why it can't play that one).
        if (wanted != null) nextUri = runCatching { s.queueNext(wanted.second); wanted.first }.getOrNull()
    }

    fun resume() = command { d ->
        if (d.kind == Kind.GOOGLE_CAST) castSession(d).play() else avt(d, "Play", "Speed" to "1")
        _state.update { it.copy(isPlaying = true) }
        polledAt = System.currentTimeMillis()
    }

    fun pause() = command { d ->
        refresh()
        if (d.kind == Kind.GOOGLE_CAST) castSession(d).pause() else avt(d, "Pause")
        polledPos = _state.value.positionMs
        _state.update { it.copy(isPlaying = false) }
    }

    fun seek(ms: Long) {
        _state.update { it.copy(positionMs = ms) }
        polledPos = ms
        polledAt = System.currentTimeMillis()
        quietUntil = System.currentTimeMillis() + 4000
        command { d -> if (d.kind == Kind.GOOGLE_CAST) castSession(d).seek(ms / 1000.0) else avt(d, "Seek", "Unit" to "REL_TIME", "Target" to Upnp.formatTime(ms)) }
    }

    fun stopRemote() = command { d ->
        quietUntil = System.currentTimeMillis() + 4000
        if (d.kind == Kind.GOOGLE_CAST) session?.takeIf { !it.closed }?.stop() else avt(d, "Stop")
        currentUri = null
        nextUri = null
        _state.update { it.copy(isPlaying = false) }
    }

    fun setVolume(v: Int) {
        val c = v.coerceIn(0, 100)
        _volume.value = c
        command { d -> if (d.kind == Kind.GOOGLE_CAST) castSession(d).setVolume(c / 100.0) else rc(d, "SetVolume", "Channel" to "Master", "DesiredVolume" to c.toString()) }
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

    // ---- following the device ----

    private suspend fun pollLoop() {
        var fails = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val d = _target.value ?: return
            try {
                if (d.kind == Kind.GOOGLE_CAST) {
                    // The device tells us what changes by itself; this is just a check-in.
                    session?.takeIf { !it.closed && currentUri != null }?.getStatus()
                } else {
                    poll(d)
                }
                fails = 0
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (++fails >= 4) _state.update { it.copy(isPlaying = false, error = "Can't reach ${d.name} (${t.message ?: "no answer"})") }
            }
            delay(if (d.kind == Kind.GOOGLE_CAST) 3000 else 1000)
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

    /** A MEDIA_STATUS from the Cast device, on the session's reading thread. */
    private fun onCastStatus(d: Device, s: JSONObject) {
        if (_target.value?.key != d.key) return
        s.optJSONArray("items")?.let { items ->
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val c = it.optJSONObject("media")?.optString("contentId")
                if (it.has("itemId") && !c.isNullOrEmpty()) castItems[it.optInt("itemId")] = c
            }
        }
        val itemId = s.optInt("currentItemId", castItemId)
        val content = s.optJSONObject("media")?.optString("contentId")?.takeIf { it.isNotEmpty() } ?: castItems[itemId]
        if (content != null && itemId >= 0) castItems[itemId] = content
        val player = s.optString("playerState")
        val now = System.currentTimeMillis()
        if (itemId != castItemId && content != null) {
            val before = castItemId
            castItemId = itemId
            castContent = content
            if (before >= 0 && content != currentUri) {
                if (content == nextUri) {
                    // Moved on by itself to the song queued next: gapless, as it should.
                    currentUri = content
                    nextUri = null
                    bg.launch(Dispatchers.Main) { onAdvanced() }
                } else if (currentUri != null) {
                    // Moved on to a song that was queued before the queue changed: go on to the
                    // deck's next song instead.
                    currentUri = null
                    bg.launch(Dispatchers.Main) { onEnded() }
                    return
                }
            }
        }
        if (player == "IDLE") {
            val reason = s.optString("idleReason")
            when {
                reason == "ERROR" -> {
                    currentUri = null
                    val detail = s.optString("detail").ifBlank { "it couldn't play this song" }
                    _state.update { it.copy(isPlaying = false, isBuffering = false, error = "${d.name}: $detail") }
                    return
                }
                reason == "FINISHED" && currentUri != null && castContent == currentUri -> {
                    // The song ran out with nothing queued after it. (Said once, unlike a UPnP
                    // renderer's STOPPED, so no settling time applies.)
                    wasPlaying = false
                    currentUri = null
                    bg.launch(Dispatchers.Main) { onEnded() }
                    return
                }
            }
        }
        val playing = player == "PLAYING"
        if (s.has("currentTime")) {
            polledPos = (s.optDouble("currentTime") * 1000).toLong()
            polledAt = now
        }
        val dur = s.optJSONObject("media")?.optDouble("duration")?.takeIf { !it.isNaN() && it > 0 }?.let { (it * 1000).toLong() }
        if (playing) wasPlaying = true else if (player == "PAUSED") wasPlaying = false
        _state.value = EngineState(
            isPlaying = playing,
            isBuffering = player == "BUFFERING" || player == "LOADING",
            positionMs = polledPos,
            durationMs = dur ?: _state.value.durationMs,
        )
    }

    private fun waitForPlaying(d: Device) {
        val until = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < until) {
            if (runCatching { avt(d, "GetTransportInfo")["CurrentTransportState"] }.getOrNull() == "PLAYING") return
            Thread.sleep(250)
        }
    }

    // ---- the song as the device gets it ----

    private fun localFor(d: Device) = Lan.localFor(d.host) ?: Lan.networks().firstOrNull()?.address ?: throw java.io.IOException("The phone has no network the device is on")

    private data class Info(val token: String, val url: String, val mime: String, val title: String, val artist: String, val album: String, val art: String?)

    private fun info(d: Device, track: Track): Info {
        val token = MediaServer.register(track)
        val local = localFor(d)
        val mime = MediaServer.mimeFor(token)
        val tags = TagStore.tags.value[track.id]
        return Info(
            token = token,
            url = MediaServer.songUrl(token, local) + "." + extensionFor(mime),
            mime = mime,
            title = tags?.title?.takeIf { it.isNotBlank() } ?: track.title,
            artist = tags?.artist?.takeIf { it.isNotBlank() } ?: track.artist,
            album = tags?.album?.takeIf { it.isNotBlank() } ?: track.album,
            art = if (ArtworkStore.cached(track) != null) MediaServer.artUrl(token, local) else null,
        )
    }

    private fun item(d: Device, track: Track): Pair<String, String> {
        val i = info(d, track)
        val meta = Upnp.didl(
            title = i.title, artist = i.artist, album = i.album,
            url = i.url, mime = i.mime, durationMs = 0, sizeBytes = track.sizeBytes, artUrl = i.art,
        )
        return i.url to meta
    }

    /** The song as a Cast MediaInformation; throws for a format Cast devices can't play. */
    private fun castMedia(d: Device, track: Track): Pair<String, JSONObject> {
        val i = info(d, track)
        val codec = when (i.mime) {
            "audio/dsf", "audio/dff" -> "DSD"
            "audio/aiff" -> "AIFF"
            "audio/mp4" -> MediaServer.codecFor(i.token)
            else -> ""
        }
        castUnsupported(codec)?.let { throw java.io.IOException(it) }
        val meta = JSONObject()
            .put("metadataType", 3) // MusicTrackMediaMetadata
            .put("title", i.title)
            .put("artist", i.artist)
            .put("albumName", i.album)
        i.art?.let { meta.put("images", JSONArray().put(JSONObject().put("url", it))) }
        val media = JSONObject()
            .put("contentId", i.url)
            .put("contentUrl", i.url)
            .put("streamType", "BUFFERED")
            .put("contentType", i.mime)
            .put("metadata", meta)
        return i.url to media
    }

    /** Why a Cast device can't play [codec], or null when it can. */
    internal fun castUnsupported(codec: String) = when (codec.uppercase()) {
        "ALAC" -> "Cast devices can't play Apple Lossless (ALAC)"
        "DSD" -> "Cast devices can't play DSD"
        "AIFF" -> "Cast devices can't play AIFF"
        else -> null
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

    private fun upnp(d: Device) = d.desc ?: throw java.io.IOException("${d.name} isn't a UPnP renderer")

    private fun avt(d: Device, action: String, vararg args: Pair<String, String>) =
        soap(upnp(d).avTransport, action, listOf("InstanceID" to "0") + args)

    private fun rc(d: Device, action: String, vararg args: Pair<String, String>): Map<String, String> {
        val s = upnp(d).renderingControl ?: throw java.io.IOException("No volume control")
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
