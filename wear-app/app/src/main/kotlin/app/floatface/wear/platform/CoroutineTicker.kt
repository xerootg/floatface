package app.floatface.wear.platform

import app.floatface.core.Cancellable
import app.floatface.core.Ticker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [Ticker] adapter (SPEC §4.5.7 / §13a) backed by a coroutine on [scope]. Each
 * [schedule] call launches its own job that `delay()`s [intervalMs] then runs
 * [action], repeating indefinitely while [repeat] is true. [scope] must be
 * long-lived (service-scoped, per SPEC §9) so ticks keep firing across
 * Activity lifecycle changes / Doze.
 */
class CoroutineTicker(private val scope: CoroutineScope) : Ticker {

    override fun schedule(intervalMs: Long, repeat: Boolean, action: suspend () -> Unit): Cancellable {
        val job: Job = scope.launch {
            do {
                delay(intervalMs)
                action()
            } while (repeat)
        }
        return object : Cancellable {
            override fun cancel() {
                job.cancel()
            }
        }
    }
}
