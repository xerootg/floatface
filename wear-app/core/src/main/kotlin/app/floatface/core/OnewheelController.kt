package app.floatface.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The effect-runner (SPEC §4.4, §4.5.6). Merges transport events, ticker ticks,
 * user intents and recorder-state changes into ONE [Channel], consumed by a
 * single coroutine on [scope] so the whole domain is single-threaded by
 * construction. Delegates connection-lifecycle decisions to the pure [machine]
 * and applies its [Command]s against the ports; controller-side effects
 * (telemetry decode, derived metrics, paging, recording) are applied after.
 */
class OnewheelController(
    private val transport: OnewheelTransport,
    private val ticker: Ticker,
    private val clock: Clock,
    private val haptics: Haptics,
    private val recorder: RideRecorder,
    unlockConfig: UnlockConfig,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val machine: ConnectionStateMachine = DefaultConnectionStateMachine(unlockConfig.unlockBytes()),
) {
    private companion object {
        const val TAG = "OnewheelController"
        const val SCAN_TIMEOUT_MS = 30_000L
        const val LIVENESS_TIMEOUT_MS = 5_000L
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    /** Single merged input; unlimited so producer coroutines never suspend/drop. */
    private val channel = Channel<Event>(Channel.UNLIMITED)

    private var board: BoardModel = BoardModel.GT
    private var startBattery: Int? = null
    private val distance = DistanceIntegrator()
    private val halfway = HalfwayWarning()

    private var keepaliveCancellable: Cancellable? = null
    private var livenessCancellable: Cancellable? = null
    private var scanTimeoutCancellable: Cancellable? = null

    /** Begins the merged-event loop, starts scanning, and arms the scan timeout. */
    fun start() {
        transport.events
            .onEach { channel.trySend(Event.Transport(it)) }
            .launchIn(scope)

        recorder.state
            .onEach { channel.trySend(Event.Recorder(it)) }
            .launchIn(scope)

        scope.launch {
            for (event in channel) {
                process(event)
            }
        }

        _uiState.update { it.copy(connection = ConnectionState.Scanning) }
        transport.startScan()
        armScanTimeout()
    }

    // Armed on every entry into a scanning state (initial start AND each rescan/
    // retry via Command.StartScan) so Event.ScanTimeout is reachable for all of
    // them, not just the first 30s window. Cancelled once we leave scanning.
    private fun armScanTimeout() {
        scanTimeoutCancellable?.cancel()
        scanTimeoutCancellable = ticker.schedule(SCAN_TIMEOUT_MS, false) { channel.trySend(Event.ScanTimeout) }
    }

    private fun cancelScanTimeout() {
        scanTimeoutCancellable?.cancel()
        scanTimeoutCancellable = null
    }

    /** Enqueues a [UserIntent] onto the single merged input. */
    fun submit(intent: UserIntent) {
        channel.trySend(Event.User(intent))
    }

    private suspend fun process(event: Event) {
        val prevConnection = _uiState.value.connection
        val reduction = machine.reduce(prevConnection, event)
        _uiState.update { it.copy(connection = reduction.state) }
        reduction.commands.forEach { runCommand(it) }

        if (prevConnection != ConnectionState.Connected && reduction.state == ConnectionState.Connected) {
            distance.clearTimestamp()
        }

        applyControllerEffects(event)
    }

    private fun runCommand(command: Command) {
        when (command) {
            Command.StartScan -> { transport.startScan(); armScanTimeout() }
            Command.StopScan -> { transport.stopScan(); cancelScanTimeout() }
            is Command.Connect -> { transport.connect(command.deviceId); cancelScanTimeout() }
            Command.DiscoverServices -> transport.discoverServices()
            Command.CloseGatt -> transport.closeGatt()
            is Command.EnableNotifications -> transport.enableNotifications(command.char)
            is Command.Read -> transport.read(command.char)
            is Command.IssueInitialReads -> command.chars.forEach { transport.read(it) }
            is Command.WriteUnlock -> transport.writeUnlock(command.bytes)
            is Command.StartKeepalive -> {
                keepaliveCancellable?.cancel()
                keepaliveCancellable = ticker.schedule(command.intervalMs, true) {
                    channel.trySend(Event.KeepaliveTick)
                }
            }
            Command.StopKeepalive -> {
                keepaliveCancellable?.cancel()
                keepaliveCancellable = null
            }
            Command.Disconnect -> transport.disconnect()
        }
    }

    private suspend fun applyControllerEffects(event: Event) {
        when (event) {
            is Event.Transport -> applyTransportEffect(event.event)
            is Event.User -> applyUserEffect(event.intent)
            is Event.Recorder -> _uiState.update { it.copy(ride = it.ride.copy(recording = event.state)) }
            Event.KeepaliveTick, Event.ScanTimeout, Event.LivenessTimeout -> Unit
        }
    }

    private fun applyTransportEffect(te: TransportEvent) {
        when (te) {
            is TransportEvent.CharacteristicChanged -> onTelemetry(te.char, te.value)
            is TransportEvent.ReadComplete -> if (te.status.isSuccess) onTelemetry(te.char, te.value)
            is TransportEvent.Disconnected -> {
                // Link state resets; ride state (distance/range/halfway) is kept.
                livenessCancellable?.cancel()
                livenessCancellable = null
                _uiState.update { it.copy(telemetry = TelemetrySnapshot.EMPTY) }
                distance.clearTimestamp()
            }
            else -> Unit
        }
    }

    private fun onTelemetry(char: OwCharacteristic, value: ByteArray) {
        val current = _uiState.value
        val telemetry = TelemetryDecoder.decodeInto(current.telemetry, char, value, board)
        var ride = current.ride

        if (char == OwCharacteristic.FIRMWARE_REVISION) {
            telemetry.firmwareRevision?.let { rev ->
                board = BoardModel.forGeneration(TelemetryDecoder.generationOf(rev))
            }
        }

        if (char == OwCharacteristic.BATTERY_LEVEL) {
            val fired = halfway.update(telemetry.batteryLevel, startBattery)
            if (fired) {
                haptics.buzz(BuzzPattern.HalfwayWarning)
            }
            ride = ride.copy(
                halfwayWarningActive = halfway.active,
                estimatedRangeMiles = RangeEstimator.estimate(startBattery, telemetry.batteryLevel, ride.distanceMiles),
            )
        }

        if (char == OwCharacteristic.SPEED_RPM && ride.recording == RecordingState.Recording) {
            telemetry.speedMph?.let { mph -> distance.onSpeed(mph, clock.elapsedRealtimeMs()) }
            ride = ride.copy(
                distanceMiles = distance.miles,
                estimatedRangeMiles = RangeEstimator.estimate(startBattery, telemetry.batteryLevel, distance.miles),
            )
        }

        _uiState.update { it.copy(telemetry = telemetry, ride = ride, board = board) }

        livenessCancellable?.cancel()
        livenessCancellable = ticker.schedule(LIVENESS_TIMEOUT_MS, false) { channel.trySend(Event.LivenessTimeout) }
    }

    private suspend fun applyUserEffect(intent: UserIntent) {
        when (intent) {
            UserIntent.NextPage -> _uiState.update { it.copy(page = (it.page + 1) % 4) }
            UserIntent.PrevPage -> _uiState.update { it.copy(page = (it.page + 3) % 4) }
            is UserIntent.SelectPage -> _uiState.update { it.copy(page = intent.index.coerceIn(0, 3)) }
            UserIntent.ToggleRecording -> {
                val current = _uiState.value
                if (current.ride.recording == RecordingState.Idle) {
                    recorder.start()
                    startBattery = current.telemetry.batteryLevel
                    distance.reset()
                    halfway.reset()
                    _uiState.update {
                        it.copy(
                            ride = RideState(
                                recording = RecordingState.Recording,
                                distanceMiles = 0.0,
                                estimatedRangeMiles = null,
                                halfwayWarningActive = false,
                            ),
                        )
                    }
                } else {
                    recorder.stop()
                    _uiState.update { it.copy(ride = it.ride.copy(recording = RecordingState.Idle)) }
                }
            }
            UserIntent.Shutdown, UserIntent.Retry -> {
                logger.d(TAG, "intent=$intent")
            }
        }
    }
}
