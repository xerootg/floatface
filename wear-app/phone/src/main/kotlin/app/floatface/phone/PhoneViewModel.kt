package app.floatface.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.floatface.core.BoardConfig
import app.floatface.core.UnlockBytes
import app.floatface.phone.sync.ConfigSender
import app.floatface.phone.sync.InstallResult
import app.floatface.phone.sync.SendResult
import app.floatface.phone.sync.WatchAppStatus
import app.floatface.phone.sync.WatchInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Status of the most recent push attempt. */
sealed interface SendStatus {
    data object Idle : SendStatus
    data object Sending : SendStatus
    data class Sent(val nodeCount: Int) : SendStatus
    data object NoWatch : SendStatus
    data class Failed(val message: String) : SendStatus
}

/** Companion-app form state. Validity is derived from the shared :core rules. */
data class PhoneUiState(
    val unlockHex: String = "",
    val bleMac: String = "",
    val status: SendStatus = SendStatus.Idle,
    val watchAppStatus: WatchAppStatus = WatchAppStatus.Unknown,
    val installMessage: String? = null,
) {
    /** Unlock bytes must parse to a configured value (20 bytes, not all-zero). */
    val unlockValid: Boolean get() = UnlockBytes.isConfigured(UnlockBytes.fromHex(unlockHex))

    /** MAC is optional ("any board"); if present it must be well-formed. */
    val macValid: Boolean get() = bleMac.isBlank() || BoardConfig.isValidMac(bleMac)

    val canSend: Boolean get() = unlockValid && macValid && status != SendStatus.Sending

    /** Show the "Install on watch" affordance only when a watch lacks the app. */
    val canInstall: Boolean get() = watchAppStatus is WatchAppStatus.NotInstalled
}

/**
 * Drives the companion phone UI (SPEC §10): edits the unlock hex + BLE MAC,
 * validates via :core, and pushes to the watch through a [ConfigSender].
 */
class PhoneViewModel(
    private val sender: ConfigSender,
    private val installer: WatchInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(PhoneUiState())
    val state: StateFlow<PhoneUiState> = _state.asStateFlow()

    fun onUnlockHexChange(value: String) = _state.update { it.copy(unlockHex = value, status = SendStatus.Idle) }

    fun onBleMacChange(value: String) = _state.update { it.copy(bleMac = value, status = SendStatus.Idle) }

    /** Query whether the watch app is installed on the connected watch(es). */
    fun refreshWatchStatus() {
        viewModelScope.launch {
            val status = runCatching { installer.status() }.getOrDefault(WatchAppStatus.Unknown)
            _state.update { it.copy(watchAppStatus = status) }
        }
    }

    /** Open the watch app's Play Store listing on the watch so the user can install it. */
    fun installWatchApp() {
        viewModelScope.launch {
            val message = when (
                val result = runCatching { installer.installOnWatch() }
                    .getOrElse { InstallResult.Error(it.message ?: "Unknown error") }
            ) {
                is InstallResult.Launched ->
                    "Opened Play Store on ${result.nodeCount} watch(es) — tap Install there."
                InstallResult.NoTarget -> "Watch app already installed."
                is InstallResult.Error -> "Couldn't open Play Store on the watch: ${result.message}"
            }
            _state.update { it.copy(installMessage = message) }
            refreshWatchStatus()
        }
    }

    fun send() {
        val current = _state.value
        if (!current.canSend) return
        _state.update { it.copy(status = SendStatus.Sending) }
        viewModelScope.launch {
            val status = runCatching {
                sender.send(current.unlockHex.trim(), current.bleMac.trim().ifBlank { null })
            }.fold(
                onSuccess = { result ->
                    when (result) {
                        is SendResult.Sent -> SendStatus.Sent(result.nodeCount)
                        SendResult.NoWatch -> SendStatus.NoWatch
                        is SendResult.Error -> SendStatus.Failed(result.message)
                    }
                },
                onFailure = { e -> SendStatus.Failed(e.message ?: "Unknown error") },
            )
            _state.update { it.copy(status = status) }
        }
    }

    class Factory(
        private val sender: ConfigSender,
        private val installer: WatchInstaller,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(PhoneViewModel::class.java)) { "Unknown ViewModel $modelClass" }
            return PhoneViewModel(sender, installer) as T
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PhoneViewModel::class.java)) { "Unknown ViewModel $modelClass" }
            return PhoneViewModel(sender, installer) as T
        }
    }
}
