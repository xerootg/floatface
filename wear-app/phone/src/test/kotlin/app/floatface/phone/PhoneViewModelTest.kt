package app.floatface.phone

import app.floatface.phone.sync.ConfigSender
import app.floatface.phone.sync.InstallResult
import app.floatface.phone.sync.SendResult
import app.floatface.phone.sync.WatchAppStatus
import app.floatface.phone.sync.WatchInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val validHex = "0102030405060708090a0b0c0d0e0f1011121314"

    @Before fun setUp() = Dispatchers.setMain(mainDispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeSender(var result: SendResult = SendResult.Sent(1)) : ConfigSender {
        var calls = 0
        var lastHex: String? = null
        var lastMac: String? = null
        override suspend fun send(unlockHex: String?, bleMac: String?): SendResult {
            calls++; lastHex = unlockHex; lastMac = bleMac; return result
        }
    }

    private class FakeInstaller(
        var statusResult: WatchAppStatus = WatchAppStatus.Installed(1),
        var installResult: InstallResult = InstallResult.Launched(1),
    ) : WatchInstaller {
        var statusCalls = 0
        var installCalls = 0
        override suspend fun status(): WatchAppStatus { statusCalls++; return statusResult }
        override suspend fun installOnWatch(): InstallResult { installCalls++; return installResult }
    }

    private fun viewModel(
        sender: ConfigSender = FakeSender(),
        installer: WatchInstaller = FakeInstaller(),
    ) = PhoneViewModel(sender, installer)

    @Test
    fun `canSend requires valid unlock bytes, MAC optional`() {
        val vm = viewModel()
        assertFalse(vm.state.value.canSend)

        vm.onUnlockHexChange(validHex)
        assertTrue("valid unlock, blank MAC -> can send", vm.state.value.canSend)

        vm.onBleMacChange("not-a-mac")
        assertFalse("invalid MAC blocks send", vm.state.value.canSend)

        vm.onBleMacChange("AA:BB:CC:DD:EE:FF")
        assertTrue(vm.state.value.canSend)
    }

    @Test
    fun `send forwards config to the sender and reports Sent`() = runTest(mainDispatcher) {
        val sender = FakeSender(SendResult.Sent(2))
        val vm = viewModel(sender)
        vm.onUnlockHexChange(validHex)
        vm.onBleMacChange("AA:BB:CC:DD:EE:FF")

        vm.send()
        advanceUntilIdle()

        assertEquals(1, sender.calls)
        assertEquals(validHex, sender.lastHex)
        assertEquals("AA:BB:CC:DD:EE:FF", sender.lastMac)
        assertEquals(SendStatus.Sent(2), vm.state.value.status)
    }

    @Test
    fun `blank MAC is sent as null (match any board)`() = runTest(mainDispatcher) {
        val sender = FakeSender()
        val vm = viewModel(sender)
        vm.onUnlockHexChange(validHex)
        vm.send()
        advanceUntilIdle()
        assertEquals(null, sender.lastMac)
    }

    @Test
    fun `no connected watch surfaces NoWatch`() = runTest(mainDispatcher) {
        val vm = viewModel(FakeSender(SendResult.NoWatch))
        vm.onUnlockHexChange(validHex)
        vm.send()
        advanceUntilIdle()
        assertEquals(SendStatus.NoWatch, vm.state.value.status)
    }

    @Test
    fun `send is a no-op while the form is invalid`() = runTest(mainDispatcher) {
        val sender = FakeSender()
        val vm = viewModel(sender)
        vm.send() // no unlock entered
        advanceUntilIdle()
        assertEquals(0, sender.calls)
    }

    @Test
    fun `refreshWatchStatus reflects the installer and enables install when missing`() =
        runTest(mainDispatcher) {
            val installer = FakeInstaller(statusResult = WatchAppStatus.NotInstalled(listOf("node-1")))
            val vm = viewModel(installer = installer)

            vm.refreshWatchStatus()
            advanceUntilIdle()

            assertEquals(1, installer.statusCalls)
            assertEquals(WatchAppStatus.NotInstalled(listOf("node-1")), vm.state.value.watchAppStatus)
            assertTrue("install offered when the watch lacks the app", vm.state.value.canInstall)
        }

    @Test
    fun `installed watch does not offer install`() = runTest(mainDispatcher) {
        val vm = viewModel(installer = FakeInstaller(statusResult = WatchAppStatus.Installed(1)))
        vm.refreshWatchStatus()
        advanceUntilIdle()
        assertFalse(vm.state.value.canInstall)
    }

    @Test
    fun `installWatchApp launches the store and re-checks status`() = runTest(mainDispatcher) {
        val installer = FakeInstaller(
            statusResult = WatchAppStatus.NotInstalled(listOf("node-1")),
            installResult = InstallResult.Launched(1),
        )
        val vm = viewModel(installer = installer)

        vm.installWatchApp()
        advanceUntilIdle()

        assertEquals(1, installer.installCalls)
        assertEquals(1, installer.statusCalls) // re-queried after launching
        assertTrue(vm.state.value.installMessage!!.contains("Play Store"))
    }
}
