package app.floatface.wear.platform

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DataStoreBoardConfigStore] seed/precedence logic (SPEC §10). The compile-time
 * default seeds the unlock hex only when it parses to a configured value; the
 * all-zero placeholder must NOT read as configured. (Runtime set/clear round-trips
 * go through DataStore file IO, which is timing-nondeterministic in unit tests —
 * that override/clear behavior is exercised via FakeBoardConfigStore in the domain
 * reducer/controller tests instead.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoardConfigStoreTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun newStore(scope: kotlinx.coroutines.CoroutineScope, seed: String?): DataStoreBoardConfigStore {
        val n = counter.incrementAndGet()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            ctx.preferencesDataStoreFile("board_config_test_$n")
        }
        return DataStoreBoardConfigStore(dataStore, scope, seed)
    }

    @Test
    fun `seeds unlock hex from a configured compile-time default`() = runTest {
        val store = newStore(backgroundScope, "0102030405060708090a0b0c0d0e0f1011121314")
        assertEquals("0102030405060708090a0b0c0d0e0f1011121314", store.config.value.unlockBytesHex)
        assertNull(store.config.value.bleMac)
    }

    @Test
    fun `all-zero placeholder default is not treated as configured`() = runTest {
        val store = newStore(backgroundScope, "0".repeat(40))
        assertNull(store.config.value.unlockBytesHex)
    }

    @Test
    fun `absent default leaves config empty`() = runTest {
        val store = newStore(backgroundScope, null)
        assertNull(store.config.value.unlockBytesHex)
        assertNull(store.config.value.bleMac)
    }

    private companion object {
        val counter = AtomicInteger(0)
    }
}
