package app.floatface.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import app.floatface.core.OnewheelController
import app.floatface.core.UiState
import app.floatface.core.UserIntent
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin UI-facing wrapper around [OnewheelController] (SPEC §8, §13). The
 * controller is injected — this class never constructs one — so ports and
 * wiring stay owned by whatever assembles the controller (the Application /
 * a DI entry point).
 */
class OnewheelViewModel(private val controller: OnewheelController) : ViewModel() {

    val uiState: StateFlow<UiState> = controller.uiState

    fun toggleRecording() = controller.submit(UserIntent.ToggleRecording)

    fun nextPage() = controller.submit(UserIntent.NextPage)

    fun prevPage() = controller.submit(UserIntent.PrevPage)

    fun selectPage(index: Int) = controller.submit(UserIntent.SelectPage(index))

    fun retry() = controller.submit(UserIntent.Retry)

    fun shutdown() = controller.submit(UserIntent.Shutdown)

    class Factory(private val controller: OnewheelController) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(OnewheelViewModel::class.java)) {
                "Unknown ViewModel class $modelClass"
            }
            return OnewheelViewModel(controller) as T
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(OnewheelViewModel::class.java)) {
                "Unknown ViewModel class $modelClass"
            }
            return OnewheelViewModel(controller) as T
        }
    }
}
