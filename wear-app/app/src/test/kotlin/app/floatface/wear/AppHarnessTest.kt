package app.floatface.wear

import app.floatface.core.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Harness test proving the :app toolchain (Robolectric + Compose deps + :core /
 * :ble on the classpath) runs. Real ViewModel / screen / recorder tests are added
 * test-first.
 */
@RunWith(RobolectricTestRunner::class)
class AppHarnessTest {

    @Test
    fun buildConfigCarriesUnlockBytesField() {
        // Falls back to the all-zero placeholder without unlock.properties.
        assertEquals(40, BuildConfig.UNLOCK_BYTES_HEX.length)
    }

    @Test
    fun coreTypesAreVisibleFromApp() {
        assertTrue(UiState().ride.distanceMiles == 0.0)
    }
}
