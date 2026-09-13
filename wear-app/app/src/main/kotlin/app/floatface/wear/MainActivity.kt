package app.floatface.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * Launcher activity (SPEC §15). The real UI (paged Ride/Stats/Board/Diagnostics
 * screens observing OnewheelController.uiState) is built test-first by the swarm;
 * this placeholder only proves the Compose toolchain compiles.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlaceholderScreen() }
    }
}

@Composable
private fun PlaceholderScreen() {
    MaterialTheme {
        Text(text = "Floatface")
    }
}
