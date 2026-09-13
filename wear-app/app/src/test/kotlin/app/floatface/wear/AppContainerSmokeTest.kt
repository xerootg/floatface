package app.floatface.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test for the composition root (SPEC §13): building [AppContainer]
 * against a real (Robolectric) [Context] must not throw, and every held
 * singleton — in particular the application-scoped controller — must be
 * non-null and ready to use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppContainerSmokeTest {

    @Test
    fun buildingAppContainerDoesNotThrowAndExposesAController() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val container = AppContainer(context)

        assertNotNull(container.controller)
        assertNotNull(container.transport)
        assertNotNull(container.rideRecorder)
        assertNotNull(container.rideLog)
        assertNotNull(container.settingsStore)
        assertNotNull(container.clock)
        assertNotNull(container.ticker)
        assertNotNull(container.haptics)
        assertNotNull(container.logger)
        assertNotNull(container.boardConfigStore)
    }
}
