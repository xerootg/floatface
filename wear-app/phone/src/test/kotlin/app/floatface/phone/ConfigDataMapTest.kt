package app.floatface.phone

import app.floatface.core.ConfigSync
import app.floatface.phone.sync.ConfigDataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDataMapTest {

    @Test
    fun `includes provided fields and always a nonce`() {
        val map = ConfigDataMap.build("0102", "AA:BB:CC:DD:EE:FF", nonce = 42L)
        assertEquals("0102", map[ConfigSync.KEY_UNLOCK_HEX])
        assertEquals("AA:BB:CC:DD:EE:FF", map[ConfigSync.KEY_BLE_MAC])
        assertEquals("42", map[ConfigSync.KEY_NONCE])
    }

    @Test
    fun `omits blank fields but keeps the nonce`() {
        val map = ConfigDataMap.build(unlockHex = "  ", bleMac = null, nonce = 7L)
        assertFalse(map.containsKey(ConfigSync.KEY_UNLOCK_HEX))
        assertFalse(map.containsKey(ConfigSync.KEY_BLE_MAC))
        assertTrue(map.containsKey(ConfigSync.KEY_NONCE))
    }

    @Test
    fun `distinct nonces make otherwise-identical pushes differ`() {
        val a = ConfigDataMap.build("0102", null, nonce = 1L)
        val b = ConfigDataMap.build("0102", null, nonce = 2L)
        assertTrue(a != b)
    }
}
