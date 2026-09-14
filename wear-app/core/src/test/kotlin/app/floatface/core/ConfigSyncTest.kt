package app.floatface.core

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigSyncTest {

    private val validHex = "0102030405060708090a0b0c0d0e0f1011121314"

    @Test
    fun `valid hex and mac are both applied to the store`() = runTest {
        val store = FakeBoardConfigStore()
        val applied = ConfigSync.applyPushedConfig(validHex, "AA:BB:CC:DD:EE:FF", store, backgroundScope)
        runCurrent()
        assertTrue(applied.unlockApplied && applied.macApplied)
        assertEquals(validHex, store.config.value.unlockBytesHex)
        assertEquals("AA:BB:CC:DD:EE:FF", store.config.value.bleMac)
    }

    @Test
    fun `invalid hex is ignored but a valid mac still applies`() = runTest {
        val store = FakeBoardConfigStore()
        val applied = ConfigSync.applyPushedConfig("zzzz", "AA:BB:CC:DD:EE:FF", store, backgroundScope)
        runCurrent()
        assertFalse(applied.unlockApplied)
        assertTrue(applied.macApplied)
        assertNull(store.config.value.unlockBytesHex)
        assertEquals("AA:BB:CC:DD:EE:FF", store.config.value.bleMac)
    }

    @Test
    fun `all-zero hex and malformed mac are both rejected`() = runTest {
        val store = FakeBoardConfigStore()
        val applied = ConfigSync.applyPushedConfig("0".repeat(40), "not-a-mac", store, backgroundScope)
        runCurrent()
        assertFalse(applied.anything)
        assertNull(store.config.value.unlockBytesHex)
        assertNull(store.config.value.bleMac)
    }

    @Test
    fun `blank fields leave existing config untouched`() = runTest {
        val store = FakeBoardConfigStore(BoardConfig(unlockBytesHex = validHex, bleMac = "AA:BB:CC:DD:EE:FF"))
        val applied = ConfigSync.applyPushedConfig("  ", null, store, backgroundScope)
        runCurrent()
        assertFalse(applied.anything)
        assertEquals(validHex, store.config.value.unlockBytesHex)
        assertEquals("AA:BB:CC:DD:EE:FF", store.config.value.bleMac)
    }
}
