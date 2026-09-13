package app.floatface.core

/**
 * The pure connection reducer (SPEC §4.5.6, §6.3) — the crown jewel.
 *
 * `reduce` is a pure function: no coroutines, no I/O, deterministic. All side
 * effects are expressed as returned [Command]s for the effect-runner
 * ([OnewheelController]) to execute.
 *
 * [unlockBytes] is treated as "configured" only when non-null AND
 * [UnlockBytes.isConfigured] returns true for it (20 bytes, not all-zero).
 */
class DefaultConnectionStateMachine(
    private val unlockBytesProvider: () -> ByteArray?,
    private val targetMacProvider: () -> String? = { null },
    private val keepaliveIntervalMs: Long = 12_000L,
) : ConnectionStateMachine {

    /** Back-compat / fixed-config convenience (tests, simple wiring). */
    constructor(unlockBytes: ByteArray?, keepaliveIntervalMs: Long = 12_000L) :
        this({ unlockBytes }, { null }, keepaliveIntervalMs)

    /** Current effective unlock bytes, or null when not configured (SPEC §10). */
    private fun unlockBytesOrNull(): ByteArray? = unlockBytesProvider()?.takeIf { UnlockBytes.isConfigured(it) }

    /** Keepalive/liveness re-unlock; holds (no write) if config was cleared mid-ride. */
    private fun writeUnlockOrHold(state: ConnectionState): Reduction {
        val bytes = unlockBytesOrNull() ?: return Reduction(state)
        return Reduction(state, listOf(Command.WriteUnlock(bytes)))
    }

    override fun reduce(state: ConnectionState, event: Event): Reduction {
        // Global overrides, checked before any state-specific dispatch:

        // ShuttingDown is a true terminal sink: nothing (not even Disconnected)
        // moves it anywhere else, and it never re-emits commands.
        if (state is ConnectionState.ShuttingDown) {
            return Reduction(state)
        }

        // Shutdown wins from any other NON-TERMINAL state: transition to
        // ShuttingDown BEFORE emitting Disconnect/CloseGatt so the resulting
        // Disconnected event lands in ShuttingDown (a no-op, no rescan). The
        // error/terminal states (ServiceNotFound, WriteCharNotFound,
        // UnlockNotConfigured, Error) only react to User.Retry — they are
        // already at rest with the GATT closed, so Shutdown falls through to
        // the default no-op below for them.
        if (event is Event.User && event.intent is UserIntent.Shutdown && !isTerminalError(state)) {
            return Reduction(
                ConnectionState.ShuttingDown,
                listOf(Command.StopScan, Command.StopKeepalive, Command.Disconnect, Command.CloseGatt),
            )
        }

        return when (state) {
            is ConnectionState.Scanning -> onScanningLike(state, event)
            is ConnectionState.ScanTimedOut -> onScanningLike(state, event)
            is ConnectionState.Rescanning -> onScanningLike(state, event)
            is ConnectionState.Connecting -> onConnecting(state, event)
            is ConnectionState.Discovering -> onDiscovering(state, event)
            is ConnectionState.Subscribing -> onSubscribing(state, event)
            is ConnectionState.Unlocking -> onUnlocking(state, event)
            is ConnectionState.Connected -> onConnected(state, event)
            is ConnectionState.ServiceNotFound -> onTerminalError(state, event)
            is ConnectionState.WriteCharNotFound -> onTerminalError(state, event)
            is ConnectionState.UnlockNotConfigured -> onTerminalError(state, event)
            is ConnectionState.Error -> onTerminalError(state, event)
            is ConnectionState.ShuttingDown -> Reduction(state) // unreachable (handled above)
        }
    }

    // Scanning / ScanTimedOut / Rescanning all share: DeviceFound(match) -> Connecting,
    // and (Scanning/Rescanning only, per spec) ScanTimeout -> ScanTimedOut.
    private fun onScanningLike(state: ConnectionState, event: Event): Reduction {
        if (event is Event.Transport) {
            val te = event.event
            if (te is TransportEvent.DeviceFound && matches(te.deviceId, te.name, te.serviceUuids)) {
                return Reduction(ConnectionState.Connecting, listOf(Command.StopScan, Command.Connect(te.deviceId)))
            }
            return Reduction(state)
        }
        if (event is Event.ScanTimeout) {
            return Reduction(ConnectionState.ScanTimedOut)
        }
        return Reduction(state)
    }

    private fun onConnecting(state: ConnectionState.Connecting, event: Event): Reduction {
        if (event !is Event.Transport) return Reduction(state)
        return when (val te = event.event) {
            is TransportEvent.Connected -> Reduction(ConnectionState.Discovering, listOf(Command.DiscoverServices))
            is TransportEvent.Disconnected -> rescan()
            is TransportEvent.OperationFailed ->
                if (te.op == GattOp.CONNECT) rescan() else Reduction(state)
            else -> Reduction(state)
        }
    }

    private fun onDiscovering(state: ConnectionState.Discovering, event: Event): Reduction {
        if (event !is Event.Transport) return Reduction(state)
        val te = event.event
        if (te !is TransportEvent.ServicesDiscovered) return Reduction(state)

        if (!te.status.isSuccess) {
            return Reduction(ConnectionState.ServiceNotFound, listOf(Command.CloseGatt))
        }
        if (OwCharacteristic.UART_SERIAL_WRITE !in te.available) {
            return Reduction(ConnectionState.WriteCharNotFound, listOf(Command.CloseGatt))
        }
        val bytes = unlockBytesOrNull()
        if (bytes == null) {
            return Reduction(ConnectionState.UnlockNotConfigured, listOf(Command.CloseGatt))
        }
        val notify = OwCharacteristic.NOTIFY_SET.filter { it in te.available }
        return if (notify.isEmpty()) {
            Reduction(ConnectionState.Unlocking(0), listOf(Command.WriteUnlock(bytes)))
        } else {
            Reduction(
                ConnectionState.Subscribing(notify),
                listOf(Command.Read(OwCharacteristic.FIRMWARE_REVISION), Command.EnableNotifications(notify.first())),
            )
        }
    }

    private fun onSubscribing(state: ConnectionState.Subscribing, event: Event): Reduction {
        if (event !is Event.Transport) return Reduction(state)
        return when (val te = event.event) {
            is TransportEvent.NotificationsEnabled -> {
                val remaining = state.remaining
                if (remaining.isEmpty() || te.char != remaining.first()) {
                    // Ignore duplicate/out-of-order acks.
                    return Reduction(state)
                }
                val rest = remaining.drop(1)
                if (rest.isEmpty()) {
                    val bytes = unlockBytesOrNull()
                        ?: return Reduction(ConnectionState.UnlockNotConfigured, listOf(Command.CloseGatt))
                    Reduction(ConnectionState.Unlocking(0), listOf(Command.WriteUnlock(bytes)))
                } else {
                    Reduction(ConnectionState.Subscribing(rest), listOf(Command.EnableNotifications(rest.first())))
                }
            }
            is TransportEvent.ReadComplete -> Reduction(state) // controller folds FIRMWARE_REVISION into BoardModel
            is TransportEvent.Disconnected -> rescan()
            else -> Reduction(state)
        }
    }

    private fun onUnlocking(state: ConnectionState.Unlocking, event: Event): Reduction {
        if (event !is Event.Transport) return Reduction(state)
        return when (val te = event.event) {
            is TransportEvent.WriteComplete ->
                if (te.char != OwCharacteristic.UART_SERIAL_WRITE) {
                    Reduction(state)
                } else if (te.status.isSuccess) {
                    Reduction(
                        ConnectionState.Connected,
                        listOf(
                            Command.StartKeepalive(keepaliveIntervalMs),
                            Command.IssueInitialReads(OwCharacteristic.INITIAL_READS),
                        ),
                    )
                } else {
                    val retryBytes = unlockBytesOrNull()
                    if (state.attempt < 1 && retryBytes != null) {
                        Reduction(ConnectionState.Unlocking(state.attempt + 1), listOf(Command.WriteUnlock(retryBytes)))
                    } else {
                        Reduction(ConnectionState.Error("Unlock failed"), listOf(Command.CloseGatt))
                    }
                }
            is TransportEvent.Disconnected -> rescan()
            else -> Reduction(state)
        }
    }

    private fun onConnected(state: ConnectionState.Connected, event: Event): Reduction = when (event) {
        is Event.KeepaliveTick -> writeUnlockOrHold(state)
        is Event.LivenessTimeout -> writeUnlockOrHold(state)
        is Event.Transport ->
            if (event.event is TransportEvent.Disconnected) rescan() else Reduction(state)
        else -> Reduction(state)
    }

    private fun onTerminalError(state: ConnectionState, event: Event): Reduction {
        if (event is Event.User && event.intent is UserIntent.Retry) {
            return Reduction(ConnectionState.Scanning, listOf(Command.CloseGatt, Command.StartScan))
        }
        return Reduction(state)
    }

    // StopKeepalive first: a drop-to-rescan must tear down the keepalive ticker
    // (the reducer is the single source of truth for its lifecycle), otherwise it
    // keeps firing every interval against a dead link. CloseGatt then StartScan.
    private fun rescan(): Reduction =
        Reduction(ConnectionState.Rescanning, listOf(Command.StopKeepalive, Command.CloseGatt, Command.StartScan))

    private fun isTerminalError(state: ConnectionState): Boolean = when (state) {
        is ConnectionState.ServiceNotFound,
        is ConnectionState.WriteCharNotFound,
        is ConnectionState.UnlockNotConfigured,
        is ConnectionState.Error -> true
        else -> false
    }

    private fun matches(deviceId: String, name: String?, uuids: List<String>): Boolean {
        val targetMac = targetMacProvider()?.trim()?.takeIf { it.isNotEmpty() }
        if (targetMac != null) {
            // A configured MAC narrows matching to exactly that board (SPEC §10).
            return deviceId.equals(targetMac, ignoreCase = true)
        }
        val nameMatch = name != null && name.lowercase().startsWith("ow")
        val uuidMatch = uuids.any { it.equals(OwCharacteristic.SERVICE_UUID, ignoreCase = true) }
        return nameMatch || uuidMatch
    }
}
