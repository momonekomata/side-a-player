package io.github.momonekomata.sidea.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import io.github.momonekomata.sidea.audio.EngineState
import io.github.momonekomata.sidea.sources.ArtworkStore
import io.github.momonekomata.sidea.sources.MissingFiles
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
    /**
     * The device no longer has the current song (it dropped it, or came back after the
     * connection went without it): hand it over again from [positionMs], playing if [play].
     * Called on the main thread.
     */
    var onReload: (positionMs: Long, play: Boolean) -> Unit = { _, _ -> }

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
    /** The device's last playerState (PLAYING, PAUSED, BUFFERING, IDLE). */
    @Volatile private var castPlayer = ""
    /** Counts MEDIA_STATUS messages, so a rejoin can wait for a fresh one. */
    @Volatile private var castStatusCount = 0
    private val castItems = ConcurrentHashMap<Int, String>()
    /** The song last queued after the current one, to queue it again after a reconnect. */
    @Volatile private var nextTrack: Track? = null
    private var reconnectJob: Job? = null

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
        reconnectJob?.cancel()
        if (d.kind == Kind.GOOGLE_CAST) {
            io.launch {
                try {
                    openSession(d)
                    _state.update { it.copy(isBuffering = false) }
                } catch (t: Throwable) {
                    _state.value = EngineState(error = "Can't reach ${d.name} (${t.message ?: t.javaClass.simpleName})")
                }
            }
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
        reconnectJob?.cancel()
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
                s?.onClosed = { _, _ -> }
                s?.onStatus = {}
                runCatching { s?.stopApp() }
                s?.close()
            } else {
                runCatching { avt(d, "Stop") }
            }
        }
    }

    /**
     * Connects to [d] and starts (or joins) its media player; throws if it can't. [fresh] forgets
     * what the device's queue held; a rejoin after a dropped connection keeps it, as the device
     * kept playing it.
     */
    private fun openSession(d: Device, fresh: Boolean = true): CastSession {
        session?.close()
        session = null
        if (fresh) {
            castItemId = -1
            castContent = null
            castItems.clear()
        }
        castPlayer = ""
        val s = CastSession(d.host, d.port.takeIf { it > 0 } ?: 8009)
        s.onStatus = { if (session === s) onCastStatus(d, it) }
        s.onReceiver = { st ->
            st.optJSONObject("volume")?.let { v -> if (v.has("level")) _volume.value = (v.optDouble("level") * 100).roundToInt().coerceIn(0, 100) }
        }
        s.onClosed = { why, lost -> if (session === s) onSessionClosed(d, why, lost) }
        try {
            s.open()
        } catch (t: Throwable) {
            s.close()
            throw t
        }
        session = s
        return s
    }

    /** The Cast session, opened again if it went (the device restarted, another phone took it). */
    private fun castSession(d: Device): CastSession {
        session?.takeIf { !it.closed }?.let { return it }
        return try {
            openSession(d)
        } catch (t: Throwable) {
            throw java.io.IOException("can't reach it (${t.message ?: t.javaClass.simpleName})")
        }
    }

    /**
     * The connection to [d] went. When it dropped by itself (the phone's Wi-Fi blinked, the device
     * stopped answering for a moment) with a song on, connect again and carry on: the device
     * usually played on meanwhile, and otherwise gets the song again from where it was. When the
     * device ended it on purpose, stop and say why; Play starts it again.
     */
    private fun onSessionClosed(d: Device, why: String, lost: Boolean) {
        if (_target.value?.key != d.key) return
        refresh()
        val st = _state.value
        val uri = currentUri
        val playing = st.isPlaying || (st.isBuffering && !paused)
        val pos = st.positionMs
        currentUri = null
        if (!lost || uri == null) {
            nextUri = null
            _state.update { it.copy(isPlaying = false, isBuffering = false, bufferNote = null, error = "${d.name}: $why") }
            return
        }
        _state.update { it.copy(isPlaying = false, isBuffering = true, bufferNote = "Reconnecting to ${d.name}…", error = null) }
        reconnectJob?.cancel()
        reconnectJob = io.launch { reconnect(d, uri, pos, playing, why) }
    }

    private suspend fun reconnect(d: Device, uri: String, pos: Long, playing: Boolean, why: String) {
        for (wait in listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)) {
            delay(wait)
            if (_target.value?.key != d.key) return
            val s = runCatching { openSession(d, fresh = false) }.getOrNull() ?: continue
            // What the device is doing now: a fresh status for the media.
            val seen = castStatusCount
            runCatching { s.getStatus() }
            val until = System.currentTimeMillis() + 4_000
            while (castStatusCount == seen && System.currentTimeMillis() < until && !s.closed) delay(100)
            if (s.closed) continue
            val live = castPlayer.isNotEmpty() && castPlayer != "IDLE"
            // Moved on meanwhile to the song queued next: the status already followed it.
            val advanced = currentUri != null && castContent == currentUri
            if (live && (castContent == uri || advanced)) {
                // It played on: follow it again.
                if (!advanced) currentUri = uri
                if (playing && castPlayer == "PAUSED") runCatching { s.play() }
                if (nextUri == null && !advanced) runCatching { queueNextCast(d, nextTrack) }
                _state.update { it.copy(bufferNote = null, error = null) }
            } else {
                // It let the song go: hand it over again, from where it was.
                nextUri = null
                _state.update { it.copy(bufferNote = null) }
                bg.launch(Dispatchers.Main) { onReload(pos, playing) }
            }
            return
        }
        nextUri = null
        _state.update { it.copy(isPlaying = false, isBuffering = false, bufferNote = null, positionMs = pos, error = "Lost ${d.name} ($why). Press Play to try again.") }
    }

    /** The last song handed over was to stay paused. */
    @Volatile private var paused = false

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
        io.launch {
            // A song the device can't play (ALAC on a Chromecast) is turned down before anything
            // changes: the song it plays goes on, and the deck keeps showing that one.
            val media = if (d.kind == Kind.GOOGLE_CAST) {
                try {
                    castMedia(d, track)
                } catch (e: CastRefused) {
                    val keep = playingTrack
                    withContext(Dispatchers.Main) { onRefused(track, e.message.orEmpty(), keep) }
                    return@launch
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    _state.update { it.copy(isBuffering = false, isPlaying = false, error = "${d.name}: ${t.message ?: t.javaClass.simpleName}") }
                    return@launch
                }
            } else null
            // A song chosen while reconnecting: that's what to play now.
            reconnectJob?.cancel()
            this@Cast.paused = paused
            nextTrack = next
            quietUntil = System.currentTimeMillis() + 8000
            wasPlaying = false
            _state.value = EngineState(isBuffering = true, positionMs = startMs, durationMs = _state.value.durationMs.takeIf { currentUri != null } ?: 0)
            try {
                if (media != null) loadCast(d, track, media, startMs, paused, next) else loadUpnp(d, track, startMs, paused, next)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { it.copy(isBuffering = false, isPlaying = false, error = "${d.name}: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    /** Shows [why] the current song isn't playing on the device. */
    fun fail(why: String) {
        val d = _target.value ?: return
        _state.update { it.copy(isBuffering = false, isPlaying = false, error = "${d.name}: $why") }
    }

    /** A song this kind of device can't play; the message says why. */
    class CastRefused(why: String) : java.io.IOException(why)

    /**
     * A song the device can't play was chosen ([why] says so). [keep] is the song the device
     * still plays (it goes on), or null when it plays nothing. Called on the main thread.
     */
    var onRefused: (track: Track, why: String, keep: Track?) -> Unit = { _, _, _ -> }

    /** The song the device has (playing or paused), as handed to it. */
    @Volatile private var loadedTrack: Track? = null
    val playingTrack: Track? get() = loadedTrack.takeIf { currentUri != null }

    private fun loadUpnp(d: Device, track: Track, startMs: Long, paused: Boolean, next: Track?) {
        val (uri, meta) = item(d, track)
        avt(d, "SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to meta)
        currentUri = uri
        loadedTrack = track
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

    private fun loadCast(d: Device, track: Track, prepared: Pair<String, JSONObject>, startMs: Long, paused: Boolean, next: Track?) {
        val s = castSession(d)
        val (uri, media) = prepared
        currentUri = uri
        loadedTrack = track
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
        if (castContent == uri) {
            queueNext(d, next)
        } else if (!s.closed && _state.value.error == null) {
            // No word from the device about the song: say so, rather than buffer for ever (Play
            // then tries again).
            currentUri = null
            throw java.io.IOException("it didn't start the song")
        }
    }

    /** Queues [track] on the device to follow the current song (or clears it with null). */
    fun setNext(track: Track?) {
        val d = _target.value ?: return
        nextTrack = track
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
        if (d.kind == Kind.GOOGLE_CAST) {
            val s = castSession(d)
            // The device let the song go (it stopped it, or another app cut in): there's nothing
            // to carry on, so hand it over again from where it was.
            if (castPlayer == "IDLE" || castPlayer == "" || s.mediaSessionId == null) {
                val pos = _state.value.positionMs
                currentUri = null
                bg.launch(Dispatchers.Main) { onReload(pos, true) }
                return@command
            }
            s.play()
        } else {
            avt(d, "Play", "Speed" to "1")
        }
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
            loadedTrack = nextTrack
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
        if (player.isNotEmpty()) castPlayer = player
        castStatusCount++
        val now = System.currentTimeMillis()
        if (itemId != castItemId && content != null) {
            val before = castItemId
            castItemId = itemId
            castContent = content
            if (before >= 0 && content != currentUri) {
                if (content == nextUri) {
                    // Moved on by itself to the song queued next: gapless, as it should.
                    currentUri = content
                    loadedTrack = nextTrack
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
                (reason == "INTERRUPTED" || reason == "CANCELLED") && currentUri != null && castContent == currentUri && now > quietUntil -> {
                    // The device let our song go by itself (stopped from its own controls or
                    // a voice command, or cut off by something else), not for a song we sent.
                    // It's paused where it was: Play hands it over again from there.
                    currentUri = null
                    wasPlaying = false
                    if (s.has("currentTime")) polledPos = (s.optDouble("currentTime") * 1000).toLong()
                    _state.update { it.copy(isPlaying = false, isBuffering = false, positionMs = polledPos) }
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

    private fun localFor(d: Device): java.net.InetAddress = servedAs() ?: Lan.localFor(d.host) ?: Lan.networks().firstOrNull()?.address ?: throw java.io.IOException("The phone has no network the device is on")

    /**
     * For testing against a real device from the emulator, whose own address the device can't
     * reach: `adb shell setprop debug.walkdeck.serve <the PC's address>` makes the device fetch
     * songs from the PC, where a relay passes them on to the emulator (scripts/relay.py). Only
     * adb can set a debug.* property, so on a phone this is never set.
     */
    private fun servedAs(): java.net.InetAddress? = runCatching {
        val v = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.walkdeck.serve") as String
        v.takeIf { it.isNotBlank() }?.let { java.net.InetAddress.getByName(it) }
    }.getOrNull()

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
        castUnsupported(codec)?.let { throw CastRefused(it) }
        // Its file isn't there any more (moved or renamed on the NAS): turned down like a format
        // the device can't play, so whatever plays goes on.
        if (track.id in MissingFiles.ids.value) throw CastRefused("Its file has moved or been renamed since the last scan; rescan to find it")
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
        "ALAC" -> "Cast devices can't play ALAC (Apple Lossless)"
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
