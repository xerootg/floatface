package app.floatface.wear.platform

import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SPEC §10: a clean checkout / CI build has no `unlock.properties`, so the
 * Gradle build falls back to the all-zero placeholder for
 * `BuildConfig.UNLOCK_BYTES_HEX`. [BuildConfigUnlockConfig] must map that
 * (and any other non-configured value) to `null`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockConfigTest {

    @Test
    fun allZeroPlaceholderMapsToNull() {
        val config = BuildConfigUnlockConfig()

        assertNull(config.unlockBytes())
    }
}
