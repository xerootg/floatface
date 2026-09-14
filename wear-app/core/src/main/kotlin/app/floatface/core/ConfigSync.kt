package app.floatface.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared contract for pushing [BoardConfig] from the companion phone app to the
 * watch over the Wear Data Layer (SPEC §10). Both the phone sender and the watch
 * receiver reference these constants so the wire format stays in one place. Pure
 * Kotlin — no Android / Play Services types leak into :core.
 */
object ConfigSync {
    /** Data Layer item path carrying a pushed board config. */
    const val PATH = "/floatface/board-config"

    const val KEY_UNLOCK_HEX = "unlock_hex"
    const val KEY_BLE_MAC = "ble_mac"

    /** A per-send nonce so repeated identical pushes still register as a change. */
    const val KEY_NONCE = "nonce"

    /**
     * Static Data Layer capability the watch app advertises (via res/values/wear.xml).
     * The phone queries CapabilityClient for it to tell whether the watch app is
     * installed on a connected node before pushing config or offering to install.
     */
    const val WATCH_APP_CAPABILITY = "floatface_watch_app"

    /**
     * The shared applicationId of the watch app — also its Play Store listing id.
     * The phone opens `market://details?id=$WATCH_APP_PACKAGE` on the watch to help
     * the user install it. The phone app MUST use this same applicationId + signing
     * key or the Data Layer will not pair the two apps.
     */
    const val WATCH_APP_PACKAGE = "app.floatface.wear"

    /** What [applyPushedConfig] actually applied (for logging / tests). */
    data class Applied(val unlockApplied: Boolean, val macApplied: Boolean) {
        val anything: Boolean get() = unlockApplied || macApplied
    }

    /**
     * Applies a config pushed from the phone into [store]. Only non-blank, VALID
     * fields are applied — an absent/blank/invalid field is left untouched, never
     * stored as garbage (a blank field is "don't change this", not "clear it";
     * clearing is done on the watch). The store write is launched on [scope]; the
     * returned [Applied] reflects the validation decision synchronously.
     */
    fun applyPushedConfig(
        unlockHex: String?,
        bleMac: String?,
        store: BoardConfigStore,
        scope: CoroutineScope,
    ): Applied {
        val hex = unlockHex?.trim()?.takeIf { it.isNotEmpty() }
            ?.takeIf { UnlockBytes.isConfigured(UnlockBytes.fromHex(it)) }
        val mac = bleMac?.trim()?.takeIf { it.isNotEmpty() }
            ?.takeIf { BoardConfig.isValidMac(it) }

        if (hex != null || mac != null) {
            scope.launch {
                if (hex != null) store.setUnlockBytesHex(hex)
                if (mac != null) store.setBleMac(mac)
            }
        }
        return Applied(unlockApplied = hex != null, macApplied = mac != null)
    }
}
