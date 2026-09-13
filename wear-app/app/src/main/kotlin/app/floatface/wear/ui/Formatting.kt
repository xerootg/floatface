package app.floatface.wear.ui

import androidx.compose.ui.graphics.Color
import app.floatface.core.BoardModel
import app.floatface.core.ConnectionState

/**
 * Pure formatting/logic helpers for the UI screens (SPEC §8.3, §8.6). Kept free
 * of Compose runtime state so they can be unit-tested directly.
 */

/** Battery color thresholds (SPEC §8.3): >=75 blue, >=50 green, >=25 yellow, else red; null grey. */
fun batteryColor(batteryLevel: Int?): Color = when {
    batteryLevel == null -> Color.Gray
    batteryLevel >= 75 -> Color(0xFF4C8DFF)
    batteryLevel >= 50 -> Color(0xFF4CD964)
    batteryLevel >= 25 -> Color(0xFFFFD60A)
    else -> Color(0xFFFF453A)
}

/** Riding mode text: named mode when known, else raw "Mode n"; "--" when unknown mode value. */
fun ridingModeText(board: BoardModel, mode: Int?): String {
    if (mode == null) return "--"
    return board.ridingModeNames[mode] ?: "Mode $mode"
}

/** Renders a nullable value via [format], or "--" when null. */
fun <T> valueOrDash(value: T?, format: (T) -> String = { it.toString() }): String =
    if (value == null) "--" else format(value)

/** Human-readable connection status line (SPEC §8.3). */
fun connectionStatusText(connection: ConnectionState): String = when (connection) {
    ConnectionState.Scanning -> "Scanning…"
    ConnectionState.ScanTimedOut -> "Board not found — is your phone's Bluetooth off?"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Discovering -> "Discovering…"
    is ConnectionState.Subscribing -> "Subscribing…"
    is ConnectionState.Unlocking -> "Unlocking…"
    ConnectionState.Connected -> "Connected"
    ConnectionState.Rescanning -> "Disconnected, rescanning…"
    ConnectionState.UnlockNotConfigured -> "Unlock bytes not configured"
    ConnectionState.ShuttingDown -> "Shutting down"
    ConnectionState.ServiceNotFound -> "Service not found"
    ConnectionState.WriteCharNotFound -> "uart_serial_write not found"
    is ConnectionState.Error -> connection.message
}
