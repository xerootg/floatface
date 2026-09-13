package app.floatface.wear.platform

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CoroutineTicker] under kotlinx-coroutines-test virtual time: `repeat`
 * fires [Ticker.schedule]'s action on every interval boundary, and
 * cancelling the returned [Cancellable] stops further firing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineTickerTest {

    @Test
    fun repeatFiresActionMultipleTimesUnderVirtualTime() = runTest {
        val ticker = CoroutineTicker(backgroundScope)
        var fireCount = 0

        ticker.schedule(intervalMs = 1_000L, repeat = true) { fireCount++ }

        advanceTimeBy(3_500L)
        runCurrent()

        assertTrue("expected at least 3 fires, got $fireCount", fireCount >= 3)
    }

    @Test
    fun nonRepeatingScheduleFiresExactlyOnce() = runTest {
        val ticker = CoroutineTicker(backgroundScope)
        var fireCount = 0

        ticker.schedule(intervalMs = 1_000L, repeat = false) { fireCount++ }

        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(1, fireCount)
    }

    @Test
    fun cancelStopsFurtherFiring() = runTest {
        val ticker = CoroutineTicker(backgroundScope)
        var fireCount = 0

        val cancellable = ticker.schedule(intervalMs = 1_000L, repeat = true) { fireCount++ }

        advanceTimeBy(1_000L)
        runCurrent()
        val countAfterFirstFire = fireCount
        assertTrue(countAfterFirstFire >= 1)

        cancellable.cancel()

        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(countAfterFirstFire, fireCount)
    }
}
