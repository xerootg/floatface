package app.floatface.wear.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.floatface.core.BuzzPattern
import app.floatface.core.Haptics

/**
 * [Haptics] adapter (SPEC §4.5.7 / §8) backed by the system [Vibrator]. Every
 * currently-defined [BuzzPattern] is a single ~150ms buzz; add cases as new
 * patterns are introduced.
 */
class VibratorHaptics(context: Context) : Haptics {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    override fun buzz(pattern: BuzzPattern) {
        when (pattern) {
            BuzzPattern.HalfwayWarning -> vibrateOnce(DURATION_MS)
        }
    }

    private fun vibrateOnce(durationMs: Long) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    private companion object {
        const val DURATION_MS = 150L
    }
}
