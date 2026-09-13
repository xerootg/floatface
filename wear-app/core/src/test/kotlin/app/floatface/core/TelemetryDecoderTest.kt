package app.floatface.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for [TelemetryDecoder] (SPEC §3.2, §3.6, §6.2).
 *
 * NOTE: a shared `ByteVectors.kt` test-fixture file does not exist in the repo
 * at the time this suite was authored, so the canonical byte vectors named in
 * the task brief are reproduced here as local constants (same values, same
 * semantics) rather than introduced as a new shared fixture file outside this
 * task's scope.
 */
class TelemetryDecoderTest {

    private val board = BoardModel.GT

    // --- Canonical byte vectors -------------------------------------------------
    private val batteryBytes = byteArrayOf(0x00, 0x40) // -> 64
    private val motorBytesPositive = byteArrayOf(0x20, 0x1b) // -> (90F, 81F)
    private val motorBytesNegative = byteArrayOf(0xfb.toByte(), 0xfe.toByte()) // -> (23F, 28F)
    private val batteryLowBytes = byteArrayOf(0x1d, 0x1c) // -> (84F, 82F)
    private val genericU16Bytes = byteArrayOf(0x08, 0x00) // -> 2048
    private val speedRpmBytes = byteArrayOf(0x04, 0x17) // -> 1047 rpm

    // --- BATTERY_LEVEL -----------------------------------------------------------

    @Test
    fun decodesBatteryLevelAsUnsignedBigEndian16() {
        val result = TelemetryDecoder.decodeInto(TelemetrySnapshot.EMPTY, OwCharacteristic.BATTERY_LEVEL, batteryBytes, board)
        assertEquals(64, result.batteryLevel)
    }

    // --- MOTOR_CONTROLLER_TEMP ----------------------------------------------------

    @Test
    fun decodesMotorControllerTempAsTwoIndependentSignedBytesConvertedToFahrenheit() {
        val result = TelemetryDecoder.decodeInto(TelemetrySnapshot.EMPTY, OwCharacteristic.MOTOR_CONTROLLER_TEMP, motorBytesPositive, board)
        assertEquals(90, result.motorTempAF)
        assertEquals(81, result.motorTempBF)
    }

    @Test
    fun decodesMotorControllerTempWithNegativeCelsiusSensorBytes() {
        val result = TelemetryDecoder.decodeInto(TelemetrySnapshot.EMPTY, OwCharacteristic.MOTOR_CONTROLLER_TEMP, motorBytesNegative, board)
        assertEquals(23, result.motorTempAF)
        assertEquals(28, result.motorTempBF)
    }

    // --- BATTERY_LOW_TEMP ----------------------------------------------------------

    @Test
    fun decodesBatteryLowTempAsTwoIndependentSignedBytesConvertedToFahrenheit() {
        val result = TelemetryDecoder.decodeInto(TelemetrySnapshot.EMPTY, OwCharacteristic.BATTERY_LOW_TEMP, batteryLowBytes, board)
        assertEquals(84, result.batteryTempAF)
        assertEquals(82, result.batteryTempBF)
    }

    // --- Generic u16be characteristics --------------------------------------------

    @Test
    fun decodesRidingModeSafetyHeadroomStatusAndOdometersAsUnsignedBigEndian16() {
        val cases = listOf(
            OwCharacteristic.RIDING_MODE,
            OwCharacteristic.SAFETY_HEADROOM,
            OwCharacteristic.STATUS,
            OwCharacteristic.TRIP_ODOMETER,
            OwCharacteristic.LIFE_ODOMETER,
            OwCharacteristic.TRIP_AMP_HOURS,
            OwCharacteristic.TRIP_REGEN_AMP_HOURS,
        )
        for (char in cases) {
            val result = TelemetryDecoder.decodeInto(TelemetrySnapshot.EMPTY, char, genericU16Bytes, board)
            val actual = when (char) {
                OwCharacteristic.RIDING_MODE -> result.ridingMode
                OwCharacteristic.SAFETY_HEADROOM -> result.safetyHeadroom
                OwCharacteristic.STATUS -> result.status
                OwCharacteristic.TRIP_ODOMETER -> result.tripOdometer
                OwCharacteristic.LIFE_ODOMETER -> result.lifeOdometer
                OwCharacteristic.TRIP_AMP_HOURS -> result.tripAmpHours
                OwCharacteristic.TRIP_REGEN_AMP_HOURS -> result.tripRegenAmpHours
                else -> error("unreachable")
            }
            assertEquals(2048, actual, "expected $char to decode to 2048")
        }
    }

    @Test
    fun decodesFirmwareRevisionAsUnsignedBigEndian16() {
        val result = TelemetryDecoder.decodeInto(TelemetrySnapshot.EMPTY, OwCharacteristic.FIRMWARE_REVISION, genericU16Bytes, board)
        assertEquals(2048, result.firmwareRevision)
    }

    // --- SPEED_RPM + derived mph ---------------------------------------------------

    @Test
    fun decodesSpeedRpmAndComputesMphFromBoardTireDiameter() {
        val result = TelemetryDecoder.decodeInto(TelemetrySnapshot.EMPTY, OwCharacteristic.SPEED_RPM, speedRpmBytes, board)
        assertEquals(1047, result.speedRpm)
        assertNotNull(result.speedMph)
        assertTrue(result.speedMph!! > 0.0)
        assertEquals(RpmSpeed.toMph(1047, board.tireDiameterInches), result.speedMph)
    }

    @Test
    fun speedMphScalesWithTireDiameter() {
        val gt = TelemetryDecoder.decodeInto(TelemetrySnapshot.EMPTY, OwCharacteristic.SPEED_RPM, speedRpmBytes, BoardModel.GT)
        val biggerTire = BoardModel.GT.copy(tireDiameterInches = BoardModel.GT.tireDiameterInches * 2)
        val bigger = TelemetryDecoder.decodeInto(TelemetrySnapshot.EMPTY, OwCharacteristic.SPEED_RPM, speedRpmBytes, biggerTire)
        assertTrue(bigger.speedMph!! > gt.speedMph!!)
    }

    // --- generationOf ---------------------------------------------------------------

    @Test
    fun generationOfDividesFirmwareRevisionByOneThousand() {
        assertEquals(6, TelemetryDecoder.generationOf(6217))
        assertEquals(0, TelemetryDecoder.generationOf(999))
        assertEquals(4, TelemetryDecoder.generationOf(4001))
    }

    // --- Truncated / garbled payloads leave prev unchanged --------------------------

    @Test
    fun tooShortPayloadLeavesPreviousSnapshotUnchangedForU16Characteristics() {
        val prev = TelemetrySnapshot(batteryLevel = 55)
        val tooShort = byteArrayOf(0x01)
        val result = TelemetryDecoder.decodeInto(prev, OwCharacteristic.BATTERY_LEVEL, tooShort, board)
        assertEquals(prev, result)
    }

    @Test
    fun emptyPayloadLeavesPreviousSnapshotUnchangedForU16Characteristics() {
        val prev = TelemetrySnapshot(tripOdometer = 12)
        val result = TelemetryDecoder.decodeInto(prev, OwCharacteristic.TRIP_ODOMETER, byteArrayOf(), board)
        assertEquals(prev, result)
    }

    @Test
    fun tooShortPayloadLeavesPreviousSnapshotUnchangedForDualSensorTempCharacteristics() {
        val prev = TelemetrySnapshot(motorTempAF = 70, motorTempBF = 71)
        val result = TelemetryDecoder.decodeInto(prev, OwCharacteristic.MOTOR_CONTROLLER_TEMP, byteArrayOf(0x05), board)
        assertEquals(prev, result)
    }

    @Test
    fun tooShortPayloadLeavesPreviousSnapshotUnchangedForBatteryLowTemp() {
        val prev = TelemetrySnapshot(batteryTempAF = 60, batteryTempBF = 61)
        val result = TelemetryDecoder.decodeInto(prev, OwCharacteristic.BATTERY_LOW_TEMP, byteArrayOf(), board)
        assertEquals(prev, result)
    }

    @Test
    fun tooShortPayloadLeavesPreviousSnapshotUnchangedForSpeedRpm() {
        val prev = TelemetrySnapshot(speedRpm = 500, speedMph = 5.0)
        val result = TelemetryDecoder.decodeInto(prev, OwCharacteristic.SPEED_RPM, byteArrayOf(0x01), board)
        assertEquals(prev, result)
    }

    // --- decodeInto only touches the relevant field(s), preserving the rest ---------

    @Test
    fun decodeIntoOnlyUpdatesTheDecodedFieldsAndPreservesEverythingElse() {
        val prev = TelemetrySnapshot(
            batteryLevel = 10,
            ridingMode = 5,
            firmwareRevision = 6217,
        )
        val result = TelemetryDecoder.decodeInto(prev, OwCharacteristic.BATTERY_LEVEL, batteryBytes, board)
        assertEquals(64, result.batteryLevel)
        assertEquals(5, result.ridingMode)
        assertEquals(6217, result.firmwareRevision)
    }

    // --- UART characteristics are not telemetry fields -------------------------------

    @Test
    fun uartCharacteristicsAreIgnoredAndReturnPreviousSnapshotUnchanged() {
        val prev = TelemetrySnapshot(batteryLevel = 42)
        val result = TelemetryDecoder.decodeInto(prev, OwCharacteristic.UART_SERIAL_READ, byteArrayOf(1, 2, 3, 4), board)
        assertEquals(prev, result)
    }
}
