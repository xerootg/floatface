package app.floatface.phone.sync

import android.content.Context
import app.floatface.core.ConfigSync
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Outcome of a push to the watch (SPEC §10). */
sealed interface SendResult {
    data class Sent(val nodeCount: Int) : SendResult
    data object NoWatch : SendResult
    data class Error(val message: String) : SendResult
}

/** Sends a board config to the paired watch. Interface so the ViewModel is fake-testable. */
interface ConfigSender {
    suspend fun send(unlockHex: String?, bleMac: String?): SendResult
}

/** Pure builder for the Data Layer payload (SPEC §10) — no Android/GMS types, unit-testable. */
object ConfigDataMap {
    fun build(unlockHex: String?, bleMac: String?, nonce: Long): Map<String, String> = buildMap {
        unlockHex?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ConfigSync.KEY_UNLOCK_HEX, it) }
        bleMac?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ConfigSync.KEY_BLE_MAC, it) }
        // Always present so two otherwise-identical pushes still register as a
        // change (and so the watch's consume-once delete is followed by a fresh item).
        put(ConfigSync.KEY_NONCE, nonce.toString())
    }
}

/**
 * [ConfigSender] backed by the Wear Data Layer [Wearable.getDataClient]. Puts the
 * config at [ConfigSync.PATH]; the watch's BoardConfigListenerService receives it.
 * Requires the phone and watch apps to share the same package name + signing key.
 */
class WearConfigSender(
    private val context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ConfigSender {

    override suspend fun send(unlockHex: String?, bleMac: String?): SendResult {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) return SendResult.NoWatch

        val request = PutDataMapRequest.create(ConfigSync.PATH).apply {
            ConfigDataMap.build(unlockHex, bleMac, nowMillis()).forEach { (k, v) -> dataMap.putString(k, v) }
            setUrgent()
        }
        Wearable.getDataClient(context).putDataItem(request.asPutDataRequest()).await()
        return SendResult.Sent(nodes.size)
    }
}
