package app.floatface.ble

import app.floatface.core.GattStatus
import app.floatface.core.OwCharacteristic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Abstraction over the actual `BluetoothGatt` calls the queue drives, so the
 * queue's one-outstanding-operation logic is unit-testable without a real
 * `BluetoothGatt` (SPEC §4.5.4 / §6.1). The production implementation
 * ([RealGattOps], in AndroidOnewheelTransport.kt) wraps a connected
 * `BluetoothGatt`; tests supply a fake.
 */
interface GattOps {
    fun discover()
    fun enableNotify(c: OwCharacteristic)
    fun read(c: OwCharacteristic)
    fun write(c: OwCharacteristic, bytes: ByteArray)
}

/** One GATT operation, either waiting in [GattOperationQueue] or currently running. */
sealed interface GattQueueOp {
    data object Discover : GattQueueOp
    data class EnableNotify(val char: OwCharacteristic) : GattQueueOp
    data class Read(val char: OwCharacteristic) : GattQueueOp

    /** Content-based equals/hashCode, matching the ByteArray-carrying core types (Events.kt). */
    class Write(val char: OwCharacteristic, val bytes: ByteArray) : GattQueueOp {
        override fun equals(other: Any?): Boolean =
            other is Write && char == other.char && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = char.hashCode() * 31 + bytes.contentHashCode()

        override fun toString(): String = "Write(char=$char, bytes.size=${bytes.size})"
    }
}

/**
 * Single-outstanding-GATT-operation queue (SPEC §4.5.4, §3.5, §6.1).
 *
 * Android permits only one in-flight GATT client operation per connection at
 * a time — issuing a second before the first's callback fires silently drops
 * or corrupts it. This queue serializes [GattQueueOp]s, dispatching them to
 * [ops] one at a time and starting the next only once the current one is
 * acknowledged via [completeCurrent] (called by the real
 * `BluetoothGattCallback` once its corresponding callback fires) — or once it
 * times out.
 *
 * The queue itself never inspects the completion [GattStatus] — it only cares
 * that *something* completed the outstanding op so it can advance. On a
 * per-op timeout (default [timeoutMs] = 8000ms) it reports the timed-out op
 * via [onTimeout] so the caller can translate it into an `OperationFailed`
 * transport event, then advances to the next op — a stuck op can never wedge
 * the queue.
 */
class GattOperationQueue(
    private val ops: GattOps,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 8000L,
    private val onTimeout: (GattQueueOp) -> Unit = {},
) {
    private val pending = ArrayDeque<GattQueueOp>()
    private var timeoutJob: Job? = null

    /** The op currently dispatched to [ops] and awaiting completion, or null if idle. */
    var currentOp: GattQueueOp? = null
        private set

    val isIdle: Boolean get() = currentOp == null

    /** Ops waiting behind [currentOp] (does not include it). */
    val pendingCount: Int get() = pending.size

    /** Adds [op] to the back of the queue; dispatches it immediately if the queue was idle. */
    fun enqueue(op: GattQueueOp) {
        pending.addLast(op)
        startNextIfIdle()
    }

    /**
     * Acknowledges the currently-running op (invoked from the real GATT
     * callback once it fires) and advances to the next queued op, if any.
     * A no-op if the queue is already idle (e.g. a stray/duplicate callback).
     */
    fun completeCurrent(status: GattStatus = GattStatus.SUCCESS) {
        if (currentOp == null) return
        timeoutJob?.cancel()
        timeoutJob = null
        currentOp = null
        startNextIfIdle()
    }

    private fun startNextIfIdle() {
        if (currentOp != null) return
        val next = pending.removeFirstOrNull() ?: return
        currentOp = next
        dispatch(next)
        timeoutJob = scope.launch {
            delay(timeoutMs)
            val timedOutOp = currentOp
            if (timedOutOp != null) {
                currentOp = null
                onTimeout(timedOutOp)
                startNextIfIdle()
            }
        }
    }

    private fun dispatch(op: GattQueueOp) {
        when (op) {
            is GattQueueOp.Discover -> ops.discover()
            is GattQueueOp.EnableNotify -> ops.enableNotify(op.char)
            is GattQueueOp.Read -> ops.read(op.char)
            is GattQueueOp.Write -> ops.write(op.char, op.bytes)
        }
    }
}
