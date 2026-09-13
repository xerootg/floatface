package app.floatface.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import app.floatface.core.ConnectionState
import app.floatface.core.RecordingState
import app.floatface.core.UiState
import app.floatface.core.UserIntent

/**
 * The four pages (SPEC §8.1). Pure composables: all state comes in via [UiState],
 * all user actions go out via [onIntent].
 */

@Composable
fun RideScreen(state: UiState, onIntent: (UserIntent) -> Unit) {
    val telemetry = state.telemetry
    val recording = state.ride.recording == RecordingState.Recording

    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val speedText = valueOrDash(telemetry.speedMph) { "%.1f".format(it) }
            Text(text = speedText, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(text = "mph (est.)", fontSize = 12.sp)

            Text(
                text = "${valueOrDash(telemetry.batteryLevel) { "$it%" }}",
                color = batteryColor(telemetry.batteryLevel),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )

            Text(text = if (recording) "● Recording" else "Start/Stop to record", fontSize = 12.sp)

            if (state.ride.halfwayWarningActive) {
                Text(text = "Past halfway — head back", fontSize = 12.sp, color = MaterialTheme.colors.error)
            }

            if (state.connection == ConnectionState.ScanTimedOut) {
                Text(text = connectionStatusText(state.connection), fontSize = 11.sp)
            }
        }

        Button(onClick = { onIntent(UserIntent.ToggleRecording) }, modifier = Modifier.fillMaxWidth()) {
            Text(text = if (recording) "Stop" else "Start")
        }
    }
}

@Composable
fun StatsScreen(state: UiState, onIntent: (UserIntent) -> Unit) {
    ScalingLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        item {
            Text(text = "Trip (est.)")
            Text(text = "${"%.2f".format(state.ride.distanceMiles)} mi", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Text(text = "Range left (est.)")
            Text(
                text = valueOrDash(state.ride.estimatedRangeMiles) { "%.1f mi".format(it) },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (state.ride.halfwayWarningActive) {
            item { Text(text = "Past halfway — head back", fontSize = 12.sp) }
        }
    }
}

@Composable
fun BoardScreen(state: UiState, onIntent: (UserIntent) -> Unit) {
    val telemetry = state.telemetry
    ScalingLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        item { Text(text = ridingModeText(state.board, telemetry.ridingMode), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        item { Text(text = "Motor temp A: ${valueOrDash(telemetry.motorTempAF) { "$it°F" }}") }
        item { Text(text = "Motor temp B: ${valueOrDash(telemetry.motorTempBF) { "$it°F" }}") }
        item { Text(text = "Battery temp A: ${valueOrDash(telemetry.batteryTempAF) { "$it°F" }}") }
        item { Text(text = "Battery temp B: ${valueOrDash(telemetry.batteryTempBF) { "$it°F" }}") }
        item { Text(text = "Life odometer: ${valueOrDash(telemetry.lifeOdometer)}") }
        item { Text(text = "s_h: ${valueOrDash(telemetry.safetyHeadroom)}") }
    }
}

@Composable
fun DiagnosticsScreen(state: UiState, onIntent: (UserIntent) -> Unit) {
    val telemetry = state.telemetry
    ScalingLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        item { Text(text = "Trip Ah: ${valueOrDash(telemetry.tripAmpHours)}") }
        item { Text(text = "Regen Ah: ${valueOrDash(telemetry.tripRegenAmpHours)}") }
        item { Text(text = "Status: ${valueOrDash(telemetry.status)}") }
        item { Text(text = "Firmware: ${valueOrDash(telemetry.firmwareRevision)}") }
        item { Text(text = "Gen: ${state.board.generation}${if (!state.board.confirmed) " (unconfirmed)" else ""}") }
        item { Text(text = "Connection: ${connectionStatusText(state.connection)}") }
    }
}
