package app.floatface.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsTest {

    // ---- RpmSpeed ----

    @Test
    fun `rpm zero yields zero mph`() {
        assertEquals(0.0, RpmSpeed.toMph(0, 11.0), 1e-9)
    }

    @Test
    fun `rpm spins down to zero smoothly`() {
        val diameter = 11.0
        val speeds = listOf(600, 400, 200, 100, 0).map { RpmSpeed.toMph(it, diameter) }
        // strictly decreasing as rpm decreases, ending at zero
        for (i in 0 until speeds.size - 1) {
            assertTrue(speeds[i] > speeds[i + 1], "expected decreasing speed sequence: $speeds")
        }
        assertEquals(0.0, speeds.last(), 1e-9)
    }

    @Test
    fun `rpm to mph matches formula`() {
        val rpm = 300
        val diameter = 11.0
        val expected = Math.PI * diameter * rpm * 60.0 / 63360.0
        assertEquals(expected, RpmSpeed.toMph(rpm, diameter), 1e-9)
    }

    // ---- DistanceIntegrator ----

    @Test
    fun `first sample after construction integrates zero`() {
        val di = DistanceIntegrator()
        di.onSpeed(10.0, 1_000L)
        assertEquals(0.0, di.miles, 1e-9)
    }

    @Test
    fun `distance integrates over injected timestamps`() {
        val di = DistanceIntegrator()
        val t0 = 0L
        val t1 = t0 + 3_600_000L // +1 hour
        di.onSpeed(10.0, t0) // first sample, integrates 0
        di.onSpeed(10.0, t1) // 1 hour at 10 mph -> 10 miles
        assertEquals(10.0, di.miles, 1e-6)

        val t2 = t1 + 1_800_000L // +30 min
        di.onSpeed(20.0, t2) // 0.5 hour at 20 mph -> 10 more miles
        assertEquals(20.0, di.miles, 1e-6)
    }

    @Test
    fun `first sample after reset integrates zero`() {
        val di = DistanceIntegrator()
        di.onSpeed(10.0, 0L)
        di.onSpeed(10.0, 3_600_000L)
        assertEquals(10.0, di.miles, 1e-6)

        di.reset()
        assertEquals(0.0, di.miles, 1e-9)

        di.onSpeed(50.0, 999_999_999L) // arbitrary far timestamp; first sample post-reset
        assertEquals(0.0, di.miles, 1e-9)
    }

    @Test
    fun `reconnect gap via clearTimestamp integrates zero but keeps prior miles`() {
        val di = DistanceIntegrator()
        di.onSpeed(10.0, 0L)
        di.onSpeed(10.0, 3_600_000L) // 10 miles accumulated
        assertEquals(10.0, di.miles, 1e-6)

        di.clearTimestamp()
        assertEquals(10.0, di.miles, 1e-6) // miles preserved

        // simulate a large gap (disconnect/reconnect) - first sample after gap integrates 0
        di.onSpeed(30.0, 1_000_000_000L)
        assertEquals(10.0, di.miles, 1e-6)

        // subsequent sample integrates normally from the new baseline
        di.onSpeed(30.0, 1_000_000_000L + 3_600_000L)
        assertEquals(40.0, di.miles, 1e-6)
    }

    // ---- RangeEstimator ----

    @Test
    fun `range is null when start battery is null`() {
        assertNull(RangeEstimator.estimate(null, 50, 5.0))
    }

    @Test
    fun `range is null when current battery is null`() {
        assertNull(RangeEstimator.estimate(100, null, 5.0))
    }

    @Test
    fun `range is null when no battery consumed yet`() {
        assertNull(RangeEstimator.estimate(100, 100, 5.0))
    }

    @Test
    fun `range is null when battery increased`() {
        assertNull(RangeEstimator.estimate(50, 60, 5.0))
    }

    @Test
    fun `range estimate is correct once consumed is positive`() {
        // consumed = 100-90 = 10, tripMiles = 5 -> (5/10)*90 = 45
        val result = RangeEstimator.estimate(100, 90, 5.0)
        assertEquals(45.0, result!!, 1e-9)
    }

    // ---- HalfwayWarning ----

    @Test
    fun `halfway does not fire above half`() {
        val hw = HalfwayWarning()
        assertFalse(hw.update(60, 100))
        assertFalse(hw.active)
    }

    @Test
    fun `halfway fires exactly at start over two integer division`() {
        val hw = HalfwayWarning()
        assertFalse(hw.update(51, 100))
        assertTrue(hw.update(50, 100))
        assertTrue(hw.active)
    }

    @Test
    fun `halfway fires exactly once and latches`() {
        val hw = HalfwayWarning()
        assertTrue(hw.update(40, 100))
        assertTrue(hw.active)
        // subsequent calls, even further below half, return false (already fired)
        assertFalse(hw.update(30, 100))
        assertFalse(hw.update(10, 100))
        assertTrue(hw.active)
    }

    @Test
    fun `halfway returns false when inputs null`() {
        val hw = HalfwayWarning()
        assertFalse(hw.update(null, 100))
        assertFalse(hw.update(50, null))
        assertFalse(hw.active)
    }

    @Test
    fun `halfway reset re-arms warning`() {
        val hw = HalfwayWarning()
        assertTrue(hw.update(40, 100))
        assertTrue(hw.active)

        hw.reset()
        assertFalse(hw.active)

        // can fire again after reset with a fresh trip
        assertTrue(hw.update(20, 50))
        assertTrue(hw.active)
    }

    @Test
    fun `halfway odd start uses integer division`() {
        // start=101 -> 101/2 = 50 (integer division); current=50 should fire
        val hw = HalfwayWarning()
        assertFalse(hw.update(51, 101))
        assertTrue(hw.update(50, 101))
    }
}
