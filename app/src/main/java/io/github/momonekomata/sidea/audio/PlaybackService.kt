package io.github.momonekomata.sidea.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import io.github.momonekomata.sidea.MainActivity
import io.github.momonekomata.sidea.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps local playback alive with the screen off and gives it a media session, so the lock
 * screen, headset buttons and Bluetooth controls work.
 */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        Player.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW),
        )
        session = MediaSession(this, "SideA").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Player.togglePlay()
                override fun onPause() = Player.togglePlay()
                override fun onSkipToNext() = Player.next()
                override fun onSkipToPrevious() = Player.previous()
                override fun onSeekTo(pos: Long) = Player.seekTo(pos)
                override fun onStop() { Player.stop(); stopSelf() }
            })
            isActive = true
        }
        startForeground(ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        scope.launch {
            // Position ticks are left out on purpose: the session extrapolates them itself.
            combine(Player.queue, Player.state) { q, s -> Triple(q.current, s.isPlaying, s.durationMs) }
                .distinctUntilChanged()
                .collect { (t, _, _) ->
                    val s = Player.state.value
                    session.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, t?.title.orEmpty())
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, t?.artist.orEmpty())
                            .putString(MediaMetadata.METADATA_KEY_ALBUM, t?.album.orEmpty())
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
                            .build(),
                    )
                    session.setPlaybackState(
                        PlaybackState.Builder()
                            .setActions(
                                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                    PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP,
                            )
                            .setState(
                                if (s.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                                s.positionMs,
                                if (s.isPlaying) 1f else 0f,
                            )
                            .build(),
                    )
                    getSystemService(NotificationManager::class.java).notify(ID, buildNotification())
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> Player.togglePlay()
            ACTION_NEXT -> Player.next()
            ACTION_PREV -> Player.previous()
        }
        return START_NOT_STICKY
    }

    private fun action(icon: Int, title: String, action: String) = Notification.Action.Builder(
        Icon.createWithResource(this, icon),
        title,
        PendingIntent.getService(this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    private fun buildNotification(): Notification {
        val t = Player.queue.value.current
        val playing = Player.state.value.isPlaying
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(t?.title ?: "Side A")
            .setContentText(listOf(t?.artist, t?.album).filter { !it.isNullOrBlank() }.joinToString(" · "))
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE),
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "playback"
        private const val ID = 1
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_NEXT = "next"
        private const val ACTION_PREV = "prev"
    }
}
