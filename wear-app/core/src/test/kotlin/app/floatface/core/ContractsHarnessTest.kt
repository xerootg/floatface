package app.floatface.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Harness test proving the :core toolchain (JUnit5 on the JVM) runs, and pinning
 * the frozen §4.5 constants. Behavioral tests (decoder, reducer, integrators,
 * UnlockBytes) are added test-first by the implementation swarm.
 */
class ContractsHarnessTest {

    @Test
    fun everyCharacteristicUuidBelongsToTheOnewheelService() {
        val suffix = "-ea98-11e3-ac10-0800200c9a66"
        OwCharacteristic.entries.forEach { c ->
            assertTrue(c.uuid.endsWith(suffix), "${c.name} has unexpected UUID ${c.uuid}")
        }
        assertTrue(OwCharacteristic.SERVICE_UUID.endsWith(suffix))
    }

    @Test
    fun soleWriteTargetIsUartSerialWrite() {
        assertEquals("e659f3ff-ea98-11e3-ac10-0800200c9a66", OwCharacteristic.UART_SERIAL_WRITE.uuid)
    }

    @Test
    fun notifySetAndInitialReadsAreTheExpectedShape() {
        assertEquals(11, OwCharacteristic.NOTIFY_SET.size)
        assertEquals(OwCharacteristic.NOTIFY_SET.toSet().size, OwCharacteristic.NOTIFY_SET.size, "no duplicates")
        // UART write is never notified.
        assertTrue(OwCharacteristic.UART_SERIAL_WRITE !in OwCharacteristic.NOTIFY_SET)
        assertEquals(
            listOf(
                OwCharacteristic.BATTERY_LEVEL,
                OwCharacteristic.RIDING_MODE,
                OwCharacteristic.SAFETY_HEADROOM,
                OwCharacteristic.LIFE_ODOMETER,
            ),
            OwCharacteristic.INITIAL_READS,
        )
    }

    @Test
    fun fromUuidRoundTripsCaseInsensitively() {
        assertEquals(OwCharacteristic.BATTERY_LEVEL, OwCharacteristic.fromUuid("e659f303-ea98-11e3-ac10-0800200c9a66"))
        assertEquals(OwCharacteristic.SPEED_RPM, OwCharacteristic.fromUuid("E659F30B-EA98-11E3-AC10-0800200C9A66"))
        assertEquals(null, OwCharacteristic.fromUuid("deadbeef"))
    }

    @Test
    fun gtBoardModelIsConfirmedWithSixModes() {
        val gt = BoardModel.GT
        assertTrue(gt.confirmed)
        assertEquals(11.5, gt.tireDiameterInches)
        assertEquals(6, gt.ridingModeNames.size)
        assertEquals("Apex", gt.ridingModeNames[8])
        val unknown = BoardModel.forGeneration(4)
        assertTrue(!unknown.confirmed)
        assertTrue(unknown.ridingModeNames.isEmpty())
    }

    @Test
    fun byteArrayCarryingEventsCompareByContent() {
        val a = TransportEvent.CharacteristicChanged(OwCharacteristic.SPEED_RPM, byteArrayOf(0x04, 0x17))
        val b = TransportEvent.CharacteristicChanged(OwCharacteristic.SPEED_RPM, byteArrayOf(0x04, 0x17))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val w1 = Command.WriteUnlock(byteArrayOf(1, 2, 3))
        val w2 = Command.WriteUnlock(byteArrayOf(1, 2, 3))
        assertEquals(w1, w2)
    }

    @Test
    fun defaultUiStateIsScanningWithEmptyTelemetry() {
        val s = UiState()
        assertEquals(ConnectionState.Scanning, s.connection)
        assertEquals(TelemetrySnapshot.EMPTY, s.telemetry)
        assertEquals(RecordingState.Idle, s.ride.recording)
        assertNotNull(s.board)
    }
}
