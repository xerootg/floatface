package app.floatface.wear

import android.app.Application

/**
 * Application entry point (SPEC §13). Owns the single [AppContainer] for the
 * process lifetime, wiring the ports (OnewheelTransport, RideRecorder, Clock,
 * Ticker, etc.) and the application-scoped OnewheelController. Both
 * [app.floatface.wear.service.RideService] and [MainActivity] read
 * [container] rather than constructing their own adapters/controller.
 */
class FloatfaceApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
