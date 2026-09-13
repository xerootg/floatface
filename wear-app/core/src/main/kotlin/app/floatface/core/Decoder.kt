package app.floatface.core

/**
 * Telemetry decoder (SPEC §3.2, §3.6, §6.2).
 *
 * Pure, stateless byte-to-domain mapping from a single GATT characteristic
 * notification/read into an updated [TelemetrySnapshot]. A payload shorter
 * than required for the characteristic being decoded is treated as garbled
 * and ignored — [decodeInto] returns [prev] unchanged rather than throwing,
 * since a truncated BLE delivery must never crash the pipeline.
 */
object TelemetryDecoder {

    /** Board firmware generation, e.g. firmwareRevision 6217 -> generation 6 (GT). */
    fun generationOf(firmwareRevision: Int): Int = firmwareRevision / 1000

    fun decodeInto(
        prev: TelemetrySnapshot,
        char: OwCharacteristic,
        value: ByteArray,
        board: BoardModel,
    ): TelemetrySnapshot {
        return when (char) {
        OwCharacteristic.BATTERY_LEVEL -> {
            val v = u16be(value) ?: return prev
            prev.copy(batteryLevel = v)
        }

        OwCharacteristic.SPEED_RPM -> {
            val v = u16be(value) ?: return prev
            prev.copy(speedRpm = v, speedMph = RpmSpeed.toMph(v, board.tireDiameterInches))
        }

        OwCharacteristic.RIDING_MODE -> {
            val v = u16be(value) ?: return prev
            prev.copy(ridingMode = v)
        }

        OwCharacteristic.SAFETY_HEADROOM -> {
            val v = u16be(value) ?: return prev
            prev.copy(safetyHeadroom = v)
        }

        OwCharacteristic.STATUS -> {
            val v = u16be(value) ?: return prev
            prev.copy(status = v)
        }

        OwCharacteristic.TRIP_ODOMETER -> {
            val v = u16be(value) ?: return prev
            prev.copy(tripOdometer = v)
        }

        OwCharacteristic.LIFE_ODOMETER -> {
            val v = u16be(value) ?: return prev
            prev.copy(lifeOdometer = v)
        }

        OwCharacteristic.TRIP_AMP_HOURS -> {
            val v = u16be(value) ?: return prev
            prev.copy(tripAmpHours = v)
        }

        OwCharacteristic.TRIP_REGEN_AMP_HOURS -> {
            val v = u16be(value) ?: return prev
            prev.copy(tripRegenAmpHours = v)
        }

        OwCharacteristic.FIRMWARE_REVISION -> {
            val v = u16be(value) ?: return prev
            prev.copy(firmwareRevision = v)
        }

        // Two independent SIGNED-byte Celsius sensor readings, each converted to °F.
        OwCharacteristic.MOTOR_CONTROLLER_TEMP -> {
            if (value.size < 2) return prev
            val aF = cToF(value[0].toInt())
            val bF = cToF(value[1].toInt())
            prev.copy(motorTempAF = aF, motorTempBF = bF)
        }

        OwCharacteristic.BATTERY_LOW_TEMP -> {
            if (value.size < 2) return prev
            val aF = cToF(value[0].toInt())
            val bF = cToF(value[1].toInt())
            prev.copy(batteryTempAF = aF, batteryTempBF = bF)
        }

        // The UART characteristics carry raw protocol frames, not a telemetry field.
        OwCharacteristic.UART_SERIAL_READ, OwCharacteristic.UART_SERIAL_WRITE -> prev
        }
    }

    /** Big-endian unsigned 16-bit read of the first two bytes; null if too short. */
    private fun u16be(value: ByteArray): Int? {
        if (value.size < 2) return null
        return ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
    }

    private fun cToF(c: Int): Int = Math.round(c * 9.0 / 5.0 + 32.0).toInt()
}
