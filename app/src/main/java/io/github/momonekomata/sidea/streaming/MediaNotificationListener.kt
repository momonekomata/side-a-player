package io.github.momonekomata.sidea.streaming

import android.service.notification.NotificationListenerService

/**
 * Never reads notifications. Android only lets an app see other apps' media sessions
 * (MediaSessionManager.getActiveSessions) while it holds notification access, so this
 * empty listener is the key that unlocks controlling Spotify, Apple Music, TIDAL and YT Music.
 */
class MediaNotificationListener : NotificationListenerService()
