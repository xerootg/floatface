package app.floatface.ble

import app.floatface.core.GattStatus
import app.floatface.core.OwCharacteristic
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every call made through [GattOps], in order, for assertion. */
private class FakeGattOps : GattOps {
    val calls = mutableListOf<String>()
    override fun discover() { calls += "discover" }
    override fun enableNotify(c: OwCharacteristic) { calls += "enableNotify:${c.name}" }
    override fun read(c: OwCharacteristic) { calls += "read:${c.name}" }
    override fun write(c: OwCharacteristic, bytes: ByteArray) { calls += "write:${c.name}:${bytes.size}" }
}

/**
 * SPEC §4.5.4 / §3.5 / §6.1: single-outstanding-GATT-op queue. One op runs at
 * a time; the next starts only after [GattOperationQueue.completeCurrent];
 * a stuck op times out (failing it) and the queue still advances; ordering
 * is FIFO regardless of when ops are enqueued.
 */
class GattOperationQueueTest {

    @Test
    fun `dispatches the first op immediately and holds the rest`() = runTest {
        val fake = FakeGattOps()
        val queue = GattOperationQueue(fake, this)

        queue.enqueue(GattQueueOp.Discover)
        queue.enqueue(GattQueueOp.EnableNotify(OwCharacteristic.BATTERY_LEVEL))
        queue.enqueue(GattQueueOp.Read(OwCharacteristic.RIDING_MODE))

        assertEquals(listOf("discover"), fake.calls)
        assertEquals(GattQueueOp.Discover, queue.currentOp)
        assertEquals(2, queue.pendingCount)
    }

    @Test
    fun `advances to the next op only after completeCurrent`() = runTest {
        val fake = FakeGattOps()
        val queue = GattOperationQueue(fake, this)

        queue.enqueue(GattQueueOp.Discover)
        queue.enqueue(GattQueueOp.EnableNotify(OwCharacteristic.BATTERY_LEVEL))
        queue.enqueue(GattQueueOp.EnableNotify(OwCharacteristic.SPEED_RPM))

        // Only the first op has run; enqueueing more never starts them early.
        assertEquals(listOf("discover"), fake.calls)

        queue.completeCurrent(GattStatus.SUCCESS)
        assertEquals(listOf("discover", "enableNotify:BATTERY_LEVEL"), fake.calls)
        assertEquals(1, queue.pendingCount)

        queue.completeCurrent(GattStatus.SUCCESS)
        assertEquals(listOf("discover", "enableNotify:BATTERY_LEVEL", "enableNotify:SPEED_RPM"), fake.calls)
        assertEquals(0, queue.pendingCount)
        assertNotNull(queue.currentOp)

        queue.completeCurrent(GattStatus.SUCCESS)
        assertNull(queue.currentOp)
        assertTrue(queue.isIdle)

        // A stray completion on an already-idle queue is a harmless no-op.
        queue.completeCurrent(GattStatus.SUCCESS)
        assertEquals(3, fake.calls.size)
    }

    @Test
    fun `preserves FIFO ordering even when ops are enqueued mid-flight`() = runTest {
        val fake = FakeGattOps()
        val queue = GattOperationQueue(fake, this)

        queue.enqueue(GattQueueOp.EnableNotify(OwCharacteristic.BATTERY_LEVEL))
        queue.enqueue(GattQueueOp.EnableNotify(OwCharacteristic.SPEED_RPM))
        queue.completeCurrent(GattStatus.SUCCESS)
        // Enqueue a third op while the second is already running.
        queue.enqueue(GattQueueOp.Read(OwCharacteristic.SAFETY_HEADROOM))
        queue.completeCurrent(GattStatus.SUCCESS)
        queue.completeCurrent(GattStatus.SUCCESS)

        assertEquals(
            listOf(
                "enableNotify:BATTERY_LEVEL",
                "enableNotify:SPEED_RPM",
                "read:SAFETY_HEADROOM",
            ),
            fake.calls,
        )
    }

    @Test
    fun `per-op timeout fails the op and abandons the queue (dead link)`() = runTest {
        val fake = FakeGattOps()
        val timedOut = mutableListOf<GattQueueOp>()
        val queue = GattOperationQueue(fake, this, timeoutMs = 8000L, onTimeout = { timedOut += it })

        queue.enqueue(GattQueueOp.Read(OwCharacteristic.BATTERY_LEVEL))
        queue.enqueue(GattQueueOp.Read(OwCharacteristic.RIDING_MODE))

        advanceTimeBy(8000L)
        runCurrent()

        assertEquals(listOf(GattQueueOp.Read(OwCharacteristic.BATTERY_LEVEL)), timedOut)
        // The queued second op is NOT dispatched onto a presumed-dead link.
        assertEquals(listOf("read:BATTERY_LEVEL"), fake.calls)
        assertNull(queue.currentOp)
        assertTrue(queue.isIdle)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `a late callback after a timeout is a safe no-op (never completes the wrong op)`() = runTest {
        val fake = FakeGattOps()
        val timedOut = mutableListOf<GattQueueOp>()
        val queue = GattOperationQueue(fake, this, timeoutMs = 8000L, onTimeout = { timedOut += it })

        queue.enqueue(GattQueueOp.Read(OwCharacteristic.BATTERY_LEVEL))
        advanceTimeBy(8000L)
        runCurrent()
        assertTrue(queue.isIdle)

        // The real GATT callback for the timed-out op arrives late; it must not
        // dispatch or complete anything (queue already idle and abandoned).
        queue.completeCurrent(GattStatus.SUCCESS)
        assertEquals(listOf("read:BATTERY_LEVEL"), fake.calls)
        assertTrue(queue.isIdle)
    }

    @Test
    fun `default timeout is 8000ms`() = runTest {
        val fake = FakeGattOps()
        val timedOut = mutableListOf<GattQueueOp>()
        val queue = GattOperationQueue(fake, this, onTimeout = { timedOut += it })

        queue.enqueue(GattQueueOp.Discover)

        advanceTimeBy(7999L)
        runCurrent()
        assertTrue(timedOut.isEmpty())

        advanceTimeBy(2L)
        runCurrent()
        assertEquals(1, timedOut.size)
    }

    @Test
    fun `a completed op never times out later`() = runTest {
        val fake = FakeGattOps()
        val timedOut = mutableListOf<GattQueueOp>()
        val queue = GattOperationQueue(fake, this, timeoutMs = 1000L, onTimeout = { timedOut += it })

        queue.enqueue(GattQueueOp.Discover)
        queue.completeCurrent(GattStatus.SUCCESS)

        advanceTimeBy(5000L)
        runCurrent()

        assertTrue(timedOut.isEmpty())
    }

    @Test
    fun `write op carries content-based equality for the queued bytes`() {
        val a = GattQueueOp.Write(OwCharacteristic.UART_SERIAL_WRITE, byteArrayOf(1, 2, 3))
        val b = GattQueueOp.Write(OwCharacteristic.UART_SERIAL_WRITE, byteArrayOf(1, 2, 3))
        val c = GattQueueOp.Write(OwCharacteristic.UART_SERIAL_WRITE, byteArrayOf(9))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }
}
