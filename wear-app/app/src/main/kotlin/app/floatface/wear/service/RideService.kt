package app.floatface.wear.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.floatface.core.ConnectionState
import app.floatface.wear.AppContainer
import app.floatface.wear.FloatfaceApplication
import app.floatface.wear.MainActivity
import app.floatface.wear.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service owning BLE + keepalive + recording, independent of the
 * Activity lifecycle (SPEC §9 / §9a). It holds no ports/controller of its own —
 * it reads the single [AppContainer] from [FloatfaceApplication] and ensures
 * [AppContainer.controller] is started, so the Activity and the service never
 * race to build two controllers ([AppContainer.ensureStarted] is idempotent).
 *
 * The `location`/`health` foreground-service types require their runtime
 * permissions to be granted before [startForeground]; on Android 14 calling it
 * without them throws. MainActivity gates the *normal* start on the permission
 * grant, but this service also guards defensively (a system restart could
 * recreate it) by stopping itself if the prerequisites are missing.
 *
 * The [PowerManager.WakeLock] is held **only while connected** (SPEC §9), driven
 * by [AppContainer.controller] state, and the service tears itself down
 * (notification, wake lock, self-stop) once the ride is shut down.
 * [onStartCommand] returns [START_NOT_STICKY] so an idle service killed after
 * shutdown is not perpetually resurrected; MainActivity restarts it on next launch.
 */
class RideService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null

    private val container: AppContainer
        get() = (application as FloatfaceApplication).container

    override fun onCreate() {
        super.onCreate()
        if (!hasForegroundServicePermissions()) {
            // Started without the location/health prerequisites (e.g. system
            // restart before the user granted them). Do not crash on startForeground.
            stopSelf()
            return
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        container.ensureStarted()
        observeConnection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (hasForegroundServicePermissions()) container.ensureStarted()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /** Hold the wake lock only while connected; self-stop once the ride shuts down. */
    private fun observeConnection() {
        observerJob = serviceScope.launch {
            container.controller.uiState
                .map { it.connection }
                .collect { connection ->
                    when (connection) {
                        ConnectionState.Connected -> acquireWakeLock()
                        ConnectionState.ShuttingDown -> {
                            releaseWakeLock()
                            stopForegroundCompat()
                            stopSelf()
                        }
                        else -> releaseWakeLock()
                    }
                }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ride").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_DURATION_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun hasForegroundServicePermissions(): Boolean {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)      // location FGS type + GPS
            add(Manifest.permission.ACTIVITY_RECOGNITION)      // health FGS type prerequisite
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)     // connectedDevice FGS type
            }
        }
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ride_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ride_notification_title))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "ride_service"
        const val NOTIFICATION_ID = 1
        // Long upper bound; the lock is released on disconnect/shutdown well before this.
        const val MAX_WAKE_LOCK_DURATION_MS = 12 * 60 * 60 * 1000L
    }
}
