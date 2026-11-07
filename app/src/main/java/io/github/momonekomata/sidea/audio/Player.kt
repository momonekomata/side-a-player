package io.github.momonekomata.sidea.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.github.momonekomata.sidea.Settings
import io.github.momonekomata.sidea.audio.usb.UsbExclusive
import io.github.momonekomata.sidea.cast.Cast
import kotlinx.coroutines.flow.combine
import io.github.momonekomata.sidea.sources.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

enum class RepeatMode { OFF, ALL, ONE }

/**
 * [tracks] is the play order (shuffled when [shuffle] is on); [original] keeps the folder's
 * order so shuffle can be undone without losing the place.
 */
data class QueueState(
    val tracks: List<Track> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val original: List<Track> = tracks,
) {
    val current: Track? get() = tracks.getOrNull(index)

    /** The index after this one, following repeat; null at the end. */
    fun nextIndex(auto: Boolean): Int? = when {
        auto && repeat == RepeatMode.ONE -> index
        index + 1 < tracks.size -> index + 1
        repeat != RepeatMode.OFF && tracks.isNotEmpty() -> 0
        else -> null
    }

    /** [t] straight after the current song, in play order and in the folder order shuffle returns to. */
    fun withNext(t: Track): QueueState {
        val cur = current ?: return QueueState(listOf(t), 0, shuffle, repeat)
        val at = original.indexOfFirst { it === cur }
        val orig = if (at < 0) original + t else original.toMutableList().apply { add(at + 1, t) }
        return copy(tracks = tracks.toMutableList().apply { add(index + 1, t) }, original = orig)
    }

    /** Without the song at [i] (not the current one), in both orders. */
    fun without(i: Int): QueueState {
        val gone = tracks[i]
        return copy(
            tracks = tracks.filterIndexed { k, _ -> k != i },
            index = if (i < index) index - 1 else index,
            original = original.toMutableList().apply { indexOfFirst { it === gone }.takeIf { it >= 0 }?.let { removeAt(it) } },
        )
    }

    /** The same song playing, with [upcoming] after it (reordered or trimmed by the user). */
    fun withUpcoming(upcoming: List<Track>): QueueState {
        val tracks = this.tracks.take(index + 1) + upcoming
        // Unshuffled, the play order is the folder order; shuffled, turning shuffle off returns to the folder.
        val kept = original.filter { o -> tracks.any { it === o } }
        return copy(tracks = tracks, original = if (shuffle) kept else tracks)
    }

    /** [t] at the end of the queue. */
    fun withAdded(t: Track): QueueState =
        if (current == null) QueueState(listOf(t), 0, shuffle, repeat) else copy(tracks = tracks + t, original = original + t)
}

/** The app-wide local player: a queue in front of [AudioEngine]. Shared by the UI and the service. */
object Player {
    private lateinit var appContext: Context
    private lateinit var engine: AudioEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var openJob: Job? = null

    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue.asStateFlow()
    /** What the deck shows: the phone's own playback, or the renderer's while casting. */
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Identifies one queue + position, so a late gapless hand-over can't land on a new queue. */
    private class Slot(val tracks: List<Track>, val index: Int)

    private val callbacks = object : EngineCallbacks {
        override fun upNext(after: Any?): UpNext? {
            val q = _queue.value
            // Right after a hand-over the queue still points at the song before it: count from
            // the song being decoded, or a short song would be handed over to itself again.
            val from = (after as? Slot)?.takeIf { it.tracks === q.tracks }?.let { q.copy(index = it.index) } ?: q
            val i = from.nextIndex(auto = true) ?: return null
            val t = q.tracks.getOrNull(i) ?: return null
            return UpNext(Slot(q.tracks, i)) { runBlocking(Dispatchers.IO) { t.opener(appContext) } }
        }

        override fun onAdvanced(tag: Any) {
            val slot = tag as Slot
            val q = _queue.value
            if (q.tracks === slot.tracks) {
                _queue.value = q.copy(index = slot.index)
            } else {
                // The queue changed after the next song was opened: follow the song that plays.
                val t = slot.tracks.getOrNull(slot.index) ?: return
                val i = q.tracks.indices.firstOrNull { it > q.index && q.tracks[it] === t } ?: q.tracks.indexOfFirst { it === t }
                if (i >= 0) _queue.value = q.copy(index = i)
            }
        }

        override fun onCompleted() = next(auto = true)

        override fun dopAllowed() = Settings.current.dop
    }

    // ---- Audio focus: one player at a time, like every music app ----

    private lateinit var audioManager: AudioManager
    private var focus: AudioFocusRequest? = null
    private var resumeOnGain = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // Another app started playing for good (Spotify, a video): stop competing with it.
            // Another app plays now: pause and hand the DAC back at once, so it gets sound.
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                park()
                abandonFocus()
            }
            // A notification or a navigation prompt asks to duck: carry on. Android lowers
            // its own mixer path for the moment; Side A's USB path doesn't change its samples.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
            // A call, a voice assistant: pause and pick up afterwards.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (engine.state.value.isPlaying) {
                    resumeOnGain = true
                    engine.pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) {
                resumeOnGain = false
                engine.resume()
            }
        }
    }

    /** Asks the other players to pause. False while a phone call holds the audio. */
    private fun requestFocus(): Boolean {
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .build()
            .also { focus = it }
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    // ---- DAC plugged in or out ----

    private val main = Handler(Looper.getMainLooper())
    private var devicesReady = false

    /**
     * The output is chosen when a song opens, so a DAC plugged in mid-song would otherwise stay
     * behind Android's mixer until the next one. Reopen the song where it was, on the new path.
     * The short delay lets Android finish reading the DAC's formats.
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (devicesReady && !usbDriverBusy() && added.any { it.isSink && it.isUsb() }) main.postDelayed({ reopenOnNewOutput() }, 800)
            devicesReady = true // the first call just lists what's already there
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            // Unplugging pauses (becoming-noisy); reopen paused so Play goes to the new output.
            if (!usbDriverBusy() && removed.any { it.isSink && it.isUsb() }) main.postDelayed({ reopenOnNewOutput() }, 300)
        }
    }

    private fun AudioDeviceInfo.isUsb() =
        type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun reopenOnNewOutput() {
        val s = engine.state.value
        if (Cast.active || _queue.value.current == null || s.output == null || parkedAtMs != null) return
        startCurrent(startMs = s.positionMs, paused = !s.isPlaying)
    }

    /**
     * Side A's USB driver taking the DAC from Android (or handing it back) makes the DAC
     * vanish from Android's view for a moment. That isn't an unplug.
     */
    private fun usbDriverBusy() = UsbExclusive.holding || System.currentTimeMillis() < UsbExclusive.busyUntil

    private var askedUsb: String? = null

    /** Side A let go of the DAC: Android should list it as an output again by now. */
    private fun checkHandBack() {
        if (UsbExclusive.holding || UsbExclusive.audioDevice() == null) return
        val back = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.isUsb() }
        if (!back) {
            android.widget.Toast.makeText(
                appContext,
                "Android hasn't taken the DAC back (${UsbExclusive.handBack}). Unplug and replug it for other apps.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Volume while Side A's USB driver drives the DAC; Android's volume can't reach it then. */
    fun setUsbVolume(v: Float) = Settings.update { it.copy(usbVolume = v.coerceIn(0f, 1f)) }

    /** Headphones or the DAC were unplugged: pause rather than blast the phone speaker. */
    fun pauseForNoisy() {
        if (usbDriverBusy() || Cast.active) return
        resumeOnGain = false
        if (engine.state.value.isPlaying) pauseAndPark()
    }

    fun init(context: Context) {
        if (::engine.isInitialized) return
        appContext = context.applicationContext
        saved = appContext.getSharedPreferences("queue", 0)
        audioManager = appContext.getSystemService(AudioManager::class.java)
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        UsbExclusive.init(appContext)
        UsbExclusive.onPermissionGranted = { main.post { reopenOnNewOutput() } }
        UsbExclusive.onReleased = { main.postDelayed(::checkHandBack, 4_000) }
        UsbExclusive.onDetached = {
            main.post {
                if (engine.state.value.output?.exclusive != null) {
                    resumeOnGain = false
                    engine.pause()
                    main.postDelayed({ reopenOnNewOutput() }, 300)
                }
            }
        }
        Settings.init(appContext)
        scope.launch { _queue.collect { saveQueue() } }
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (state.value.isPlaying) saveQueue()
            }
        }
        io.github.momonekomata.sidea.sources.PlayHistory.init(appContext)
        io.github.momonekomata.sidea.library.Library.init(appContext)
        // Every song that comes up, by a tap or a gapless hand-over, goes into the history.
        scope.launch {
            _queue.map { it.current }.distinctUntilChanged().collect { t ->
                if (t == null) return@collect
                // The queue coming back from last time isn't a play.
                if (restored) { restored = false; return@collect }
                io.github.momonekomata.sidea.sources.PlayHistory.record(t)
                val tags = io.github.momonekomata.sidea.sources.TagStore.tags.value
                io.github.momonekomata.sidea.library.Library.played(t, t.displayTitleFor(tags), t.displayArtistFor(tags), t.displayAlbumFor(tags))
            }
        }
        engine = AudioEngine(appContext, callbacks)
        // Asked once per DAC per run; the song plays through Android until the user says yes.
        engine.onUsbPermissionNeeded = { d ->
            main.post {
                if (askedUsb != d.deviceName) {
                    askedUsb = d.deviceName
                    UsbExclusive.requestPermission(d)
                }
            }
        }
        scope.launch {
            Settings.prefs.collect {
                engine.gapless = it.gapless
                engine.eq = it.eq
                engine.usbVolume = it.usbVolume
                if (engine.exclusiveUsb != it.exclusiveUsb) {
                    engine.exclusiveUsb = it.exclusiveUsb
                    reopenOnNewOutput()
                }
            }
        }
        // ---- casting ----
        Cast.init(appContext)
        Cast.onEnded = { next(auto = true) }
        Cast.onAdvanced = {
            val q = _queue.value
            q.nextIndex(auto = true)?.let { _queue.value = q.copy(index = it) }
        }
        scope.launch {
            combine(engine.state, Cast.state, Cast.target) { e, c, t -> if (t != null) c else e }.collect { _state.value = it }
        }
        // Whatever changes the queue (a tap, Play next, shuffle, the renderer moving on), the
        // renderer gets the right song queued after the one it plays.
        scope.launch { _queue.collect { if (Cast.active && Cast.loaded) Cast.setNext(upcoming()) } }
    }

    /** The song that follows the current one on its own, when gapless is on. */
    private fun upcoming(): Track? {
        if (!Settings.current.gapless) return null
        val q = _queue.value
        return q.nextIndex(auto = true)?.let { q.tracks.getOrNull(it) }
    }

    /**
     * Plays on [device] from here on (null: back on the phone). The song carries on where it was,
     * playing or paused as it was.
     */
    fun castTo(device: Cast.Device?) {
        val s = state.value
        val pos = parkedAtMs ?: s.positionMs
        val playing = s.isPlaying
        val hasSong = _queue.value.current != null
        if (device == null) {
            if (!Cast.active) return
            Cast.disconnect()
            // Back on the phone, parked where the renderer was: Play carries on here.
            if (hasSong) _parked.value = Resume(pos, s.durationMs)
            return
        }
        if (Cast.target.value?.key == device.key) return
        openJob?.cancel()
        main.removeCallbacks(parkLater)
        if (Cast.active) Cast.disconnect()
        engine.stop(releaseOutput = true)
        abandonFocus()
        Cast.connect(device)
        if (hasSong) {
            _parked.value = null
            startCurrent(startMs = pos, paused = !playing)
        }
    }

    fun playQueue(tracks: List<Track>, index: Int) {
        val q = _queue.value
        var next = QueueState(tracks, index, shuffle = false, repeat = q.repeat, original = tracks)
        if (q.shuffle) next = shuffled(next)
        _queue.value = next
        startCurrent()
    }

    // ---- editing the queue ----

    /** Plays [t] after the current song; plays it now if nothing is queued. */
    fun playNext(t: Track) = edit { it.withNext(t) }

    /** Jumps to the song at [index] in the queue. */
    fun jumpTo(index: Int) {
        val q = _queue.value
        if (index !in q.tracks.indices) return
        _queue.value = q.copy(index = index)
        startCurrent()
    }

    /** Takes the song at [index] out of the queue; the one playing stays. */
    fun removeAt(index: Int) {
        val q = _queue.value
        if (index == q.index || index !in q.tracks.indices) return
        _queue.value = q.without(index)
        engine.upNextChanged()
    }

    /** What plays after the current song, as the user reordered it or swiped songs out. */
    fun setUpcoming(upcoming: List<Track>) {
        if (_queue.value.current == null) return
        _queue.value = _queue.value.withUpcoming(upcoming)
        engine.upNextChanged()
    }

    /** Clears everything after the song playing. */
    fun clearUpcoming() {
        val q = _queue.value
        val cur = q.current ?: return
        _queue.value = q.copy(tracks = q.tracks.take(q.index + 1), original = q.original.filter { o -> q.tracks.take(q.index + 1).any { it === o } }.ifEmpty { listOf(cur) })
        engine.upNextChanged()
    }

    /** Adds [t] to the end of the queue; plays it now if nothing is queued. */
    fun addToQueue(t: Track) = edit { it.withAdded(t) }

    private fun edit(change: (QueueState) -> QueueState) {
        val q = _queue.value
        _queue.value = change(q)
        if (q.current == null) startCurrent() else engine.upNextChanged()
    }

    // ---- shuffle, repeat ----

    private fun shuffled(q: QueueState): QueueState {
        val cur = q.current ?: return q.copy(shuffle = true)
        val rest = q.original.filter { it !== cur }.shuffled()
        return q.copy(tracks = listOf(cur) + rest, index = 0, shuffle = true)
    }

    fun toggleShuffle() {
        val q = _queue.value
        _queue.value = if (q.shuffle) {
            val cur = q.current
            q.copy(tracks = q.original, index = q.original.indexOfFirst { it === cur }.coerceAtLeast(0), shuffle = false)
        } else {
            shuffled(q)
        }
        engine.upNextChanged()
    }

    fun cycleRepeat() {
        val q = _queue.value
        _queue.value = q.copy(repeat = RepeatMode.entries[(q.repeat.ordinal + 1) % RepeatMode.entries.size])
        engine.upNextChanged()
    }

    // ---- skipping ----

    /** Jumps by [deltaMs] within the song (±5 s, ±10 s, or held fast-forward/rewind). */
    fun skipBy(deltaMs: Long) {
        val s = state.value
        if (_queue.value.current == null) return
        // Parked (paused with the DAC handed back, or brought back from last time), the engine
        // knows nothing: the spot and length are the parked ones.
        val parked = _parked.value
        val at = parked?.positionMs ?: s.positionMs
        val length = parked?.durationMs?.takeIf { it > 0 } ?: s.durationMs
        val to = (at + deltaMs).coerceIn(0, if (length > 0) (length - 500).coerceAtLeast(0) else Long.MAX_VALUE)
        if (parked != null) parkedAtMs = to else seekTo(to)
    }

    // ---- remembering the queue between runs ----

    private lateinit var saved: android.content.SharedPreferences
    @Volatile private var restored = false
    private var saveJob: Job? = null

    /** The queue, the song's place in it, where it had got to and its length, kept on the phone. */
    private fun saveQueue() {
        if (!::saved.isInitialized || !::engine.isInitialized) return
        val q = _queue.value
        // Nothing queued is the state at start-up, before [restore]; it mustn't wipe what's kept.
        if (q.current == null) return
        val tags = io.github.momonekomata.sidea.sources.TagStore.tags.value
        // At most 500 songs around the current one; a whole library queued would be a lot to keep.
        val from = (q.index - 100).coerceAtLeast(0)
        val kept = q.tracks.drop(from).take(500)
        val songs = kept.map { t ->
            io.github.momonekomata.sidea.sources.Favorite(t.id, t.displayTitleFor(tags), t.displayArtistFor(tags), t.displayAlbumFor(tags), t.sizeBytes)
        }
        val s = state.value
        val pos = parkedAtMs ?: s.positionMs
        val dur = s.durationMs.takeIf { it > 0 } ?: _parked.value?.durationMs ?: 0
        val json = org.json.JSONObject()
            .put("index", q.index - from)
            .put("position", pos)
            .put("duration", dur)
            .put("repeat", q.repeat.name)
            .put("shuffle", q.shuffle)
            // Shuffled, the order to go back to when shuffle is turned off: places in [songs].
            .put("original", org.json.JSONArray(if (q.shuffle) q.original.map { o -> kept.indexOfFirst { it === o } }.filter { it >= 0 } else emptyList()))
            .put("songs", with(io.github.momonekomata.sidea.sources.FavoritesJson) { songs.json() })
        saved.edit().putString("queue", json.toString()).apply()
    }

    /**
     * Brings back the last queue, paused where it was, if nothing is queued yet. [resolve] finds
     * each song again (local file, NAS, cloud); songs that can't be found are left out.
     */
    fun restore(resolve: (io.github.momonekomata.sidea.sources.Favorite) -> Track?) {
        if (_queue.value.current != null || !::saved.isInitialized) return
        val o = runCatching { org.json.JSONObject(saved.getString("queue", null) ?: return) }.getOrNull() ?: return
        val songs = with(io.github.momonekomata.sidea.sources.FavoritesJson) { o.optJSONArray("songs")?.list() }.orEmpty()
        val want = o.optInt("index", 0)
        val tracks = ArrayList<Track>()
        val bySaved = HashMap<Int, Track>()
        var index = 0
        songs.forEachIndexed { i, f ->
            val t = resolve(f) ?: return@forEachIndexed
            if (i == want) index = tracks.size
            tracks += t
            bySaved[i] = t
        }
        if (tracks.isEmpty()) return
        val shuffle = o.optBoolean("shuffle", false)
        val original = o.optJSONArray("original")?.let { a -> (0 until a.length()).mapNotNull { bySaved[a.optInt(it, -1)] } }
            // Songs missing from the saved order (it's only kept while shuffled) go at its end.
            ?.let { order -> order + tracks.filter { t -> order.none { it === t } } }
            ?.takeIf { shuffle && it.size == tracks.size } ?: tracks
        main.post {
            if (_queue.value.current != null) return@post
            restored = true
            _queue.value = QueueState(
                tracks, index.coerceIn(0, tracks.size - 1), shuffle = shuffle,
                repeat = runCatching { RepeatMode.valueOf(o.optString("repeat")) }.getOrDefault(RepeatMode.OFF),
                original = original,
            )
            _parked.value = Resume(o.optLong("position"), o.optLong("duration"))
            // The media session comes up with the song, paused, so a headset's play button or
            // the lock screen can carry on with it before the app's own Play is pressed.
            runCatching { appContext.startForegroundService(Intent(appContext, PlaybackService::class.java)) }
        }
    }

    // ---- giving the DAC back while paused ----

    /**
     * Where a parked song resumes: the DAC was handed back while it sat paused, or the queue was
     * brought back from the last time the app ran. The deck shows the song paused at that spot.
     */
    private val _parked = MutableStateFlow<Resume?>(null)
    val parked: StateFlow<Resume?> = _parked.asStateFlow()
    private var parkedAtMs: Long?
        get() = _parked.value?.positionMs
        set(v) {
            val known = if (::engine.isInitialized) engine.state.value.durationMs else 0
            _parked.value = v?.let { Resume(it, known.takeIf { d -> d > 0 } ?: _parked.value?.durationMs ?: 0) }
        }
    private val parkLater = Runnable { if (!engine.state.value.isPlaying) park() }

    /** Releases the output (and the USB DAC) but remembers the song and the spot. */
    private fun park() {
        val s = engine.state.value
        if (_queue.value.current == null || s.output == null) return
        main.removeCallbacks(parkLater)
        parkedAtMs = s.positionMs
        engine.stop(releaseOutput = true)
    }

    private fun pauseAndPark() {
        engine.pause()
        saveQueue()
        // Other apps get the DAC back ten seconds into a pause.
        main.removeCallbacks(parkLater)
        if (UsbExclusive.holding) main.postDelayed(parkLater, 10_000)
    }

    fun togglePlay() {
        if (Cast.active) {
            castToggle()
            return
        }
        val s = engine.state.value
        parkedAtMs?.let { at ->
            parkedAtMs = null
            startCurrent(startMs = at)
            return
        }
        when {
            _queue.value.current == null -> return
            s.isPlaying -> {
                resumeOnGain = false
                pauseAndPark()
            }
            s.isBuffering -> Unit
            s.error != null || s.output == null -> startCurrent()
            else -> if (requestFocus()) engine.resume()
        }
    }

    /** Play/pause on the renderer while casting. */
    private fun castToggle() {
        val s = Cast.state.value
        when {
            _queue.value.current == null -> return
            s.isPlaying -> Cast.pause()
            s.isBuffering -> Unit
            s.error != null || !Cast.loaded -> startCurrent(startMs = s.positionMs)
            else -> Cast.resume()
        }
    }

    /** Play, as a headset or the lock screen means it: carries on if paused, nothing if playing. */
    fun play() {
        val s = state.value
        if (_queue.value.current == null || s.isPlaying || s.isBuffering) return
        togglePlay()
    }

    /** Pause, as a headset or the lock screen means it: nothing if already paused. */
    fun pause() {
        if (state.value.isPlaying) togglePlay()
    }

    fun next(auto: Boolean = false) {
        val q = _queue.value
        val i = q.nextIndex(auto)
        if (i == null) {
            if (auto) {
                if (Cast.active) Cast.stopRemote() else engine.stop()
                abandonFocus()
            }
            return
        }
        _queue.value = q.copy(index = i)
        startCurrent()
    }

    /** Like a tape deck: rewinds the current song first, the previous one only near its start. */
    fun previous() {
        val q = _queue.value
        if (state.value.positionMs > 3000 || (q.index <= 0 && q.repeat != RepeatMode.ALL)) {
            seekTo(0)
            return
        }
        _queue.value = q.copy(index = if (q.index > 0) q.index - 1 else q.tracks.size - 1)
        startCurrent()
    }

    /** While parked, moves where Play will carry on from. */
    fun seekTo(ms: Long) {
        when {
            parkedAtMs != null -> parkedAtMs = ms
            Cast.active -> Cast.seek(ms)
            else -> engine.seekTo(ms)
        }
    }

    fun ownsMixer() = ::engine.isInitialized && engine.holdsBitPerfectMixer

    fun refreshPosition() = if (Cast.active) Cast.refresh() else engine.refreshPosition()

    fun stop() {
        openJob?.cancel()
        parkedAtMs = null
        if (Cast.active) Cast.stopRemote()
        engine.stop()
        abandonFocus()
    }

    private fun startCurrent(startMs: Long = 0, paused: Boolean = false) {
        val track = _queue.value.current ?: return
        openJob?.cancel()
        parkedAtMs = null
        main.removeCallbacks(parkLater)
        if (Cast.active) {
            // On the renderer: the phone's own output stays shut, the renderer gets the song.
            engine.stop(releaseOutput = true)
            abandonFocus()
            appContext.startForegroundService(Intent(appContext, PlaybackService::class.java))
            Cast.load(track, startMs, paused, upcoming())
            return
        }
        // The next song opens straight away: keep the DAC rather than hand it back and retake it.
        engine.stop(releaseOutput = false)
        resumeOnGain = false
        if (!paused && !requestFocus()) {
            engine.fail("Another app (a call?) has the audio right now")
            return
        }
        appContext.startForegroundService(Intent(appContext, PlaybackService::class.java))
        openJob = scope.launch {
            try {
                val source = withContext(Dispatchers.IO) { track.opener(appContext) }
                engine.play(source, startMs, paused)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                engine.fail(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}

/** A song waiting to carry on from [positionMs]; [durationMs] is 0 when not known. */
data class Resume(val positionMs: Long, val durationMs: Long)

private fun Track.displayTitleFor(tags: Map<String, io.github.momonekomata.sidea.sources.Tags>) = tags[id]?.title?.takeIf { it.isNotBlank() } ?: title
private fun Track.displayArtistFor(tags: Map<String, io.github.momonekomata.sidea.sources.Tags>) = tags[id]?.artist?.takeIf { it.isNotBlank() } ?: artist
private fun Track.displayAlbumFor(tags: Map<String, io.github.momonekomata.sidea.sources.Tags>) = tags[id]?.album?.takeIf { it.isNotBlank() } ?: album
