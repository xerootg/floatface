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
 * that *something* completed the outstanding op so it can advance.
 *
 * **Timeout is treated as a dead link, not a skip.** An 8s GATT timeout on a
 * board whose ops normally complete in tens of ms means the connection is gone.
 * On [timeoutMs] the queue fails the op via [onTimeout], **abandons the rest of
 * the queue and goes idle** rather than issuing more ops onto a dead radio. This
 * closes the timeout/late-callback race: once idle, a late callback for the
 * timed-out op finds `currentOp == null` and [completeCurrent] no-ops, so it can
 * never complete the *wrong* (next) op. The transport surfaces the timeout as a
 * disconnect so the state machine tears down and rescans.
 *
 * All mutable state ([pending]/[currentOp]/[timeoutJob]/[generation]) is guarded
 * by [lock] because callbacks arrive on a binder thread while [enqueue] runs on
 * the controller's dispatcher. A monotonic [generation] makes the timeout
 * coroutine idempotent: it acts only if its op is still the current one.
 */
class GattOperationQueue(
    private val ops: GattOps,
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 8000L,
    private val onTimeout: (GattQueueOp) -> Unit = {},
) {
    private val lock = Any()
    private val pending = ArrayDeque<GattQueueOp>()
    private var timeoutJob: Job? = null
    private var generation = 0

    /** The op currently dispatched to [ops] and awaiting completion, or null if idle. */
    @Volatile
    var currentOp: GattQueueOp? = null
        private set

    val isIdle: Boolean get() = currentOp == null

    /** Ops waiting behind [currentOp] (does not include it). */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    /** Adds [op] to the back of the queue; dispatches it immediately if the queue was idle. */
    fun enqueue(op: GattQueueOp) {
        synchronized(lock) {
            pending.addLast(op)
            startNextIfIdleLocked()
        }
    }

    /**
     * Acknowledges the currently-running op (invoked from the real GATT
     * callback once it fires) and advances to the next queued op, if any.
     * A no-op if the queue is already idle (a stray/duplicate callback, or a
     * late callback for an op that already timed out).
     */
    fun completeCurrent(status: GattStatus = GattStatus.SUCCESS) {
        synchronized(lock) {
            if (currentOp == null) return
            timeoutJob?.cancel()
            timeoutJob = null
            generation++
            currentOp = null
            startNextIfIdleLocked()
        }
    }

    private fun startNextIfIdleLocked() {
        if (currentOp != null) return
        val next = pending.removeFirstOrNull() ?: return
        currentOp = next
        val gen = ++generation
        dispatch(next)
        timeoutJob = scope.launch {
            delay(timeoutMs)
            val timedOutOp = synchronized(lock) {
                // Superseded (completed, or another op already timed out): do nothing.
                if (generation != gen || currentOp == null) return@launch
                val op = currentOp
                currentOp = null
                timeoutJob = null
                pending.clear()
                generation++
                op
            }
            timedOutOp?.let { onTimeout(it) }
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
