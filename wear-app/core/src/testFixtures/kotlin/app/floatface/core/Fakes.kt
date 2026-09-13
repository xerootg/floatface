package app.floatface.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Deterministic, pure-Kotlin fakes implementing the ports in Ports.kt, for use
 * across :core, :ble and :app tests. No JUnit/assertion imports here.
 */

/** Fake [OnewheelTransport] recording every call; events are driven manually via [emit]. */
class FakeTransport : OnewheelTransport {
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    /** Free-form log of every method invocation, in call order, e.g. "startScan", "connect(deviceId)". */
    val commands: MutableList<String> = mutableListOf()

    val startScanCalls: MutableList<Unit> = mutableListOf()
    val stopScanCalls: MutableList<Unit> = mutableListOf()
    val connected: MutableList<String> = mutableListOf()
    val closeGattCalls: MutableList<Unit> = mutableListOf()
    val discoverServicesCalls: MutableList<Unit> = mutableListOf()
    val enabled: MutableList<OwCharacteristic> = mutableListOf()
    val reads: MutableList<OwCharacteristic> = mutableListOf()
    val writes: MutableList<ByteArray> = mutableListOf()
    val disconnectCalls: MutableList<Unit> = mutableListOf()

    suspend fun emit(event: TransportEvent) {
        _events.emit(event)
    }

    override fun startScan() {
        commands += "startScan"
        startScanCalls += Unit
    }

    override fun stopScan() {
        commands += "stopScan"
        stopScanCalls += Unit
    }

    override fun connect(deviceId: String) {
        commands += "connect($deviceId)"
        connected += deviceId
    }

    override fun closeGatt() {
        commands += "closeGatt"
        closeGattCalls += Unit
    }

    override fun discoverServices() {
        commands += "discoverServices"
        discoverServicesCalls += Unit
    }

    override fun enableNotifications(char: OwCharacteristic) {
        commands += "enableNotifications($char)"
        enabled += char
    }

    override fun read(char: OwCharacteristic) {
        commands += "read($char)"
        reads += char
    }

    override fun writeUnlock(value: ByteArray) {
        commands += "writeUnlock(${value.size} bytes)"
        writes += value
    }

    override fun disconnect() {
        commands += "disconnect"
        disconnectCalls += Unit
    }
}

/** Fake [Ticker]: no real time — schedules are recorded and fired manually. */
class FakeTicker : Ticker {
    class ScheduledTask(
        val intervalMs: Long,
        val repeat: Boolean,
        val action: suspend () -> Unit,
    ) {
        var cancelled: Boolean = false
    }

    val scheduled: MutableList<ScheduledTask> = mutableListOf()

    override fun schedule(intervalMs: Long, repeat: Boolean, action: suspend () -> Unit): Cancellable {
        val task = ScheduledTask(intervalMs, repeat, action)
        scheduled += task
        return object : Cancellable {
            override fun cancel() {
                task.cancelled = true
            }
        }
    }

    /** Invokes every non-cancelled scheduled action's action, in schedule order. */
    suspend fun fireAll() {
        scheduled.filterNot { it.cancelled }.forEach { it.action() }
    }

    /** Invokes only the most recently scheduled non-cancelled task's action. */
    suspend fun fireLast() {
        scheduled.lastOrNull { !it.cancelled }?.action?.invoke()
    }
}

/** Fake [Clock] with directly settable elapsed/wall time. */
class FakeClock(var elapsed: Long = 0L, var wall: Long = 0L) : Clock {
    override fun elapsedRealtimeMs(): Long = elapsed
    override fun wallClockMs(): Long = wall
}

/** Fake [Haptics] recording every buzz pattern requested. */
class FakeHaptics : Haptics {
    val buzzes: MutableList<BuzzPattern> = mutableListOf()

    override fun buzz(pattern: BuzzPattern) {
        buzzes += pattern
    }
}

/** Fake [RideRecorder] with a settable [state] and recorded telemetry samples. */
class FakeRideRecorder : RideRecorder {
    private val _state = MutableStateFlow(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state

    val startCalls: MutableList<Unit> = mutableListOf()
    val stopCalls: MutableList<Unit> = mutableListOf()
    val telemetry: MutableList<Pair<TelemetrySnapshot, Long>> = mutableListOf()

    override suspend fun start() {
        startCalls += Unit
        _state.value = RecordingState.Recording
    }

    override suspend fun stop() {
        stopCalls += Unit
        _state.value = RecordingState.Idle
    }

    override fun onTelemetry(snapshot: TelemetrySnapshot, nowMs: Long) {
        telemetry += snapshot to nowMs
    }

    /** Test helper to force the recording state without going through start()/stop(). */
    fun setState(state: RecordingState) {
        _state.value = state
    }
}

/** Fake [RideLog] recording open/append/close calls. */
class FakeRideLog : RideLog {
    val opened: MutableList<String> = mutableListOf()
    val appended: MutableList<RideLogSample> = mutableListOf()
    val closeCalls: MutableList<Unit> = mutableListOf()

    override fun open(sessionId: String) {
        opened += sessionId
    }

    override fun append(sample: RideLogSample) {
        appended += sample
    }

    override fun close() {
        closeCalls += Unit
    }
}

/** Fake [BoardConfigStore] with a settable, in-memory [BoardConfig]. */
class FakeBoardConfigStore(initial: BoardConfig = BoardConfig.EMPTY) : BoardConfigStore {
    private val _config = MutableStateFlow(initial)
    override val config: StateFlow<BoardConfig> = _config

    override suspend fun setUnlockBytesHex(hex: String?) {
        _config.value = _config.value.copy(unlockBytesHex = hex)
    }

    override suspend fun setBleMac(mac: String?) {
        _config.value = _config.value.copy(bleMac = mac)
    }

    /** Test helper: 20 non-zero bytes as hex, so the reducer sees a configured board. */
    companion object {
        fun withUnlockBytes(bytes: ByteArray, mac: String? = null): FakeBoardConfigStore =
            FakeBoardConfigStore(BoardConfig(unlockBytesHex = bytes.joinToString("") { "%02x".format(it) }, bleMac = mac))
    }
}

/** Fake [SettingsStore] defaulting to MPH/F, settable via the mutable flows. */
class FakeSettingsStore(
    initialSpeedUnit: SpeedUnit = SpeedUnit.MPH,
    initialTempUnit: TempUnit = TempUnit.F,
) : SettingsStore {
    private val _speedUnit = MutableStateFlow(initialSpeedUnit)
    private val _tempUnit = MutableStateFlow(initialTempUnit)

    override val speedUnit: StateFlow<SpeedUnit> = _speedUnit
    override val tempUnit: StateFlow<TempUnit> = _tempUnit

    override suspend fun setSpeedUnit(unit: SpeedUnit) {
        _speedUnit.value = unit
    }

    override suspend fun setTempUnit(unit: TempUnit) {
        _tempUnit.value = unit
    }
}

/** Fake [Logger] swallowing all output. */
class FakeLogger : Logger {
    val debugs: MutableList<Pair<String, String>> = mutableListOf()
    val warns: MutableList<Pair<String, String>> = mutableListOf()
    val errors: MutableList<Triple<String, String, Throwable?>> = mutableListOf()

    override fun d(tag: String, msg: String) {
        debugs += tag to msg
    }

    override fun w(tag: String, msg: String) {
        warns += tag to msg
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        errors += Triple(tag, msg, t)
    }
}
