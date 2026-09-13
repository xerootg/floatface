package app.floatface.wear

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.floatface.ble.AndroidOnewheelTransport
import app.floatface.core.BoardConfigStore
import app.floatface.core.OnewheelController
import app.floatface.core.SettingsStore
import app.floatface.wear.platform.CoroutineTicker
import app.floatface.wear.platform.DataStoreBoardConfigStore
import app.floatface.wear.platform.DataStoreSettingsStore
import app.floatface.wear.platform.LogcatLogger
import app.floatface.wear.platform.SystemClockAdapter
import app.floatface.wear.platform.VibratorHaptics
import app.floatface.wear.record.HealthServicesRideRecorder
import app.floatface.wear.record.JsonlRideLog
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Composition root (SPEC §13): builds and holds every port adapter plus the
 * single application-scoped [OnewheelController]. One instance lives for the
 * process lifetime, owned by [FloatfaceApplication]; the foreground service
 * ([app.floatface.wear.service.RideService]) and [MainActivity] both read the
 * same [controller] rather than constructing their own.
 *
 * [applicationScope] is a single dedicated background thread (SupervisorJob +
 * a single-thread dispatcher) so the whole domain (controller + transport +
 * ticker) stays effectively single-threaded, matching [OnewheelController]'s
 * single-consumer-coroutine design, and keeps ticking through Activity
 * lifecycle changes / Doze (SPEC §9).
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Long-lived, service-scoped coroutine scope (SPEC §9): survives Activity recreation. */
    val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    val clock = SystemClockAdapter()
    val ticker = CoroutineTicker(applicationScope)
    val haptics = VibratorHaptics(appContext)
    val logger = LogcatLogger()

    private val preferencesDataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = applicationScope) {
            appContext.preferencesDataStoreFile("floatface_settings")
        }
    val settingsStore: SettingsStore = DataStoreSettingsStore(preferencesDataStore, applicationScope)

    /**
     * Runtime board config (unlock bytes + BLE MAC), persisted in DataStore and
     * editable on-watch (SPEC §10). Seeded from the optional compile-time
     * BuildConfig default; a runtime value always overrides it.
     */
    val boardConfigStore: BoardConfigStore =
        DataStoreBoardConfigStore(preferencesDataStore, applicationScope, BuildConfig.UNLOCK_BYTES_HEX)

    private val ridesDir = File(appContext.filesDir, "rides")
    val rideLog = JsonlRideLog(ridesDir)
    val rideRecorder = HealthServicesRideRecorder(appContext, rideLog, clock, logger)

    val transport = AndroidOnewheelTransport(appContext, logger, applicationScope)

    /** The single application-scoped controller; started exactly once via [ensureStarted] (SPEC §9). */
    val controller: OnewheelController = OnewheelController(
        transport = transport,
        ticker = ticker,
        clock = clock,
        haptics = haptics,
        recorder = rideRecorder,
        boardConfig = boardConfigStore,
        logger = logger,
        scope = applicationScope,
    )

    @Volatile private var started = false

    /**
     * Starts [controller] (which begins scanning and the merged-event loop) the
     * first time this is called; a no-op on any later call. Both the foreground
     * service and (if launched independently, e.g. in tests) the Activity call
     * this, so whichever comes up first performs the one real [OnewheelController.start].
     */
    @Synchronized
    fun ensureStarted() {
        if (started) return
        started = true
        controller.start()
    }
}
