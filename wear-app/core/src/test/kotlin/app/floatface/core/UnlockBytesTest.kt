package app.floatface.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnlockBytesTest {

    @Test
    fun `fromHex parses valid 40-char hex into 20 bytes`() {
        val hex = "0102030405060708090a0b0c0d0e0f1011121314"
        val bytes = UnlockBytes.fromHex(hex)
        assertNotNull(bytes)
        assertEquals(20, bytes.size)
        assertEquals(0x01, bytes[0].toInt() and 0xFF)
        assertEquals(0x14, bytes[19].toInt() and 0xFF)
    }

    @Test
    fun `fromHex returns null for odd length`() {
        assertNull(UnlockBytes.fromHex("abc"))
    }

    @Test
    fun `fromHex returns null for non-hex characters`() {
        assertNull(UnlockBytes.fromHex("zz00112233445566778899aabbccddeeff0011"))
    }

    @Test
    fun `fromHex trims whitespace`() {
        val hex = "  0102030405060708090a0b0c0d0e0f1011121314  "
        val bytes = UnlockBytes.fromHex(hex)
        assertNotNull(bytes)
        assertEquals(20, bytes.size)
    }

    @Test
    fun `fromHex is case-insensitive`() {
        // Same 40-char (20-byte) value in lower and upper case must parse equal.
        val lower = UnlockBytes.fromHex("0a0b0c0d0e0f101112131415161718191a1b1c1d")
        val upper = UnlockBytes.fromHex("0A0B0C0D0E0F101112131415161718191A1B1C1D")
        assertNotNull(lower)
        assertNotNull(upper)
        assertEquals(20, lower.size)
        assertTrue(lower.contentEquals(upper))
    }

    @Test
    fun `fromHex empty string returns empty byte array`() {
        val bytes = UnlockBytes.fromHex("")
        assertNotNull(bytes)
        assertEquals(0, bytes.size)
    }

    @Test
    fun `isConfigured true for realistic 20 non-zero bytes`() {
        val hex = "aabbccddeeff00112233445566778899aabbccdd"
        val bytes = UnlockBytes.fromHex(hex)
        assertNotNull(bytes)
        assertEquals(20, bytes.size)
        assertTrue(UnlockBytes.isConfigured(bytes))
    }

    @Test
    fun `isConfigured false for all-zero 20 bytes`() {
        val bytes = ByteArray(20)
        assertFalse(UnlockBytes.isConfigured(bytes))
    }

    @Test
    fun `isConfigured false for wrong length`() {
        val bytes = ByteArray(19) { 1 }
        assertFalse(UnlockBytes.isConfigured(bytes))
        val bytesTooLong = ByteArray(21) { 1 }
        assertFalse(UnlockBytes.isConfigured(bytesTooLong))
    }

    @Test
    fun `isConfigured false for null`() {
        assertFalse(UnlockBytes.isConfigured(null))
    }

    @Test
    fun `xorChecksumOk is advisory and does not gate isConfigured`() {
        // 19 bytes of data + a checksum byte that is deliberately wrong.
        val data = ByteArray(19) { (it + 1).toByte() }
        var xor = 0
        for (b in data) xor = xor xor (b.toInt() and 0xFF)
        val correctChecksum = xor.toByte()
        val wrongChecksum = (xor xor 0xFF).toByte()

        val validChecksumBytes = data + correctChecksum
        val invalidChecksumBytes = data + wrongChecksum

        assertTrue(UnlockBytes.xorChecksumOk(validChecksumBytes))
        assertFalse(UnlockBytes.xorChecksumOk(invalidChecksumBytes))

        // isConfigured must be true for both, regardless of checksum validity —
        // length==20 && not-all-zero is the only hard gate.
        assertTrue(UnlockBytes.isConfigured(validChecksumBytes))
        assertTrue(UnlockBytes.isConfigured(invalidChecksumBytes))
    }

    @Test
    fun `xorChecksumOk false for wrong length`() {
        assertFalse(UnlockBytes.xorChecksumOk(ByteArray(5)))
    }
}
