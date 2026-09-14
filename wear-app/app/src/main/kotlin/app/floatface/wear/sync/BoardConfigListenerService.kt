package app.floatface.wear.sync

import app.floatface.core.ConfigSync
import app.floatface.wear.FloatfaceApplication
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives a board config pushed from the companion phone app over the Wear Data
 * Layer (SPEC §10) and writes it into the shared [app.floatface.core.BoardConfigStore]
 * via [ConfigSync.applyPushedConfig] (which validates each field). The pushed item
 * is then deleted — "consume once" — so a later on-watch clear of the config is
 * not silently undone the next time the phone reconnects.
 *
 * Data Layer delivery requires the phone and watch apps to share the same package
 * name AND signing key; this service is bound by Play Services, hence exported.
 */
class BoardConfigListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val container = (application as FloatfaceApplication).container
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != ConfigSync.PATH) continue

            val map = DataMapItem.fromDataItem(item).dataMap
            container.logger.d(TAG, "board config pushed from phone")
            ConfigSync.applyPushedConfig(
                unlockHex = map.getString(ConfigSync.KEY_UNLOCK_HEX),
                bleMac = map.getString(ConfigSync.KEY_BLE_MAC),
                store = container.boardConfigStore,
                scope = container.applicationScope,
            )
            // Consume-once so a watch-side clear isn't re-applied on next sync.
            Wearable.getDataClient(this).deleteDataItems(item.uri)
        }
    }

    private companion object {
        const val TAG = "BoardConfigListener"
    }
}
