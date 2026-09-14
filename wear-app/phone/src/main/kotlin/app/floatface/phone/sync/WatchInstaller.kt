package app.floatface.phone.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import app.floatface.core.ConfigSync
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.tasks.await

/**
 * Whether the watch app is present on the currently-connected watch(es).
 *
 * Detected via the static Data Layer capability [ConfigSync.WATCH_APP_CAPABILITY],
 * which the watch app advertises the moment it is installed — so this is accurate
 * without the watch app ever having been launched.
 */
sealed interface WatchAppStatus {
    /** Not yet queried. */
    data object Unknown : WatchAppStatus

    /** No watch is connected to this phone at all. */
    data object NoWatch : WatchAppStatus

    /** The watch app is installed on every connected watch. */
    data class Installed(val nodeCount: Int) : WatchAppStatus

    /** A watch is connected but does not have the watch app. */
    data class NotInstalled(val nodeIds: List<String>) : WatchAppStatus
}

/** Outcome of asking the watch to open the app's Play Store listing. */
sealed interface InstallResult {
    /** Play Store opened on this many watch(es); the user taps Install there. */
    data class Launched(val nodeCount: Int) : InstallResult

    /** Nothing to do — the watch app is already installed (or no watch missing it). */
    data object NoTarget : InstallResult

    data class Error(val message: String) : InstallResult
}

/**
 * Detects whether the watch app is installed and, when it isn't, helps the user
 * install it (SPEC §10a.1).
 *
 * Architectural note: Wear OS 3+ (Pixel Watch) has **no** API for a phone app to
 * transfer and silently install an arbitrary APK onto the watch — the legacy
 * embedded-app (`wearApp`) mechanism was removed after Android Wear 1.x. The two
 * supported paths are (a) Google Play, where the watch app auto-installs alongside
 * (or is one tap away on the watch), and (b) `adb install` for sideloading a
 * personal build. This helper implements the best supported "from the phone" flow:
 * open the watch app's Play Store listing *on the watch* via [RemoteActivityHelper]
 * so the user installs it with a single tap. For a sideloaded build not on Play,
 * use adb (see README).
 */
interface WatchInstaller {
    suspend fun status(): WatchAppStatus

    /** Opens the watch app's Play Store listing on any connected watch that lacks it. */
    suspend fun installOnWatch(): InstallResult
}

class WearWatchInstaller(private val context: Context) : WatchInstaller {

    override suspend fun status(): WatchAppStatus {
        val connected = Wearable.getNodeClient(context).connectedNodes.await()
        if (connected.isEmpty()) return WatchAppStatus.NoWatch

        val capableIds = capableNodeIds()
        val missing = connected.filter { it.id !in capableIds }
        return if (missing.isEmpty()) {
            WatchAppStatus.Installed(connected.size)
        } else {
            WatchAppStatus.NotInstalled(missing.map { it.id })
        }
    }

    override suspend fun installOnWatch(): InstallResult {
        val connected = Wearable.getNodeClient(context).connectedNodes.await()
        val capableIds = capableNodeIds()
        val targets = connected.filter { it.id !in capableIds }
        if (targets.isEmpty()) return InstallResult.NoTarget

        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse("market://details?id=${ConfigSync.WATCH_APP_PACKAGE}"))

        val helper = RemoteActivityHelper(context)
        var launched = 0
        var lastError: String? = null
        for (node in targets) {
            runCatching { helper.startRemoteActivity(intent, node.id).await() }
                .onSuccess { launched++ }
                .onFailure { lastError = it.message ?: it::class.simpleName }
        }
        return when {
            launched > 0 -> InstallResult.Launched(launched)
            else -> InstallResult.Error(lastError ?: "Could not open Play Store on the watch")
        }
    }

    private suspend fun capableNodeIds(): Set<String> =
        Wearable.getCapabilityClient(context)
            .getCapability(ConfigSync.WATCH_APP_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
            .map { it.id }
            .toSet()
}
