package app.floatface.core

/**
 * Named byte vectors for decoder tests, per SPEC §6.2.
 */

/** Battery level: 0x0040 = 64 (percent). */
val BATTERY_64: ByteArray = byteArrayOf(0x00, 0x40)

/** Motor controller temp: A=0x20 (32C -> 90F), B=0x1b (27C -> 81F). */
val MOTOR_TEMP_32_27: ByteArray = byteArrayOf(0x20, 0x1b)

/** Motor controller temp, negative Celsius: A=0xfb (-5C -> 23F), B=0xfe (-2C -> 28F). */
val MOTOR_TEMP_NEG: ByteArray = byteArrayOf(0xfb.toByte(), 0xfe.toByte())

/** Battery low temp: A=0x1d (29C), B=0x1c (28C). */
val BATTERY_LOW_TEMP_29_28: ByteArray = byteArrayOf(0x1d, 0x1c)

/** Generic big-endian u16 = 2048 (0x0800), used for odometer/amp-hour style fields. */
val U16_2048: ByteArray = byteArrayOf(0x08, 0x00)

// NOTE: byteArrayOf(0x82.toByte(), 0x19) is WRONG for a signed-byte temperature pair —
// 0x82 as a signed byte decodes to -126 (C), not a plausible temperature. Do not use it
// as a "normal" temperature fixture.
