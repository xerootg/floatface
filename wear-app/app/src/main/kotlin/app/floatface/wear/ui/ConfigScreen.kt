package app.floatface.wear.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Text
import app.floatface.core.BoardConfig
import app.floatface.core.UnlockBytes

/**
 * On-watch board configuration screen (SPEC §10): shows whether the unlock bytes
 * and BLE MAC are set and lets the owner enter/clear them at runtime, so nothing
 * has to be compiled in. Pure composable — text entry is launched by the host via
 * [onEditUnlockBytes]/[onEditBleMac] (Wear RemoteInput) and persisted upstream.
 * Sensitive unlock bytes are never rendered; only whether they are configured.
 */
@Composable
fun ConfigScreen(
    config: BoardConfig,
    onEditUnlockBytes: () -> Unit,
    onEditBleMac: () -> Unit,
    onClearConfig: () -> Unit,
    onBack: () -> Unit,
) {
    val unlockConfigured = UnlockBytes.isConfigured(config.unlockBytesHex?.let { UnlockBytes.fromHex(it) })

    ScalingLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        item { Text(text = "Board config", fontSize = 18.sp, fontWeight = FontWeight.Bold) }

        item { Text(text = "Unlock bytes: ${if (unlockConfigured) "set" else "not set"}", fontSize = 13.sp) }
        item {
            Button(onClick = onEditUnlockBytes, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Set unlock bytes")
            }
        }

        item {
            val mac = config.bleMac?.takeUnless { it.isBlank() }
            Text(text = "BLE MAC: ${mac ?: "any Onewheel"}", fontSize = 13.sp)
        }
        item {
            Button(onClick = onEditBleMac, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Set BLE MAC")
            }
        }

        item {
            Button(onClick = onClearConfig, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(text = "Clear")
            }
        }
        item {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Back")
            }
        }
    }
}
