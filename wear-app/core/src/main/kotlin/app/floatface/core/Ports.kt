package app.floatface.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ports (SPEC §4.2 / §4.5.7). Implemented by adapters in :ble and :app; faked in
 * :core test fixtures. This is the ONLY seam between the pure domain and the
 * platform.
 */

/**
 * Transport over the Onewheel GATT connection. NOTE the read-only guarantee
 * (SPEC §1.2.1): the sole write method is [writeUnlock]; its adapter hard-codes
 * the target characteristic to UART_SERIAL_WRITE, so no path exists to write any
 * other characteristic.
 */
interface OnewheelTransport {
    val events: Flow<TransportEvent>
    fun startScan()
    fun stopScan()
    fun connect(deviceId: String)
    fun closeGatt()
    fun discoverServices()
    fun enableNotifications(char: OwCharacteristic)
    fun read(char: OwCharacteristic)
    fun writeUnlock(value: ByteArray)
    fun disconnect()
}

/** Monotonic + wall clock. [elapsedRealtimeMs] is Doze-inclusive and used for all elapsed-time math. */
interface Clock {
    fun elapsedRealtimeMs(): Long
    fun wallClockMs(): Long
}

interface Cancellable {
    fun cancel()
}

interface Ticker {
    fun schedule(intervalMs: Long, repeat: Boolean, action: suspend () -> Unit): Cancellable
}

interface Haptics {
    fun buzz(pattern: BuzzPattern)
}

/** Health Services-backed activity recording; persists via [RideLog] + GPS (SPEC §7). */
interface RideRecorder {
    val state: StateFlow<RecordingState>
    suspend fun start()
    suspend fun stop()
    fun onTelemetry(snapshot: TelemetrySnapshot, nowMs: Long)
}

/** Append-only per-ride telemetry log (JSONL) (SPEC §4a). */
interface RideLog {
    fun open(sessionId: String)
    fun append(sample: RideLogSample)
    fun close()
}

/**
 * Runtime board configuration store (SPEC §10): the per-owner unlock bytes and
 * an optional BLE MAC to target one specific board, editable on-watch and
 * persisted so neither is compiled in. [config] is the effective configuration
 * (a BuildConfig value may seed the unlock hex until the owner overrides it).
 */
interface BoardConfigStore {
    val config: StateFlow<BoardConfig>
    suspend fun setUnlockBytesHex(hex: String?)
    suspend fun setBleMac(mac: String?)
}

/** Persisted user preferences (SPEC §8.5). */
interface SettingsStore {
    val speedUnit: StateFlow<SpeedUnit>
    val tempUnit: StateFlow<TempUnit>
    suspend fun setSpeedUnit(unit: SpeedUnit)
    suspend fun setTempUnit(unit: TempUnit)
}

/** Logging abstraction keeping :core Android-free (SPEC §13a). Never log unlock bytes. */
interface Logger {
    fun d(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String, t: Throwable? = null)
}

/** The pure connection reducer (SPEC §4.5.6). No coroutines, no I/O — fully table-testable. */
interface ConnectionStateMachine {
    fun reduce(state: ConnectionState, event: Event): Reduction
}
