package app.floatface.wear

import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.wear.input.RemoteInputIntentHelper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import app.floatface.core.UserIntent
import app.floatface.wear.service.RideService
import app.floatface.wear.ui.FloatfaceApp
import app.floatface.wear.ui.OnewheelViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Launcher activity (SPEC §8/§13/§11). Gates the foreground [RideService] and the
 * BLE/keepalive/recording lifecycle on the dangerous runtime permissions: the
 * service's `location`/`health` foreground-service types would crash
 * [android.app.Service.startForeground] on Android 14 if started before their
 * permissions are granted. Only once the permissions are held does it start the
 * service and render [FloatfaceApp] driven by
 * [app.floatface.core.OnewheelController.uiState]; otherwise it shows a rationale.
 */
class MainActivity : ComponentActivity() {

    private val permissionsGranted = MutableStateFlow(false)

    private val requiredPermissions: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startIfPermitted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val granted by permissionsGranted.collectAsStateWithLifecycle()
            if (granted) {
                val container = (application as FloatfaceApplication).container
                val viewModel: OnewheelViewModel = viewModel(
                    factory = OnewheelViewModel.Factory(container.controller, container.boardConfigStore),
                )
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val boardConfig by viewModel.config.collectAsStateWithLifecycle()

                // On-watch text entry for the runtime config (SPEC §10) via Wear
                // RemoteInput (keyboard/voice); parsed + persisted by the ViewModel.
                val unlockLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    remoteInputResult(result.data, KEY_UNLOCK)?.let { viewModel.setUnlockBytesHex(it) }
                }
                val macLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    remoteInputResult(result.data, KEY_MAC)?.let { viewModel.setBleMac(it) }
                }

                FloatfaceApp(
                    state = uiState,
                    onIntent = viewModel::dispatch,
                    config = boardConfig,
                    onEditUnlockBytes = { unlockLauncher.launch(remoteInputIntent(KEY_UNLOCK, "Unlock bytes (40 hex)")) },
                    onEditBleMac = { macLauncher.launch(remoteInputIntent(KEY_MAC, "BLE MAC (AA:BB:CC:DD:EE:FF)")) },
                    onClearConfig = {
                        viewModel.setUnlockBytesHex(null)
                        viewModel.setBleMac(null)
                    },
                )
            } else {
                PermissionRequestScreen(onRequest = { permissionLauncher.launch(requiredPermissions) })
            }
        }

        if (hasAllPermissions()) startIfPermitted() else permissionLauncher.launch(requiredPermissions)
    }

    override fun onResume() {
        super.onResume()
        // The user may have granted permissions from system settings while away.
        if (hasAllPermissions()) startIfPermitted()
    }

    private fun startIfPermitted() {
        if (!hasAllPermissions()) {
            permissionsGranted.value = false
            return
        }
        permissionsGranted.value = true
        val container = (application as FloatfaceApplication).container
        ContextCompat.startForegroundService(this, Intent(this, RideService::class.java))
        container.ensureStarted()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

@androidx.compose.runtime.Composable
private fun PermissionRequestScreen(onRequest: () -> Unit) {
    MaterialTheme {
        Scaffold {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Floatface needs Bluetooth, location and activity permissions to connect to your board.",
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRequest, modifier = Modifier.padding(top = 12.dp)) {
                    Text(text = "Grant")
                }
            }
        }
    }
}

private const val KEY_UNLOCK = "floatface_unlock_hex"
private const val KEY_MAC = "floatface_ble_mac"

/** Builds a Wear RemoteInput intent prompting for a single free-text value. */
private fun remoteInputIntent(key: String, label: String): Intent {
    val remoteInput = RemoteInput.Builder(key).setLabel(label).build()
    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
    return intent
}

/** Extracts the entered text for [key] from a RemoteInput activity result, or null. */
private fun remoteInputResult(data: Intent?, key: String): String? {
    val results = data?.let { RemoteInput.getResultsFromIntent(it) } ?: return null
    return results.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

/** Routes a [UserIntent] from the UI to the matching [OnewheelViewModel] method. */
private fun OnewheelViewModel.dispatch(intent: UserIntent) {
    when (intent) {
        UserIntent.ToggleRecording -> toggleRecording()
        UserIntent.NextPage -> nextPage()
        UserIntent.PrevPage -> prevPage()
        is UserIntent.SelectPage -> selectPage(intent.index)
        UserIntent.Shutdown -> shutdown()
        UserIntent.Retry -> retry()
    }
}
