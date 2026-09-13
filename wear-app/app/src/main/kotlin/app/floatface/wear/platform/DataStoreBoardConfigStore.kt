package app.floatface.wear.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.floatface.core.BoardConfig
import app.floatface.core.BoardConfigStore
import app.floatface.core.UnlockBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * [BoardConfigStore] adapter (SPEC §10) backed by Jetpack DataStore Preferences,
 * so the per-owner unlock bytes and target BLE MAC are configured **at runtime**
 * (on-watch) and persisted — nothing has to be compiled in.
 *
 * [defaultUnlockBytesHex] is an optional compile-time seed (e.g.
 * `BuildConfig.UNLOCK_BYTES_HEX`, itself from the gitignored `unlock.properties`).
 * It is used only when it parses to a configured value AND the user has not set
 * their own; a runtime value always wins, and clearing it reverts to the seed.
 */
class DataStoreBoardConfigStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
    defaultUnlockBytesHex: String? = null,
) : BoardConfigStore {

    private val seedHex: String? =
        defaultUnlockBytesHex?.takeIf { UnlockBytes.isConfigured(UnlockBytes.fromHex(it)) }

    private val _config = MutableStateFlow(BoardConfig(unlockBytesHex = seedHex))
    override val config: StateFlow<BoardConfig> = _config

    init {
        dataStore.data
            .map { prefs ->
                BoardConfig(
                    unlockBytesHex = prefs[UNLOCK_HEX_KEY] ?: seedHex,
                    bleMac = prefs[BLE_MAC_KEY],
                )
            }
            .onEach { _config.value = it }
            .launchIn(scope)
    }

    override suspend fun setUnlockBytesHex(hex: String?) {
        dataStore.edit { prefs ->
            val cleaned = hex?.trim()
            if (cleaned.isNullOrEmpty()) prefs.remove(UNLOCK_HEX_KEY) else prefs[UNLOCK_HEX_KEY] = cleaned
        }
    }

    override suspend fun setBleMac(mac: String?) {
        dataStore.edit { prefs ->
            val cleaned = mac?.trim()
            if (cleaned.isNullOrEmpty()) prefs.remove(BLE_MAC_KEY) else prefs[BLE_MAC_KEY] = cleaned
        }
    }

    companion object {
        val UNLOCK_HEX_KEY: Preferences.Key<String> = stringPreferencesKey("unlock_bytes_hex")
        val BLE_MAC_KEY: Preferences.Key<String> = stringPreferencesKey("ble_mac")
    }
}
