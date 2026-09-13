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

    fun fromHex(hex: String): ByteArray? = TODO("implement test-first (SPEC §3.3)")

    fun isConfigured(bytes: ByteArray?): Boolean = TODO("implement test-first (SPEC §3.3)")
}
