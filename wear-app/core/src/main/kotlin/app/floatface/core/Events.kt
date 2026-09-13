package app.floatface.core

/**
 * Events emitted by the [OnewheelTransport] adapter into :core (SPEC §4.5.2).
 * Payloads are Android-free. Byte-carrying variants use content-based
 * equals/hashCode so tests and reducers compare by value.
 */
sealed interface TransportEvent {
    data class DeviceFound(
        val deviceId: String,
        val name: String?,
        val serviceUuids: List<String>,
    ) : TransportEvent

    data object Connected : TransportEvent

    data class ServicesDiscovered(
        val status: GattStatus,
        val available: Set<OwCharacteristic>,
    ) : TransportEvent

    data class NotificationsEnabled(
        val char: OwCharacteristic,
        val status: GattStatus,
    ) : TransportEvent

    class ReadComplete(
        val char: OwCharacteristic,
        val value: ByteArray,
        val status: GattStatus,
    ) : TransportEvent {
        override fun equals(other: Any?): Boolean =
            other is ReadComplete && char == other.char && status == other.status && value.contentEquals(other.value)

        override fun hashCode(): Int =
            (char.hashCode() * 31 + status.hashCode()) * 31 + value.contentHashCode()

        override fun toString(): String = "ReadComplete(char=$char, value=${value.toHexString()}, status=$status)"
    }

    data class WriteComplete(
        val char: OwCharacteristic,
        val status: GattStatus,
    ) : TransportEvent

    /** A notification (indicate/notify) delivery — distinct from a read reply. */
    class CharacteristicChanged(
        val char: OwCharacteristic,
        val value: ByteArray,
    ) : TransportEvent {
        override fun equals(other: Any?): Boolean =
            other is CharacteristicChanged && char == other.char && value.contentEquals(other.value)

        override fun hashCode(): Int = char.hashCode() * 31 + value.contentHashCode()

        override fun toString(): String = "CharacteristicChanged(char=$char, value=${value.toHexString()})"
    }

    data class MtuChanged(val mtu: Int, val status: GattStatus) : TransportEvent

    data class Disconnected(val status: GattStatus) : TransportEvent

    data object ScanStopped : TransportEvent

    data class ScanFailed(val reason: String) : TransportEvent

    data class OperationFailed(val op: GattOp, val status: GattStatus) : TransportEvent
}

/** Intents originating from the user / UI (SPEC §4.5.3). */
sealed interface UserIntent {
    data object ToggleRecording : UserIntent
    data object NextPage : UserIntent
    data object PrevPage : UserIntent
    data class SelectPage(val index: Int) : UserIntent
    data object Shutdown : UserIntent
    data object Retry : UserIntent
}

/** The merged input alphabet consumed by the effect-runner (SPEC §4.5.3). */
sealed interface Event {
    data class Transport(val event: TransportEvent) : Event
    data object KeepaliveTick : Event
    data object ScanTimeout : Event
    data object LivenessTimeout : Event
    data class User(val intent: UserIntent) : Event
    data class Recorder(val state: RecordingState) : Event
}

/** Side-effect commands returned by the pure reducer (SPEC §4.5.4). */
sealed interface Command {
    data object StartScan : Command
    data object StopScan : Command
    data class Connect(val deviceId: String) : Command
    data object CloseGatt : Command
    data class EnableNotifications(val char: OwCharacteristic) : Command
    data class Read(val char: OwCharacteristic) : Command
    data class IssueInitialReads(val chars: List<OwCharacteristic>) : Command

    class WriteUnlock(val bytes: ByteArray) : Command {
        override fun equals(other: Any?): Boolean = other is WriteUnlock && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "WriteUnlock(bytes=${bytes.toHexString()})"
    }

    data class StartKeepalive(val intervalMs: Long) : Command
    data object StopKeepalive : Command
    data class Buzz(val pattern: BuzzPattern) : Command
    data object Disconnect : Command
    data object StartRecording : Command
    data object StopRecording : Command
}

/** Connection lifecycle states (SPEC §4.5.5). */
sealed interface ConnectionState {
    data object Scanning : ConnectionState
    data object ScanTimedOut : ConnectionState
    data object Connecting : ConnectionState
    data object Discovering : ConnectionState
    data object Subscribing : ConnectionState
    data object Unlocking : ConnectionState
    data object Connected : ConnectionState
    data object Rescanning : ConnectionState
    data object UnlockNotConfigured : ConnectionState
    data object ShuttingDown : ConnectionState
    data object ServiceNotFound : ConnectionState
    data object WriteCharNotFound : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/** The result of a pure reduce step: the next state plus commands to run (SPEC §4.5.6). */
data class Reduction(
    val state: ConnectionState,
    val commands: List<Command> = emptyList(),
)

internal fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
