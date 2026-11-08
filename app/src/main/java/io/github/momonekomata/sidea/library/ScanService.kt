package io.github.momonekomata.sidea.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.github.momonekomata.sidea.MainActivity
import io.github.momonekomata.sidea.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Keeps a Library scan going when the app is closed or the screen goes off: a foreground
 * service (Android leaves those running) with the scan's progress in its notification, and
 * the CPU and Wi-Fi kept awake while a NAS is read. Stops itself when the scan is done.
 */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Library scan", NotificationManager.IMPORTANCE_LOW),
        )
        val first = notification(Library.scan.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, first)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WALKDECK:scan").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL, "WALKDECK:scan").apply {
                setReferenceCounted(false)
                acquire()
            }
        val nm = getSystemService(NotificationManager::class.java)
        scope.launch {
            combine(Library.scanning, Library.scan) { busy, s -> busy to s }.conflate().collect { (busy, s) ->
                if (!busy) {
                    stopSelf()
                    return@collect
                }
                nm.notify(ID, notification(s))
                // A NAS scan changes this many times a second; once a second is plenty.
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) Library.stopScan()
        // Killed anyway: the next start picks the scan up (sources not finished are scanned again).
        return START_NOT_STICKY
    }

    /** Android 15 allows a few hours of this a day; past that the scan goes on only while the app is open. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    private fun notification(s: ScanState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (s.source.isNotEmpty()) "Scanning ${s.source}" else "Scanning the Library")
            .setContentText(
                if (s.source.isEmpty()) "Starting…"
                else "${s.folders} folders · ${s.songs} songs" + (if (s.toRead > 0) " · reading ${s.read} of ${s.toRead}" else ""),
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        if (s.toRead > 0) b.setProgress(s.toRead, s.read, false) else b.setProgress(0, 0, true)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    companion object {
        private const val CHANNEL = "scan"
        private const val ID = 2
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            // Not allowed from the background on Android 12+: the scan still runs while the app does.
            runCatching { context.startForegroundService(Intent(context, ScanService::class.java)) }
        }
    }
}
