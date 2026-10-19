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

data class QueueState(val tracks: List<Track> = emptyList(), val index: Int = -1) {
    val current: Track? get() = tracks.getOrNull(index)
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
            val i = q.index + 1
            val t = q.tracks.getOrNull(i) ?: return null
            return UpNext(Slot(q.tracks, i)) { runBlocking(Dispatchers.IO) { t.opener(appContext) } }
        }

        override fun onAdvanced(tag: Any) {
            val slot = tag as Slot
            if (_queue.value.tracks === slot.tracks) _queue.value = QueueState(slot.tracks, slot.index)
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
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                engine.pause()
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
        if (_queue.value.current == null || s.output == null) return
        startCurrent(startMs = s.positionMs, paused = !s.isPlaying)
    }

    /**
     * Side A's USB driver taking the DAC from Android (or handing it back) makes the DAC
     * vanish from Android's view for a moment. That isn't an unplug.
     */
    private fun usbDriverBusy() = UsbExclusive.holding || System.currentTimeMillis() < UsbExclusive.busyUntil

    private var askedUsb: String? = null

    /** Volume while Side A's USB driver drives the DAC; Android's volume can't reach it then. */
    fun setUsbVolume(v: Float) = Settings.update { it.copy(usbVolume = v.coerceIn(0f, 1f)) }

    /** Headphones or the DAC were unplugged: pause rather than blast the phone speaker. */
    fun pauseForNoisy() {
        if (usbDriverBusy()) return
        resumeOnGain = false
        if (engine.state.value.isPlaying) engine.pause()
    }

    fun init(context: Context) {
        if (::engine.isInitialized) return
        appContext = context.applicationContext
        audioManager = appContext.getSystemService(AudioManager::class.java)
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        UsbExclusive.init(appContext)
        UsbExclusive.onPermissionGranted = { main.post { reopenOnNewOutput() } }
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
    }

    fun playQueue(tracks: List<Track>, index: Int) {
        _queue.value = QueueState(tracks, index)
        startCurrent()
    }

    fun togglePlay() {
        val s = engine.state.value
        when {
            _queue.value.current == null -> return
            s.isPlaying -> {
                resumeOnGain = false
                engine.pause()
            }
            s.isBuffering -> Unit
            s.error != null || s.output == null -> startCurrent()
            else -> if (requestFocus()) engine.resume()
        }
    }

    fun next(auto: Boolean = false) {
        val q = _queue.value
        if (q.index + 1 >= q.tracks.size) {
            if (auto) {
                engine.stop()
                abandonFocus()
            }
            return
        }
        _queue.value = q.copy(index = q.index + 1)
        startCurrent()
    }

    /** Like a tape deck: rewinds the current song first, the previous one only near its start. */
    fun previous() {
        val q = _queue.value
        if (engine.state.value.positionMs > 3000 || q.index <= 0) {
            seekTo(0)
            return
        }
        _queue.value = q.copy(index = q.index - 1)
        startCurrent()
    }

    fun seekTo(ms: Long) = engine.seekTo(ms)

    fun ownsMixer() = ::engine.isInitialized && engine.holdsBitPerfectMixer

    fun refreshPosition() = engine.refreshPosition()

    fun stop() {
        openJob?.cancel()
        engine.stop()
        abandonFocus()
    }

    private fun startCurrent(startMs: Long = 0, paused: Boolean = false) {
        val track = _queue.value.current ?: return
        openJob?.cancel()
        engine.stop()
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
