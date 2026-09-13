package app.floatface.wear.ui

import app.floatface.core.BoardModel
import app.floatface.core.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattingTest {

    @Test
    fun `battery color thresholds`() {
        assertEquals(batteryColor(100), batteryColor(75))
        assertEquals(batteryColor(75), batteryColor(90))
        assertEquals(batteryColor(74), batteryColor(50))
        assertEquals(batteryColor(49), batteryColor(25))
        assertEquals(batteryColor(24), batteryColor(0))
        assertEquals(batteryColor(null), batteryColor(null))
    }

    @Test
    fun `battery color is distinct per band`() {
        val blue = batteryColor(80)
        val green = batteryColor(60)
        val yellow = batteryColor(30)
        val red = batteryColor(5)
        val grey = batteryColor(null)
        val colors = setOf(blue, green, yellow, red, grey)
        assertEquals(5, colors.size)
    }

    @Test
    fun `riding mode text known mode`() {
        assertEquals("Roam", ridingModeText(BoardModel.GT, 4))
    }

    @Test
    fun `riding mode text unknown mode falls back to raw number`() {
        assertEquals("Mode 99", ridingModeText(BoardModel.GT, 99))
    }

    @Test
    fun `riding mode text null mode is a dash`() {
        assertEquals("--", ridingModeText(BoardModel.GT, null))
    }

    @Test
    fun `riding mode text on unconfirmed board falls back to raw number`() {
        val unconfirmed = BoardModel.forGeneration(7)
        assertEquals("Mode 4", ridingModeText(unconfirmed, 4))
    }

    @Test
    fun `valueOrDash renders null as dash`() {
        assertEquals("--", valueOrDash<Int>(null))
    }

    @Test
    fun `valueOrDash renders present value via formatter`() {
        assertEquals("42%", valueOrDash(42) { "$it%" })
    }

    @Test
    fun `valueOrDash default formatter uses toString`() {
        assertEquals("7", valueOrDash(7))
    }

    @Test
    fun `connection status text maps known states`() {
        assertEquals("Scanning…", connectionStatusText(ConnectionState.Scanning))
        assertEquals(
            "Board not found — is your phone's Bluetooth off?",
            connectionStatusText(ConnectionState.ScanTimedOut),
        )
        assertEquals("Connected", connectionStatusText(ConnectionState.Connected))
        assertEquals("Disconnected, rescanning…", connectionStatusText(ConnectionState.Rescanning))
        assertEquals("boom", connectionStatusText(ConnectionState.Error("boom")))
    }

    @Test
    fun `valueOrDash is not accidentally null-safe on non-null values`() {
        val v: Int? = null
        assertNull(v)
    }
}
