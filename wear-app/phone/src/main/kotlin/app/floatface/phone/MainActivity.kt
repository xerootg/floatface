package app.floatface.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.floatface.phone.sync.WatchAppStatus
import app.floatface.phone.sync.WearConfigSender
import app.floatface.phone.sync.WearWatchInstaller

/**
 * Companion phone app entry point (SPEC §10): enter the captured unlock bytes and
 * (optionally) the board BLE MAC on the phone's keyboard, then push them to the
 * paired watch over the Wear Data Layer. The watch app remains fully standalone;
 * this is an optional provisioning helper.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sender = WearConfigSender(applicationContext)
        val installer = WearWatchInstaller(applicationContext)
        setContent {
            MaterialTheme {
                val viewModel: PhoneViewModel = viewModel(factory = PhoneViewModel.Factory(sender, installer))
                val state by viewModel.state.collectAsStateWithLifecycle()
                // Re-query install state each time the screen resumes (the user may
                // have installed the watch app on the watch in the meantime).
                LaunchedEffect(Unit) { viewModel.refreshWatchStatus() }
                PhoneScreen(
                    state = state,
                    onUnlockHexChange = viewModel::onUnlockHexChange,
                    onBleMacChange = viewModel::onBleMacChange,
                    onSend = viewModel::send,
                    onInstallWatch = viewModel::installWatchApp,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneScreen(
    state: PhoneUiState,
    onUnlockHexChange: (String) -> Unit,
    onBleMacChange: (String) -> Unit,
    onSend: () -> Unit,
    onInstallWatch: () -> Unit = {},
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Floatface board setup") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(watchStatusText(state.watchAppStatus), style = MaterialTheme.typography.bodySmall)
            if (state.canInstall) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onInstallWatch, modifier = Modifier.fillMaxWidth()) {
                    Text("Install watch app")
                }
            }
            state.installMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))

            Text("Send your captured board config to the watch.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.unlockHex,
                onValueChange = onUnlockHexChange,
                label = { Text("Unlock bytes (40 hex chars)") },
                singleLine = true,
                isError = state.unlockHex.isNotBlank() && !state.unlockValid,
                supportingText = {
                    if (state.unlockHex.isNotBlank() && !state.unlockValid) {
                        Text("Must be 40 hex characters (20 non-zero bytes)")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.bleMac,
                onValueChange = onBleMacChange,
                label = { Text("BLE MAC (optional, AA:BB:CC:DD:EE:FF)") },
                singleLine = true,
                isError = !state.macValid,
                supportingText = {
                    if (!state.macValid) Text("Must look like AA:BB:CC:DD:EE:FF") else Text("Leave blank to match any Onewheel")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))

            Button(onClick = onSend, enabled = state.canSend, modifier = Modifier.fillMaxWidth()) {
                Text("Send to watch")
            }
            Spacer(Modifier.height(12.dp))

            Text(statusText(state.status), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun statusText(status: SendStatus): String = when (status) {
    SendStatus.Idle -> ""
    SendStatus.Sending -> "Sending…"
    is SendStatus.Sent -> "Sent to ${status.nodeCount} watch(es). Confirm on the watch."
    SendStatus.NoWatch -> "No watch connected. Pair a Wear OS watch and try again."
    is SendStatus.Failed -> "Failed: ${status.message}"
}

internal fun watchStatusText(status: WatchAppStatus): String = when (status) {
    WatchAppStatus.Unknown -> "Checking watch…"
    WatchAppStatus.NoWatch -> "No watch connected."
    is WatchAppStatus.Installed -> "Watch app installed ✓"
    is WatchAppStatus.NotInstalled -> "Watch app not installed on your watch."
}
