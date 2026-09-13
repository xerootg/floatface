package app.floatface.wear.record

import app.floatface.core.RideLog
import app.floatface.core.RideLogSample
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * [RideLog] adapter that appends one JSON object per line (JSONL) to a
 * per-session file under [baseDir] (SPEC §4a). Uses a minimal hand-rolled JSON
 * writer to avoid pulling in a JSON library dependency; robust to null fields.
 */
class JsonlRideLog(private val baseDir: File) : RideLog {

    private var writer: BufferedWriter? = null

    override fun open(sessionId: String) {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val file = File(baseDir, "$sessionId.jsonl")
        writer = BufferedWriter(FileWriter(file, /* append = */ true))
    }

    override fun append(sample: RideLogSample) {
        val w = writer ?: return
        w.write(toJson(sample))
        w.newLine()
        w.flush()
    }

    override fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    companion object {
        internal fun toJson(sample: RideLogSample): String {
            val sb = StringBuilder()
            sb.append('{')
            var first = true
            fun field(key: String, value: Any?) {
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(key).append("\":").append(renderValue(value))
            }
            field("tsElapsedMs", sample.tsElapsedMs)
            field("tsWallMs", sample.tsWallMs)
            field("speedRpm", sample.speedRpm)
            field("mphEst", sample.mphEst)
            field("battery", sample.battery)
            field("motorTempA_C", sample.motorTempAC)
            field("motorTempB_C", sample.motorTempBC)
            field("batteryTempA_C", sample.batteryTempAC)
            field("batteryTempB_C", sample.batteryTempBC)
            field("ridingMode", sample.ridingMode)
            field("safetyHeadroomRaw", sample.safetyHeadroomRaw)
            field("tripAmpHoursRaw", sample.tripAmpHoursRaw)
            field("tripRegenAmpHoursRaw", sample.tripRegenAmpHoursRaw)
            field("statusRaw", sample.statusRaw)
            field("lat", sample.lat)
            field("lon", sample.lon)
            sb.append('}')
            return sb.toString()
        }

        private fun renderValue(value: Any?): String = when (value) {
            null -> "null"
            is Double -> if (value.isFinite()) value.toString() else "null"
            is Long, is Int -> value.toString()
            else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }
}
