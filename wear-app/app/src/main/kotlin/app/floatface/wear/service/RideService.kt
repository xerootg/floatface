package app.floatface.wear.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that will own the BLE connection, keepalive, and Health
 * Services recording (SPEC §9 / §9a): foregroundServiceType
 * connectedDevice|location|health, START_STICKY, PARTIAL_WAKE_LOCK while
 * connected. Implemented test-first by the swarm; declared now so the manifest is
 * coherent.
 */
class RideService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
