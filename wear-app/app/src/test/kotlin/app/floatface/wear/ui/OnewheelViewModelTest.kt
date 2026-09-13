package app.floatface.wear.ui

import app.floatface.core.FakeClock
import app.floatface.core.FakeHaptics
import app.floatface.core.FakeLogger
import app.floatface.core.FakeRideRecorder
import app.floatface.core.FakeTicker
import app.floatface.core.FakeTransport
import app.floatface.core.FakeBoardConfigStore
import app.floatface.core.OnewheelController
import app.floatface.core.RecordingState
import app.floatface.core.TransportEvent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [OnewheelViewModel] is a pure pass-through onto a real
 * [OnewheelController] built from :core's testFixtures fakes: uiState reflects
 * controller state, and each forwarding function actually reaches the
 * controller (observed as a uiState change or a recorded fake call).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnewheelViewModelTest {

    private fun newController(scope: TestScope): Triple<OnewheelController, FakeTransport, FakeRideRecorder> {
        val transport = FakeTransport()
        val recorder = FakeRideRecorder()
        val controller = OnewheelController(
            transport = transport,
            ticker = FakeTicker(),
            clock = FakeClock(),
            haptics = FakeHaptics(),
            recorder = recorder,
            boardConfig = FakeBoardConfigStore.withUnlockBytes(ByteArray(20) { 1 }),
            logger = FakeLogger(),
            scope = scope,
        )
        return Triple(controller, transport, recorder)
    }

    @Test
    fun uiStateExposesControllerUiState() = runTest {
        val (controller, _, _) = newController(TestScope(StandardTestDispatcher(testScheduler)))
        val viewModel = OnewheelViewModel(controller, FakeBoardConfigStore())

        assertEquals(controller.uiState.value, viewModel.uiState.value)
    }

    @Test
    fun toggleRecordingForwardsIntentToController() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val (controller, _, recorder) = newController(scope)
        val viewModel = OnewheelViewModel(controller, FakeBoardConfigStore())

        controller.start()
        scope.testScheduler.runCurrent()

        assertEquals(RecordingState.Idle, recorder.state.value)

        viewModel.toggleRecording()
        scope.testScheduler.runCurrent()

        // The controller forwarded ToggleRecording to the (fake) recorder, which
        // flipped from Idle to Recording — observable both directly on the fake
        // and reflected through uiState.ride.recording.
        assertEquals(RecordingState.Recording, recorder.state.value)
        assertEquals(RecordingState.Recording, viewModel.uiState.value.ride.recording)
    }

    @Test
    fun nextPageForwardsIntentToController() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val (controller, _, _) = newController(scope)
        val viewModel = OnewheelViewModel(controller, FakeBoardConfigStore())

        controller.start()
        scope.testScheduler.runCurrent()

        val startingPage = viewModel.uiState.value.page

        viewModel.nextPage()
        scope.testScheduler.runCurrent()

        assertEquals(startingPage + 1, viewModel.uiState.value.page)

        viewModel.prevPage()
        scope.testScheduler.runCurrent()

        assertEquals(startingPage, viewModel.uiState.value.page)

        viewModel.selectPage(2)
        scope.testScheduler.runCurrent()

        assertEquals(2, viewModel.uiState.value.page)
    }

    @Test
    fun retryAndShutdownForwardToController() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val (controller, transport, _) = newController(scope)
        val viewModel = OnewheelViewModel(controller, FakeBoardConfigStore())

        controller.start()
        scope.testScheduler.runCurrent()

        viewModel.shutdown()
        scope.testScheduler.runCurrent()

        // Shutdown drives the machine towards disconnect/close (SPEC §4.5.6):
        // confirm the transport actually observed teardown activity.
        assertNotNull(transport)
        assertEquals(true, transport.closeGattCalls.isNotEmpty() || transport.disconnectCalls.isNotEmpty())

        // Retry is at minimum forwarded without throwing, whatever the current state.
        viewModel.retry()
        scope.testScheduler.runCurrent()
    }

    @Test
    fun factoryCreatesViewModelWrappingController() = runTest {
        val (controller, _, _) = newController(TestScope(StandardTestDispatcher(testScheduler)))
        val factory = OnewheelViewModel.Factory(controller, FakeBoardConfigStore())

        val viewModel = factory.create(OnewheelViewModel::class.java)

        assertEquals(controller.uiState.value, viewModel.uiState.value)
    }
}
