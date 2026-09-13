package app.floatface.core

/**
 * Unlock-byte helpers (SPEC §3.3 / §4.5.7).
 *
 * IMPLEMENTATION PENDING — to be built test-first by the implementation swarm.
 * Contract:
 *  - [fromHex] parses 0-9a-fA-F; returns null on odd length or any non-hex char.
 *  - [isConfigured] is true iff exactly 20 bytes AND not all-zero.
 *  - XOR-checksum verification is advisory only and lives elsewhere (never gates
 *    a write); length==20 && not-all-zero is the only hard gate.
 */
object UnlockBytes {
    const val LENGTH: Int = 20

    fun fromHex(hex: String): ByteArray? {
        val trimmed = hex.trim()
        if (trimmed.length % 2 != 0) return null
        if (trimmed.any { !it.isHexDigit() }) return null
        return try {
            ByteArray(trimmed.length / 2) { i ->
                val start = i * 2
                trimmed.substring(start, start + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun isConfigured(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size == LENGTH && bytes.any { it.toInt() != 0 }

    /**
     * ADVISORY only: checks the XOR checksum convention (bytes[0..18] XOR == bytes[19]).
     * This must NEVER gate a write or configuration state; length==20 && not-all-zero
     * (see [isConfigured]) is the only hard gate.
     */
    fun xorChecksumOk(bytes: ByteArray): Boolean {
        if (bytes.size != LENGTH) return false
        var xor = 0
        for (i in 0 until LENGTH - 1) {
            xor = xor xor bytes[i].toInt()
        }
        return (xor and 0xFF) == (bytes[LENGTH - 1].toInt() and 0xFF)
    }

    private fun Char.isHexDigit(): Boolean =
        (this in '0'..'9') || (this in 'a'..'f') || (this in 'A'..'F')
}
