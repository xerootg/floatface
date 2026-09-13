package app.floatface.wear.record

import app.floatface.core.RideLogSample
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JsonlRideLogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sample(i: Long, withNulls: Boolean = false) = RideLogSample(
        tsElapsedMs = i,
        tsWallMs = 1_700_000_000_000L + i,
        speedRpm = if (withNulls) null else 100 + i.toInt(),
        mphEst = if (withNulls) null else 12.5,
        battery = if (withNulls) null else 80,
        motorTempAC = if (withNulls) null else 30,
        motorTempBC = if (withNulls) null else 31,
        batteryTempAC = if (withNulls) null else 25,
        batteryTempBC = if (withNulls) null else 26,
        ridingMode = if (withNulls) null else 5,
        safetyHeadroomRaw = if (withNulls) null else 42,
        tripAmpHoursRaw = if (withNulls) null else 10,
        tripRegenAmpHoursRaw = if (withNulls) null else 1,
        statusRaw = if (withNulls) null else 0,
        lat = if (withNulls) null else 37.5,
        lon = if (withNulls) null else -122.3,
    )

    @Test
    fun `writes one JSONL line per appended sample`() {
        val baseDir = tempFolder.newFolder("rides")
        val log = JsonlRideLog(baseDir)

        log.open("session-1")
        repeat(5) { log.append(sample(it.toLong())) }
        log.close()

        val file = baseDir.listFiles()!!.single { it.name == "session-1.jsonl" }
        val lines = file.readLines()
        assertEquals(5, lines.size)
        lines.forEach { line ->
            assertTrue(line.startsWith("{"))
            assertTrue(line.endsWith("}"))
        }
    }

    @Test
    fun `null fields are serialized as JSON null, not omitted or crashing`() {
        val baseDir = tempFolder.newFolder("rides-null")
        val log = JsonlRideLog(baseDir)

        log.open("session-nulls")
        log.append(sample(0, withNulls = true))
        log.close()

        val file = File(baseDir, "session-nulls.jsonl")
        val line = file.readLines().single()
        assertTrue(line.contains("\"speedRpm\":null"))
        assertTrue(line.contains("\"lat\":null"))
        assertTrue(line.contains("\"lon\":null"))
    }

    @Test
    fun `a couple of fields round-trip readable values`() {
        val baseDir = tempFolder.newFolder("rides-fields")
        val log = JsonlRideLog(baseDir)

        log.open("session-fields")
        log.append(sample(7))
        log.close()

        val line = File(baseDir, "session-fields.jsonl").readLines().single()
        assertTrue(line.contains("\"tsElapsedMs\":7"))
        assertTrue(line.contains("\"battery\":80"))
        assertTrue(line.contains("\"ridingMode\":5"))
    }

    @Test
    fun `close flushes and further appends after close are ignored, not crashing`() {
        val baseDir = tempFolder.newFolder("rides-close")
        val log = JsonlRideLog(baseDir)

        log.open("session-close")
        log.append(sample(0))
        log.close()
        // append after close should not throw
        log.append(sample(1))

        val file = File(baseDir, "session-close.jsonl")
        assertEquals(1, file.readLines().size)
        assertFalse(file.readLines().isEmpty())
    }
}
