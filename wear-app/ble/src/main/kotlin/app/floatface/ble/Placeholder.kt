package app.floatface.ble

/**
 * Placeholder so the :ble module compiles before the swarm implements the
 * Android GATT adapter (SPEC §4.5.4 / §6.1): a single-outstanding-operation
 * queue over android.bluetooth translating callbacks to
 * [app.floatface.core.TransportEvent] and implementing
 * [app.floatface.core.OnewheelTransport] with writeUnlock() hard-coded to
 * UART_SERIAL_WRITE. Replaced test-first during implementation.
 */
internal const val BLE_MODULE = "app.floatface.ble"
