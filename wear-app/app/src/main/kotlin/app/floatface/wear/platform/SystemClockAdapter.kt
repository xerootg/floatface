package app.floatface.wear.platform

import android.os.SystemClock
import app.floatface.core.Clock

/**
 * [Clock] adapter (SPEC §4.5.7 / §13a) backed by the real Android clocks.
 * [elapsedRealtimeMs] is Doze-inclusive monotonic time, used for all elapsed
 * time math; [wallClockMs] is the wall clock used for persisted timestamps.
 */
class SystemClockAdapter : Clock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun wallClockMs(): Long = System.currentTimeMillis()
}
