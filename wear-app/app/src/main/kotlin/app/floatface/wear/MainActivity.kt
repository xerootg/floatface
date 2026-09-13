package app.floatface.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.floatface.core.UserIntent
import app.floatface.wear.service.RideService
import app.floatface.wear.ui.FloatfaceApp
import app.floatface.wear.ui.OnewheelViewModel

/**
 * Launcher activity (SPEC §8/§13). Reads the process-wide [AppContainer] off
 * [FloatfaceApplication], starts the foreground [RideService] (which owns the
 * actual BLE/keepalive/recording lifecycle independent of this Activity, per
 * SPEC §9), and renders [FloatfaceApp] driven by
 * [app.floatface.core.OnewheelController.uiState].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as FloatfaceApplication).container
        ContextCompat.startForegroundService(this, Intent(this, RideService::class.java))
        container.ensureStarted()

        setContent {
            val viewModel: OnewheelViewModel = viewModel(factory = OnewheelViewModel.Factory(container.controller))
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            FloatfaceApp(state = uiState, onIntent = viewModel::dispatch)
        }
    }
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
