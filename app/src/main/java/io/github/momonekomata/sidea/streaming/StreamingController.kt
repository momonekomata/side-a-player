package io.github.momonekomata.sidea.streaming

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StreamingApp(val label: String, val packageName: String) {
    SPOTIFY("Spotify", "com.spotify.music"),
    APPLE_MUSIC("Apple Music", "com.apple.android.music"),
    TIDAL("TIDAL", "com.aspiro.tidal"),
    YT_MUSIC("YT Music", "com.google.android.apps.youtube.music");

    companion object {
        fun forPackage(pkg: String) = entries.firstOrNull { it.packageName == pkg }
    }
}

data class RemoteQueueItem(val id: Long, val title: String, val subtitle: String)

data class RemoteNowPlaying(
    val app: StreamingApp,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isPlaying: Boolean,
    val art: Bitmap?,
    val queue: List<RemoteQueueItem>,
    val activeQueueId: Long,
    private val state: PlaybackState?,
) {
    /** Extrapolated from the last report, as the apps only report on change. */
    val positionMs: Long
        get() {
            val s = state ?: return 0
            if (s.state != PlaybackState.STATE_PLAYING) return s.position
            val elapsed = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
            val p = s.position + (elapsed * s.playbackSpeed).toLong()
            return if (durationMs > 0) p.coerceIn(0, durationMs) else p
        }
}

/**
 * Drives the streaming apps through their own media sessions: the same channel Android Auto,
 * smartwatches and the lock screen use. Their audio, accounts and lossless settings stay theirs;
 * Side A is the cassette deck on top.
 */
class StreamingController(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MediaNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var started = false

    private val _nowPlaying = MutableStateFlow<Map<StreamingApp, RemoteNowPlaying>>(emptyMap())
    val nowPlaying: StateFlow<Map<StreamingApp, RemoteNowPlaying>> = _nowPlaying.asStateFlow()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> bind(list.orEmpty()) }

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun accessSettingsIntent() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Safe to call repeatedly; does nothing until notification access is granted. */
    fun start() {
        if (started || !hasAccess()) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            bind(manager.getActiveSessions(listener))
            started = true
        } catch (_: SecurityException) {
            // Access was revoked between the check and the call.
        }
    }

    fun stop() {
        if (!started) return
        manager.removeOnActiveSessionsChangedListener(sessionsChanged)
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
        started = false
    }

    private fun bind(controllers: List<MediaController>) {
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
        controllers.filter { StreamingApp.forPackage(it.packageName) != null }.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) = publish()
                override fun onSessionDestroyed() = publish()
            }
            c.registerCallback(cb, main)
            callbacks[c] = cb
        }
        publish()
    }

    private fun publish() {
        _nowPlaying.value = callbacks.keys.mapNotNull { c ->
            val app = StreamingApp.forPackage(c.packageName) ?: return@mapNotNull null
            val m = c.metadata
            val st = c.playbackState
            app to RemoteNowPlaying(
                app = app,
                title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
                isPlaying = st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING,
                art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                queue = c.queue.orEmpty().map {
                    RemoteQueueItem(it.queueId, it.description.title?.toString().orEmpty(), it.description.subtitle?.toString().orEmpty())
                },
                activeQueueId = st?.activeQueueItemId ?: -1,
                state = st,
            )
        }.toMap()
    }

    private fun controls(app: StreamingApp) =
        callbacks.keys.firstOrNull { it.packageName == app.packageName }?.transportControls

    fun playPause(app: StreamingApp) {
        val c = controls(app) ?: return launch(app)
        if (_nowPlaying.value[app]?.isPlaying == true) c.pause() else c.play()
    }

    fun next(app: StreamingApp) = controls(app)?.skipToNext()
    fun previous(app: StreamingApp) = controls(app)?.skipToPrevious()
    fun seekTo(app: StreamingApp, ms: Long) = controls(app)?.seekTo(ms)
    fun playQueueItem(app: StreamingApp, id: Long) = controls(app)?.skipToQueueItem(id)

    fun isInstalled(app: StreamingApp) = context.packageManager.getLaunchIntentForPackage(app.packageName) != null

    /** Opens the app so the user can pick a playlist there; Side A follows whatever it plays. */
    fun launch(app: StreamingApp) {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${app.packageName}"))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
