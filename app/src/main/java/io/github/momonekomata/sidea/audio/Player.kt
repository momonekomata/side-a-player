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
import io.github.momonekomata.sidea.sources.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}

/** The app-wide local player: a queue in front of [AudioEngine]. Shared by the UI and the service. */
object Player {
    private lateinit var appContext: Context
    private lateinit var engine: AudioEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var openJob: Job? = null

    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue.asStateFlow()
    val state: StateFlow<EngineState> get() = engine.state

    /** Identifies one queue + position, so a late gapless hand-over can't land on a new queue. */
    private class Slot(val tracks: List<Track>, val index: Int)

    private val callbacks = object : EngineCallbacks {
        override fun upNext(): UpNext? {
            val q = _queue.value
            val i = q.nextIndex(auto = true) ?: return null
            val t = q.tracks.getOrNull(i) ?: return null
            return UpNext(Slot(q.tracks, i)) { runBlocking(Dispatchers.IO) { t.opener(appContext) } }
        }

        override fun onAdvanced(tag: Any) {
            val slot = tag as Slot
            if (_queue.value.tracks === slot.tracks) _queue.value = _queue.value.copy(index = slot.index)
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
            // A call, a navigation prompt: pause and pick up afterwards. Ducking would mean
            // changing the samples, so a bit-perfect player pauses instead.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
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
            .setWillPauseWhenDucked(true)
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
        if (_queue.value.current == null || s.output == null || parkedAtMs != null) return
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
        if (usbDriverBusy()) return
        resumeOnGain = false
        if (engine.state.value.isPlaying) pauseAndPark()
    }

    fun init(context: Context) {
        if (::engine.isInitialized) return
        appContext = context.applicationContext
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
        io.github.momonekomata.sidea.streaming.StreamingEq.start(appContext)
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
                io.github.momonekomata.sidea.streaming.StreamingEq.setConfig(it.eq)
                engine.usbVolume = it.usbVolume
                if (engine.exclusiveUsb != it.exclusiveUsb) {
                    engine.exclusiveUsb = it.exclusiveUsb
                    reopenOnNewOutput()
                }
            }
        }
    }

    fun playQueue(tracks: List<Track>, index: Int) {
        val q = _queue.value
        var next = QueueState(tracks, index, shuffle = false, repeat = q.repeat, original = tracks)
        if (q.shuffle) next = shuffled(next)
        _queue.value = next
        startCurrent()
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
    }

    fun cycleRepeat() {
        val q = _queue.value
        _queue.value = q.copy(repeat = RepeatMode.entries[(q.repeat.ordinal + 1) % RepeatMode.entries.size])
    }

    // ---- skipping, speed ----

    /** Jumps by [deltaMs] within the song (±5 s, ±10 s, or held fast-forward/rewind). */
    fun skipBy(deltaMs: Long) {
        val s = engine.state.value
        if (_queue.value.current == null) return
        val to = (s.positionMs + deltaMs).coerceIn(0, if (s.durationMs > 0) s.durationMs - 500 else Long.MAX_VALUE)
        if (parkedAtMs != null) parkedAtMs = to else seekTo(to)
    }

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Playback speed with the pitch kept. Anything but 1× goes through Android's time-stretch. */
    fun setSpeed(v: Float) {
        if (v == _speed.value) return
        _speed.value = v
        engine.speed = v
        reopenOnNewOutput()
    }

    // ---- giving the DAC back while paused ----

    /** Where a parked song resumes: the DAC was handed back while it sat paused. */
    private var parkedAtMs: Long? = null
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
        // Other apps get the DAC back half a minute into a pause.
        main.removeCallbacks(parkLater)
        if (UsbExclusive.holding) main.postDelayed(parkLater, 30_000)
    }

    fun togglePlay() {
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

    fun next(auto: Boolean = false) {
        val q = _queue.value
        val i = q.nextIndex(auto)
        if (i == null) {
            if (auto) {
                engine.stop()
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
        if (engine.state.value.positionMs > 3000 || (q.index <= 0 && q.repeat != RepeatMode.ALL)) {
            seekTo(0)
            return
        }
        _queue.value = q.copy(index = if (q.index > 0) q.index - 1 else q.tracks.size - 1)
        startCurrent()
    }

    fun seekTo(ms: Long) = engine.seekTo(ms)

    fun ownsMixer() = ::engine.isInitialized && engine.holdsBitPerfectMixer

    fun refreshPosition() = engine.refreshPosition()

    fun stop() {
        openJob?.cancel()
        parkedAtMs = null
        engine.stop()
        abandonFocus()
    }

    private fun startCurrent(startMs: Long = 0, paused: Boolean = false) {
        val track = _queue.value.current ?: return
        openJob?.cancel()
        parkedAtMs = null
        main.removeCallbacks(parkLater)
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
