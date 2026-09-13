package app.floatface.wear.platform

import app.floatface.core.UnlockBytes
import app.floatface.core.UnlockConfig
import app.floatface.wear.BuildConfig

/**
 * [UnlockConfig] adapter (SPEC §10). Reads the per-owner unlock bytes baked
 * into [BuildConfig.UNLOCK_BYTES_HEX] by the `:app` Gradle build (from the
 * gitignored `unlock.properties`, defaulting to the all-zero placeholder).
 * `fromHex` failures and the all-zero placeholder both map to `null`
 * (`UnlockConfig.unlockBytes() == null`), which the reducer surfaces as
 * `ConnectionState.UnlockNotConfigured`.
 */
class BuildConfigUnlockConfig : UnlockConfig {
    override fun unlockBytes(): ByteArray? {
        val bytes = UnlockBytes.fromHex(BuildConfig.UNLOCK_BYTES_HEX)
        return if (UnlockBytes.isConfigured(bytes)) bytes else null
    }
}
