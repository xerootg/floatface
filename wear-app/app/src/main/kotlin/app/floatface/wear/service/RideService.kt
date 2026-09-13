package app.floatface.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.floatface.wear.AppContainer
import app.floatface.wear.FloatfaceApplication
import app.floatface.wear.MainActivity
import app.floatface.wear.R

/**
 * Foreground service owning BLE + keepalive + recording, independent of the
 * Activity lifecycle (SPEC §9 / §9a). Holds no ports/controller of its own —
 * it reads the single [AppContainer] from [FloatfaceApplication] and ensures
 * [AppContainer.controller] is started, so the Activity and the service never
 * race to build two controllers.
 *
 * `onStartCommand` returns [Service.START_STICKY] so the system recreates
 * this service (and thus re-attaches to / restarts the controller) after it
 * is killed while a ride may still be in progress. A [PowerManager.WakeLock]
 * is held for the service's active lifetime so BLE keepalive keeps firing in
 * Doze (SPEC §9); it is released in [onDestroy].
 */
class RideService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    private val container: AppContainer
        get() = (application as FloatfaceApplication).container

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        container.ensureStarted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        container.ensureStarted()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ride").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_DURATION_MS)
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
        // Long upper bound; the lock is explicitly released in onDestroy() well before this.
        const val MAX_WAKE_LOCK_DURATION_MS = 12 * 60 * 60 * 1000L
    }
}
