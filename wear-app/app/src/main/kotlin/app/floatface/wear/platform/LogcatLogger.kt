package app.floatface.wear.platform

import android.util.Log
import app.floatface.core.Logger

/**
 * [Logger] adapter (SPEC §4.5.7 / §13a) backed by `android.util.Log`.
 *
 * SECURITY: never pass unlock bytes (raw or hex) to any of these calls — no
 * call site in this codebase should ever log [app.floatface.core.UnlockBytes]
 * output or `UnlockConfig.unlockBytes()`.
 */
class LogcatLogger : Logger {
    override fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    override fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
    }
}
