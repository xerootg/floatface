package app.floatface.core

/**
 * FROZEN CONTRACTS (SPEC §4.5). These declarations are normative: adapters in
 * :ble/:app and all tests build against them. Do not diverge without updating
 * the spec first. NO android.* types may appear in :core.
 */

/** The Onewheel GATT characteristics used by the app (SPEC §3.1). */
enum class OwCharacteristic(val uuid: String) {
    FIRMWARE_REVISION("e659f311-ea98-11e3-ac10-0800200c9a66"),
    UART_SERIAL_READ("e659f3fe-ea98-11e3-ac10-0800200c9a66"),
    UART_SERIAL_WRITE("e659f3ff-ea98-11e3-ac10-0800200c9a66"),
    BATTERY_LEVEL("e659f303-ea98-11e3-ac10-0800200c9a66"),
    SPEED_RPM("e659f30b-ea98-11e3-ac10-0800200c9a66"),
    RIDING_MODE("e659f302-ea98-11e3-ac10-0800200c9a66"),
    SAFETY_HEADROOM("e659f317-ea98-11e3-ac10-0800200c9a66"),
    MOTOR_CONTROLLER_TEMP("e659f310-ea98-11e3-ac10-0800200c9a66"),
    BATTERY_LOW_TEMP("e659f315-ea98-11e3-ac10-0800200c9a66"),
    STATUS("e659f30f-ea98-11e3-ac10-0800200c9a66"),
    TRIP_ODOMETER("e659f30a-ea98-11e3-ac10-0800200c9a66"),
    LIFE_ODOMETER("e659f319-ea98-11e3-ac10-0800200c9a66"),
    TRIP_AMP_HOURS("e659f313-ea98-11e3-ac10-0800200c9a66"),
    TRIP_REGEN_AMP_HOURS("e659f314-ea98-11e3-ac10-0800200c9a66");

    companion object {
        const val SERVICE_UUID = "e659f300-ea98-11e3-ac10-0800200c9a66"

        /** Notify characteristics, in the fixed serialization order used at connect (SPEC §3.5). */
        val NOTIFY_SET: List<OwCharacteristic> = listOf(
            BATTERY_LEVEL, SPEED_RPM, RIDING_MODE, SAFETY_HEADROOM,
            MOTOR_CONTROLLER_TEMP, BATTERY_LOW_TEMP, STATUS,
            TRIP_ODOMETER, LIFE_ODOMETER, TRIP_AMP_HOURS, TRIP_REGEN_AMP_HOURS,
        )

        /**
         * Initial read burst after unlock. The first three are PROTOCOL-confirmed
         * on-change characteristics that must be read directly; LIFE_ODOMETER is
         * best-effort (SPEC §3.5).
         */
        val INITIAL_READS: List<OwCharacteristic> = listOf(
            BATTERY_LEVEL, RIDING_MODE, SAFETY_HEADROOM, LIFE_ODOMETER,
        )

        fun fromUuid(uuid: String): OwCharacteristic? = entries.find { it.uuid.equals(uuid, ignoreCase = true) }
    }
}

/** Platform-neutral GATT status (0 == success). */
data class GattStatus(val code: Int) {
    val isSuccess: Boolean get() = code == 0

    companion object {
        val SUCCESS = GattStatus(0)
    }
}

/** GATT operation kinds, used to report [TransportEvent.OperationFailed]. */
enum class GattOp { DISCOVER, ENABLE_NOTIFY, READ, WRITE, MTU, CONNECT }

enum class RecordingState { Idle, Recording }

enum class BuzzPattern { HalfwayWarning }

enum class SpeedUnit { MPH, KMH }

enum class TempUnit { F, C }

/**
 * Runtime board configuration (SPEC §10): the per-owner unlock bytes (as hex)
 * and, optionally, the board's BLE MAC address used to target one specific
 * board. Persisted at runtime (DataStore) so neither has to be compiled in; a
 * BuildConfig value may seed [unlockBytesHex] as a default until the owner sets
 * their own. A blank/null [bleMac] means "match any Onewheel by name/service".
 */
data class BoardConfig(
    val unlockBytesHex: String? = null,
    val bleMac: String? = null,
) {
    companion object {
        val EMPTY = BoardConfig()

        /** True for a syntactically plausible BLE MAC (`AA:BB:CC:DD:EE:FF`). */
        fun isValidMac(mac: String?): Boolean {
            val m = mac?.trim() ?: return false
            return Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(m)
        }
    }
}

/**
 * Immutable snapshot of the latest decoded telemetry (SPEC §3.2). All fields are
 * nullable and null until first received; temperatures are stored here in °F for
 * display (raw Celsius is persisted to the ride log — SPEC §4a).
 */
data class TelemetrySnapshot(
    val batteryLevel: Int? = null,
    val speedRpm: Int? = null,
    val speedMph: Double? = null,
    val ridingMode: Int? = null,
    val safetyHeadroom: Int? = null,
    val motorTempAF: Int? = null,
    val motorTempBF: Int? = null,
    val batteryTempAF: Int? = null,
    val batteryTempBF: Int? = null,
    val status: Int? = null,
    val tripOdometer: Int? = null,
    val lifeOdometer: Int? = null,
    val tripAmpHours: Int? = null,
    val tripRegenAmpHours: Int? = null,
    val firmwareRevision: Int? = null,
) {
    companion object {
        val EMPTY = TelemetrySnapshot()
    }
}

/** Ride-scoped derived state (SPEC §5). Persists across a transient BLE drop. */
data class RideState(
    val recording: RecordingState = RecordingState.Idle,
    val distanceMiles: Double = 0.0,
    val estimatedRangeMiles: Double? = null,
    val halfwayWarningActive: Boolean = false,
)

/** The complete UI state the ViewModel maps from (SPEC §4.5.5). */
data class UiState(
    val connection: ConnectionState = ConnectionState.Scanning,
    val telemetry: TelemetrySnapshot = TelemetrySnapshot.EMPTY,
    val ride: RideState = RideState(),
    val board: BoardModel = BoardModel.GT,
    val page: Int = 0,
    val error: AppError? = null,
)

/**
 * Board-model data keyed by firmware generation (SPEC §3.6). Only GT (generation
 * 6) is hardware-confirmed; others fall back to GT's diameter flagged unconfirmed
 * and show raw mode numbers.
 */
data class BoardModel(
    val generation: Int,
    val tireDiameterInches: Double,
    val ridingModeNames: Map<Int, String>,
    val confirmed: Boolean,
) {
    companion object {
        val GT = BoardModel(
            generation = 6,
            tireDiameterInches = 11.5,
            ridingModeNames = mapOf(
                3 to "Bay", 4 to "Roam", 5 to "Flow",
                6 to "Highline", 7 to "Elevated", 8 to "Apex",
            ),
            confirmed = true,
        )

        fun forGeneration(gen: Int): BoardModel =
            if (gen == 6) GT else GT.copy(generation = gen, confirmed = false, ridingModeNames = emptyMap())
    }
}

/** User-facing error taxonomy (SPEC §8a). Each maps to a UI state + machine transition. */
sealed interface AppError {
    data object BluetoothOff : AppError
    data object LocationServicesOff : AppError
    data class PermissionDenied(val permission: String, val permanent: Boolean) : AppError
    data class ConnectFailed(val status: Int, val attempts: Int) : AppError
    data object ScanTooFrequent : AppError
    data object NotificationEnableFailed : AppError
    data object UnlockWriteFailed : AppError
    data object ConnectTimeout : AppError
    data object BoardOutOfRange : AppError
    data object HealthServicesUnavailable : AppError
    data object ExerciseOwnershipLost : AppError
    data class Fatal(val message: String) : AppError
}

/** One persisted telemetry sample in the ride log (SPEC §4a). Temperatures raw Celsius. */
data class RideLogSample(
    val tsElapsedMs: Long,
    val tsWallMs: Long,
    val speedRpm: Int?,
    val mphEst: Double?,
    val battery: Int?,
    val motorTempAC: Int?,
    val motorTempBC: Int?,
    val batteryTempAC: Int?,
    val batteryTempBC: Int?,
    val ridingMode: Int?,
    val safetyHeadroomRaw: Int?,
    val tripAmpHoursRaw: Int?,
    val tripRegenAmpHoursRaw: Int?,
    val statusRaw: Int?,
    val lat: Double? = null,
    val lon: Double? = null,
)
