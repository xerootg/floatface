package app.floatface.wear.record

import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseType
import androidx.test.core.app.ApplicationProvider
import app.floatface.core.Clock
import app.floatface.core.Logger
import app.floatface.core.RideLog
import app.floatface.core.RideLogSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeClock(var elapsed: Long = 0L, var wall: Long = 1_700_000_000_000L) : Clock {
    override fun elapsedRealtimeMs(): Long = elapsed
    override fun wallClockMs(): Long = wall
}

private class FakeLogger : Logger {
    override fun d(tag: String, msg: String) = Unit
    override fun w(tag: String, msg: String) = Unit
    override fun e(tag: String, msg: String, t: Throwable?) = Unit
}

private class RecordingRideLog : RideLog {
    var opened: String? = null
    val appended = mutableListOf<RideLogSample>()
    var closed = false

    override fun open(sessionId: String) {
        opened = sessionId
    }

    override fun append(sample: RideLogSample) {
        appended += sample
    }

    override fun close() {
        closed = true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExerciseConfigTest {

    private lateinit var recorder: HealthServicesRideRecorder
    private lateinit var rideLog: RecordingRideLog

    @Before
    fun setUp() {
        rideLog = RecordingRideLog()
        recorder = HealthServicesRideRecorder(
            context = ApplicationProvider.getApplicationContext(),
            rideLog = rideLog,
            clock = FakeClock(),
            logger = FakeLogger(),
        )
    }

    @Test
    fun `enables GPS`() {
        val config = recorder.buildExerciseConfig()
        assertTrue(config.isGpsEnabled)
    }

    @Test
    fun `includes DataType LOCATION`() {
        val config = recorder.buildExerciseConfig()
        assertTrue(config.dataTypes.contains(DataType.LOCATION))
    }

    @Test
    fun `exercise type is a GPS-bearing type, never UNKNOWN`() {
        val config = recorder.buildExerciseConfig()
        assertNotEquals(ExerciseType.UNKNOWN, config.exerciseType)
    }

    @Test
    fun `onTelemetry before recording starts does not append`() {
        recorder.onTelemetry(app.floatface.core.TelemetrySnapshot.EMPTY, nowMs = 100L)
        assertEquals(0, rideLog.appended.size)
    }

    @Test
    fun `fahrenheitToCelsius converts the pinned reference point`() {
        // 32F == 0C, 212F == 100C
        assertEquals(0, HealthServicesRideRecorder.fahrenheitToCelsius(32))
        assertEquals(100, HealthServicesRideRecorder.fahrenheitToCelsius(212))
    }
}
