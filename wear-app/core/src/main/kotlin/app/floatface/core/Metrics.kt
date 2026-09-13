package app.floatface.core

/**
 * Converts wheel RPM to speed in miles per hour given a tire diameter in inches.
 */
object RpmSpeed {
    fun toMph(rpm: Int, tireDiameterInches: Double): Double {
        return Math.PI * tireDiameterInches * rpm * 60.0 / 63360.0
    }
}

/**
 * Integrates instantaneous speed samples (mph) over wall-clock time to accumulate
 * ride distance in miles.
 */
class DistanceIntegrator {
    private var lastTs: Long? = null
    private var accumulatedMiles: Double = 0.0

    val miles: Double
        get() = accumulatedMiles

    /** Resets accumulated distance to zero and clears the last-sample timestamp. */
    fun reset() {
        accumulatedMiles = 0.0
        lastTs = null
    }

    /** Clears the last-sample timestamp only, keeping accumulated miles intact. */
    fun clearTimestamp() {
        lastTs = null
    }

    /**
     * Integrates [mph] over the elapsed time since the previous sample.
     * The first sample after construction, [reset], or [clearTimestamp] integrates zero
     * (there is no prior timestamp to measure an interval against).
     */
    fun onSpeed(mph: Double, nowMillis: Long) {
        val prev = lastTs
        if (prev != null) {
            val elapsedHours = (nowMillis - prev) / 3_600_000.0
            accumulatedMiles += mph * elapsedHours
        }
        lastTs = nowMillis
    }
}

/**
 * Estimates total achievable range in miles based on battery consumed so far during
 * the trip and the distance already traveled.
 */
object RangeEstimator {
    fun estimate(startBattery: Int?, currentBattery: Int?, tripMiles: Double): Double? {
        if (startBattery == null || currentBattery == null) return null
        val consumed = startBattery - currentBattery
        if (consumed <= 0) return null
        return (tripMiles / consumed) * currentBattery
    }
}

/**
 * Latching warning that fires exactly once when current battery drops to or below
 * half of the trip's starting battery.
 */
class HalfwayWarning {
    private var _active: Boolean = false

    val active: Boolean
        get() = _active

    fun reset() {
        _active = false
    }

    /**
     * Returns true exactly on the transition to fired; once active, subsequent calls
     * return false regardless of battery values until [reset] is called.
     */
    fun update(currentBattery: Int?, startBattery: Int?): Boolean {
        if (_active) return false
        if (startBattery == null || currentBattery == null) return false
        if (currentBattery <= startBattery / 2) {
            _active = true
            return true
        }
        return false
    }
}
