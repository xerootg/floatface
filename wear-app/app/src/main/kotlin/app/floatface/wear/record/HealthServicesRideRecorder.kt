package app.floatface.wear.record

import android.content.Context
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseEvent
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import app.floatface.core.Clock
import app.floatface.core.Logger
import app.floatface.core.RecordingState
import app.floatface.core.RideLog
import app.floatface.core.RideLogSample
import app.floatface.core.RideRecorder
import app.floatface.core.TelemetrySnapshot
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [RideRecorder] backed by Health Services (SPEC §7). Health Services supplies
 * GPS only (via [ExerciseUpdateCallback] + `DataType.LOCATION`); every other
 * telemetry field is persisted directly by this class into the injected
 * [RideLog] (SPEC §4a) — Health Services never sees board telemetry.
 *
 * Coordinates used: `androidx.health:health-services-client:1.1.0-rc02`
 * (`androidx.health.services.client.*`), already declared in the version
 * catalog as `libs.androidx.health.services`.
 */
class HealthServicesRideRecorder(
    private val context: Context,
    private val rideLog: RideLog,
    private val clock: Clock,
    private val logger: Logger,
) : RideRecorder {

    private val _state = MutableStateFlow(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    @Volatile private var lastLocation: Pair<Double, Double>? = null

    /**
     * Lazily resolved so a device/emulator without Health Services never
     * crashes the app; null forever after the first failed resolution.
     */
    private val exerciseClient: ExerciseClient? by lazy {
        try {
            HealthServices.getClient(context).exerciseClient
        } catch (t: Throwable) {
            logger.e(TAG, "Health Services unavailable", t)
            null
        }
    }

    private val updateCallback = object : ExerciseUpdateCallback {
        override fun onRegistered() {
            logger.d(TAG, "Exercise update callback registered")
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            logger.e(TAG, "Exercise update callback registration failed", throwable)
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val point = update.latestMetrics.getData(DataType.LOCATION).lastOrNull() ?: return
            lastLocation = point.value.latitude to point.value.longitude
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            logger.d(TAG, "Availability changed for $dataType: $availability")
        }

        override fun onExerciseEventReceived(event: ExerciseEvent) = Unit
    }

    /**
     * Extracted for testability (SPEC §7): a GPS-bearing [ExerciseType]
     * (never [ExerciseType.UNKNOWN], which yields no GPS) with GPS enabled
     * and `DataType.LOCATION` requested.
     */
    fun buildExerciseConfig(): ExerciseConfig =
        ExerciseConfig.Builder(ExerciseType.WALKING)
            .setIsGpsEnabled(true)
            .setDataTypes(setOf(DataType.LOCATION))
            .build()

    override suspend fun start() {
        val id = "ride-${clock.wallClockMs()}"
        rideLog.open(id)

        val client = exerciseClient
        if (client == null) {
            logger.w(TAG, "start(): Health Services unavailable; recording without GPS")
            _state.value = RecordingState.Recording
            return
        }

        try {
            client.setUpdateCallback(updateCallback)
            client.startExerciseAsync(buildExerciseConfig()).await()
        } catch (t: Throwable) {
            logger.e(TAG, "start() failed to launch exercise", t)
        }
        _state.value = RecordingState.Recording
    }

    override suspend fun stop() {
        val client = exerciseClient
        if (client != null) {
            try {
                client.endExerciseAsync().await()
                client.clearUpdateCallbackAsync(updateCallback).await()
            } catch (t: Throwable) {
                logger.e(TAG, "stop() failed to end exercise", t)
            }
        }
        rideLog.close()
        lastLocation = null
        _state.value = RecordingState.Idle
    }

    override fun onTelemetry(snapshot: TelemetrySnapshot, nowMs: Long) {
        if (_state.value != RecordingState.Recording) return
        val loc = lastLocation
        rideLog.append(
            RideLogSample(
                tsElapsedMs = nowMs,
                tsWallMs = clock.wallClockMs(),
                speedRpm = snapshot.speedRpm,
                mphEst = snapshot.speedMph,
                battery = snapshot.batteryLevel,
                motorTempAC = snapshot.motorTempAF?.let(::fahrenheitToCelsius),
                motorTempBC = snapshot.motorTempBF?.let(::fahrenheitToCelsius),
                batteryTempAC = snapshot.batteryTempAF?.let(::fahrenheitToCelsius),
                batteryTempBC = snapshot.batteryTempBF?.let(::fahrenheitToCelsius),
                ridingMode = snapshot.ridingMode,
                safetyHeadroomRaw = snapshot.safetyHeadroom,
                tripAmpHoursRaw = snapshot.tripAmpHours,
                tripRegenAmpHoursRaw = snapshot.tripRegenAmpHours,
                statusRaw = snapshot.status,
                lat = loc?.first,
                lon = loc?.second,
            ),
        )
    }

    companion object {
        private const val TAG = "HealthServicesRideRecorder"

        /**
         * [TelemetrySnapshot] only carries display Fahrenheit (the decoder's raw
         * Celsius input is not itself part of the frozen model), so this is a
         * best-effort inverse used solely to satisfy the ride log's "raw Celsius"
         * schema (SPEC §4a) from the data this recorder actually receives.
         */
        internal fun fahrenheitToCelsius(f: Int): Int = Math.round((f - 32) * 5.0 / 9.0).toInt()
    }
}
