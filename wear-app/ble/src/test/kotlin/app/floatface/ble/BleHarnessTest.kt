package app.floatface.ble

import android.util.Base64
import app.floatface.core.OwCharacteristic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Harness test proving the :ble toolchain (Robolectric + Android framework on the
 * JVM) runs and can see :core types. Adapter tests are added test-first.
 */
@RunWith(RobolectricTestRunner::class)
class BleHarnessTest {

    @Test
    fun robolectricAndroidFrameworkIsAvailable() {
        val encoded = Base64.encodeToString(byteArrayOf(0x0a, 0x1b), Base64.NO_WRAP)
        assertTrue(encoded.isNotEmpty())
    }

    @Test
    fun coreContractsAreVisibleFromBle() {
        assertEquals("e659f3ff-ea98-11e3-ac10-0800200c9a66", OwCharacteristic.UART_SERIAL_WRITE.uuid)
    }
}
