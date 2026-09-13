package app.floatface.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import androidx.test.core.app.ApplicationProvider
import app.floatface.core.GattStatus
import app.floatface.core.Logger
import app.floatface.core.OwCharacteristic
import app.floatface.core.TransportEvent
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/** Records every call made through [GattOps], in order, for assertion. */
private class RecordingGattOps : GattOps {
    val writes = mutableListOf<Pair<OwCharacteristic, ByteArray>>()
    val discovers = mutableListOf<Unit>()
    val enableNotifies = mutableListOf<OwCharacteristic>()
    val reads = mutableListOf<OwCharacteristic>()

    override fun discover() { discovers += Unit }
    override fun enableNotify(c: OwCharacteristic) { enableNotifies += c }
    override fun read(c: OwCharacteristic) { reads += c }
    override fun write(c: OwCharacteristic, bytes: ByteArray) { writes += c to bytes }
}

private val silentLogger = object : Logger {
    override fun d(tag: String, msg: String) {}
    override fun w(tag: String, msg: String) {}
    override fun e(tag: String, msg: String, t: Throwable?) {}
}

/**
 * SPEC §4.5.4 / §6.1: GATT op-queue wiring, Android-callback → [TransportEvent]
 * translation, and the read-only `writeUnlock` guarantee. The op-queue
 * abstraction ([GattOps]) is injected directly (bypassing a real, radio-backed
 * `BluetoothGatt`) for the write-target test, exactly as intended by that
 * seam; callback-mapping tests drive the adapter's real `BluetoothGattCallback`
 * with framework POJOs Robolectric can construct without hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidOnewheelTransportTest {

    private fun newTransport(scope: kotlinx.coroutines.CoroutineScope) = AndroidOnewheelTransport(
        context = ApplicationProvider.getApplicationContext(),
        logger = silentLogger,
        scope = scope,
    )

    @Test
    fun `writeUnlock targets only UART_SERIAL_WRITE and nothing else`() = runTest {
        val fakeOps = RecordingGattOps()
        val transport = newTransport(this)
        transport.queue = GattOperationQueue(fakeOps, this)
        transport.servicesDiscovered = true

        transport.writeUnlock(byteArrayOf(1, 2, 3, 4))

        assertEquals(1, fakeOps.writes.size)
        val (char, bytes) = fakeOps.writes[0]
        assertEquals(OwCharacteristic.UART_SERIAL_WRITE, char)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes)
        // No other GATT operation kind was ever issued by writeUnlock.
        assertTrue(fakeOps.discovers.isEmpty())
        assertTrue(fakeOps.enableNotifies.isEmpty())
        assertTrue(fakeOps.reads.isEmpty())
    }

    @Test
    fun `writeUnlock before service discovery is guarded and dropped`() = runTest {
        val fakeOps = RecordingGattOps()
        val transport = newTransport(this)
        transport.queue = GattOperationQueue(fakeOps, this)
        // servicesDiscovered defaults to false.

        transport.writeUnlock(byteArrayOf(9))

        assertTrue(fakeOps.writes.isEmpty())
    }

    @Test
    fun `writeUnlock with no active connection is dropped, not crashed`() = runTest {
        val transport = newTransport(this)
        transport.servicesDiscovered = true
        // queue stays null (never connected).

        transport.writeUnlock(byteArrayOf(1))
        // No exception means the guard held.
    }

    @Test
    fun `connection state changes map to Connected and Disconnected`() = runTest {
        val transport = newTransport(this)
        val received = mutableListOf<TransportEvent>()
        backgroundScope.launch { transport.events.collect { received += it } }
        runCurrent()

        val fakeGatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        transport.callback.onConnectionStateChange(fakeGatt, 0, BluetoothProfile.STATE_CONNECTED)
        transport.callback.onConnectionStateChange(fakeGatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()

        assertEquals(listOf(TransportEvent.Connected, TransportEvent.Disconnected(GattStatus(8))), received)
    }

    @Test
    fun `a characteristic-changed notification maps to CharacteristicChanged`() = runTest {
        val transport = newTransport(this)
        val received = mutableListOf<TransportEvent>()
        backgroundScope.launch { transport.events.collect { received += it } }
        runCurrent()

        val characteristic = BluetoothGattCharacteristic(
            UUID.fromString(OwCharacteristic.BATTERY_LEVEL.uuid),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        )
        val fakeGatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        transport.callback.onCharacteristicChanged(fakeGatt, characteristic, byteArrayOf(0, 64))
        runCurrent()

        assertEquals(1, received.size)
        val event = received[0] as TransportEvent.CharacteristicChanged
        assertEquals(OwCharacteristic.BATTERY_LEVEL, event.char)
        assertArrayEquals(byteArrayOf(0, 64), event.value)
    }

    @Test
    fun `an unrecognized characteristic uuid is dropped rather than crashing`() = runTest {
        val transport = newTransport(this)
        val received = mutableListOf<TransportEvent>()
        backgroundScope.launch { transport.events.collect { received += it } }
        runCurrent()

        val unknown = BluetoothGattCharacteristic(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        )
        val fakeGatt = Shadow.newInstanceOf(BluetoothGatt::class.java)
        transport.callback.onCharacteristicChanged(fakeGatt, unknown, byteArrayOf(1))
        runCurrent()

        assertTrue(received.isEmpty())
    }
}
