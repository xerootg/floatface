package app.floatface.wear.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.floatface.core.BoardModel
import app.floatface.core.ConnectionState
import app.floatface.core.RecordingState
import app.floatface.core.RideState
import app.floatface.core.TelemetrySnapshot
import app.floatface.core.UiState
import app.floatface.core.UserIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreensRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val connectedTelemetry = TelemetrySnapshot(
        batteryLevel = 80,
        speedMph = 12.3,
        ridingMode = 4,
        motorTempAF = 90,
        motorTempBF = 91,
        batteryTempAF = 70,
        batteryTempBF = 71,
        safetyHeadroom = 5,
        lifeOdometer = 1234,
        tripAmpHours = 3,
        tripRegenAmpHours = 1,
        status = 0,
        firmwareRevision = 6001,
    )

    @Test
    fun `connected ride page shows numeric battery percent`() {
        val state = UiState(
            connection = ConnectionState.Connected,
            telemetry = connectedTelemetry,
            ride = RideState(recording = RecordingState.Idle),
            board = BoardModel.GT,
            page = 0,
        )

        composeRule.setContent { FloatfaceApp(state = state, onIntent = {}) }

        composeRule.onNodeWithText("80%", substring = true).assertExists()
    }

    @Test
    fun `connected board page shows a riding mode name`() {
        val state = UiState(
            connection = ConnectionState.Connected,
            telemetry = connectedTelemetry,
            ride = RideState(),
            board = BoardModel.GT,
            page = 2,
        )

        composeRule.setContent { FloatfaceApp(state = state, onIntent = {}) }

        composeRule.onNodeWithText("Roam", substring = true).assertExists()
    }

    @Test
    fun `scanning state shows scanning status in header`() {
        val state = UiState(connection = ConnectionState.Scanning, page = 0)

        composeRule.setContent { FloatfaceApp(state = state, onIntent = {}) }

        composeRule.onNodeWithText("Scanning", substring = true).assertExists()
    }

    @Test
    fun `scan timed out state shows the board-not-found hint`() {
        val state = UiState(connection = ConnectionState.ScanTimedOut, page = 0)

        composeRule.setContent { FloatfaceApp(state = state, onIntent = {}) }

        composeRule.onNodeWithText("Board not found", substring = true).assertExists()
    }

    @Test
    fun `toggle recording intent fires from the ride screen button`() {
        var received: UserIntent? = null
        val state = UiState(connection = ConnectionState.Connected, telemetry = connectedTelemetry, page = 0)

        composeRule.setContent {
            FloatfaceApp(state = state, onIntent = { received = it })
        }

        composeRule.onNodeWithText("Start").performClick()

        composeRule.runOnIdle {
            assert(received == UserIntent.ToggleRecording) { "expected ToggleRecording, got $received" }
        }
    }
}
