package io.github.momonekomata.sidea.audio

import android.content.Context
import android.content.Intent
import io.github.momonekomata.sidea.Settings
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

    fun init(context: Context) {
        if (::engine.isInitialized) return
        appContext = context.applicationContext
        Settings.init(appContext)
        engine = AudioEngine(appContext, callbacks)
        scope.launch { Settings.prefs.collect { engine.gapless = it.gapless } }
    }

    fun playQueue(tracks: List<Track>, index: Int) {
        _queue.value = QueueState(tracks, index)
        startCurrent()
    }

    fun togglePlay() {
        val s = engine.state.value
        when {
            _queue.value.current == null -> return
            s.isPlaying -> engine.pause()
            s.isBuffering -> Unit
            s.error != null || s.output == null -> startCurrent()
            else -> engine.resume()
        }
    }

    fun next(auto: Boolean = false) {
        val q = _queue.value
        if (q.index + 1 >= q.tracks.size) {
            if (auto) engine.stop()
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

    fun refreshPosition() = engine.refreshPosition()

    fun stop() {
        openJob?.cancel()
        engine.stop()
    }

    private fun startCurrent() {
        val track = _queue.value.current ?: return
        openJob?.cancel()
        engine.stop()
        appContext.startForegroundService(Intent(appContext, PlaybackService::class.java))
        openJob = scope.launch {
            try {
                val source = withContext(Dispatchers.IO) { track.opener(appContext) }
                engine.play(source)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                engine.fail(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
