package app.floatface.core

import app.cash.turbine.test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the effect-runner ([OnewheelController]) using the
 * fakes in :core testFixtures, plus a small hand-scripted [ConnectionStateMachine]
 * double so these tests exercise the CONTROLLER's plumbing (command dispatch,
 * merged-event ordering, telemetry-derived effects) independently of the real
 * reducer's connection-protocol business logic, which is table-tested on its own
 * (SPEC §4.4: "controller via an integration test with FakeTransport + FakeTicker
 * + StandardTestDispatcher").
 */
class OnewheelControllerTest {

    private val unlockBytes = ByteArray(UnlockBytes.LENGTH) { (it + 1).toByte() }
    private val deviceId = "AA:BB:CC:DD:EE:FF"

    /** A minimal, fully-controlled connection state machine for controller-plumbing tests. */
    private inner class ScriptedMachine : ConnectionStateMachine {
        override fun reduce(state: ConnectionState, event: Event): Reduction = when (event) {
            is Event.Transport -> when (val te = event.event) {
                is TransportEvent.DeviceFound ->
                    Reduction(ConnectionState.Connecting, listOf(Command.StopScan, Command.Connect(te.deviceId)))

                TransportEvent.Connected ->
                    Reduction(
                        ConnectionState.Connected,
                        listOf(Command.WriteUnlock(unlockBytes), Command.StartKeepalive(12_000L)),
                    )

                is TransportEvent.Disconnected ->
                    if (state == ConnectionState.ShuttingDown) {
                        Reduction(ConnectionState.ShuttingDown)
                    } else {
                        Reduction(ConnectionState.Scanning, listOf(Command.StopKeepalive, Command.StartScan))
                    }

                else -> Reduction(state)
            }

            Event.KeepaliveTick ->
                if (state == ConnectionState.Connected) {
                    Reduction(state, listOf(Command.WriteUnlock(unlockBytes)))
                } else {
                    Reduction(state)
                }

            is Event.User ->
                if (event.intent == UserIntent.Shutdown) {
                    Reduction(
                        ConnectionState.ShuttingDown,
                        listOf(Command.StopKeepalive, Command.Disconnect, Command.CloseGatt),
                    )
                } else {
                    Reduction(state)
                }

            else -> Reduction(state)
        }
    }

    private class Harness(scope: TestScope) {
        val transport = FakeTransport()
        val ticker = FakeTicker()
        val clock = FakeClock()
        val haptics = FakeHaptics()
        val recorder = FakeRideRecorder()
        val unlockConfig = FakeUnlockConfig(ByteArray(UnlockBytes.LENGTH) { (it + 1).toByte() })
        val logger = FakeLogger()
        val backgroundScope = scope.backgroundScope
    }

    private fun runControllerTest(block: suspend TestScope.(Harness, OnewheelController) -> Unit) = runTest {
        val harness = Harness(this)
        val controller = OnewheelController(
            transport = harness.transport,
            ticker = harness.ticker,
            clock = harness.clock,
            haptics = harness.haptics,
            recorder = harness.recorder,
            unlockConfig = harness.unlockConfig,
            logger = harness.logger,
            scope = harness.backgroundScope,
            machine = ScriptedMachine(),
        )
        block(harness, controller)
    }

    @Test
    fun `happy path drives Scanning through Connecting to Connected`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            assertEquals(ConnectionState.Scanning, awaitItem().connection)

            controller.start()
            runCurrent()
            assertEquals(1, harness.transport.startScanCalls.size)

            harness.transport.emit(TransportEvent.DeviceFound(deviceId, "Onewheel", emptyList()))
            runCurrent()
            assertEquals(ConnectionState.Connecting, expectMostRecentItem().connection)
            assertEquals(listOf(deviceId), harness.transport.connected)

            harness.transport.emit(TransportEvent.Connected)
            runCurrent()
            assertEquals(ConnectionState.Connected, expectMostRecentItem().connection)
            assertEquals(1, harness.transport.writes.size)
            assertTrue(harness.transport.writes[0].contentEquals(unlockBytes))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `keepalive re-writes unlock bytes on every tick and only ever writes unlock bytes`() =
        runControllerTest { harness, controller ->
            controller.start()
            runCurrent()
            harness.transport.emit(TransportEvent.DeviceFound(deviceId, null, emptyList()))
            harness.transport.emit(TransportEvent.Connected)
            runCurrent()

            assertEquals(1, harness.transport.writes.size) // the initial unlock write on Connected

            // FakeTicker doesn't auto-repeat: firing the scheduled keepalive task simulates cadence.
            repeat(3) {
                harness.ticker.fireLast()
                runCurrent()
            }

            assertEquals(4, harness.transport.writes.size)
            harness.transport.writes.forEach { w -> assertTrue(w.contentEquals(unlockBytes)) }
        }

    @Test
    fun `CharacteristicChanged BATTERY_LEVEL decodes into telemetry`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            runCurrent()
            skipItems(1)

            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(0x00, 0x40)))
            runCurrent()
            assertEquals(64, expectMostRecentItem().telemetry.batteryLevel)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ToggleRecording then SPEED_RPM samples accumulate distance`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            runCurrent()
            skipItems(1)

            controller.submit(UserIntent.ToggleRecording)
            runCurrent()
            assertEquals(RecordingState.Recording, expectMostRecentItem().ride.recording)
            assertEquals(1, harness.recorder.startCalls.size)

            val rpm = 6000
            fun rpmBytes(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

            harness.clock.elapsed = 0L
            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.SPEED_RPM, rpmBytes(rpm)))
            runCurrent()
            // First sample after reset() integrates zero.
            assertEquals(0.0, expectMostRecentItem().ride.distanceMiles, 1e-9)

            harness.clock.elapsed = 3_600_000L // +1 hour
            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.SPEED_RPM, rpmBytes(rpm)))
            runCurrent()

            val expectedMph = RpmSpeed.toMph(rpm, BoardModel.GT.tireDiameterInches)
            assertEquals(expectedMph, expectMostRecentItem().ride.distanceMiles, 1e-6)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `halfway warning buzzes exactly once per ride`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            runCurrent()
            skipItems(1)

            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(0x00, 100)))
            runCurrent()
            expectMostRecentItem()

            controller.submit(UserIntent.ToggleRecording) // startBattery = 100
            runCurrent()
            expectMostRecentItem()

            assertEquals(0, harness.haptics.buzzes.size)

            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(0x00, 50)))
            runCurrent()
            var state = expectMostRecentItem()
            assertEquals(1, harness.haptics.buzzes.size)
            assertTrue(state.ride.halfwayWarningActive)

            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(0x00, 40)))
            runCurrent()
            state = expectMostRecentItem()
            assertEquals(1, harness.haptics.buzzes.size, "must latch, not re-fire")
            assertTrue(state.ride.halfwayWarningActive)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Shutdown tears down without rescanning`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            runCurrent()
            skipItems(1)

            harness.transport.emit(TransportEvent.DeviceFound(deviceId, null, emptyList()))
            harness.transport.emit(TransportEvent.Connected)
            runCurrent()
            assertEquals(ConnectionState.Connected, expectMostRecentItem().connection)
            val startScansSoFar = harness.transport.startScanCalls.size

            controller.submit(UserIntent.Shutdown)
            runCurrent()
            assertEquals(ConnectionState.ShuttingDown, expectMostRecentItem().connection)
            assertEquals(1, harness.transport.disconnectCalls.size)
            assertEquals(1, harness.transport.closeGattCalls.size)

            // The resulting Disconnected must be a no-op: state stays ShuttingDown
            // (so no new UI emission) and there is no rescan.
            harness.transport.emit(TransportEvent.Disconnected(GattStatus.SUCCESS))
            runCurrent()
            expectNoEvents()
            assertEquals(ConnectionState.ShuttingDown, controller.uiState.value.connection)
            assertEquals(startScansSoFar, harness.transport.startScanCalls.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Disconnected resets live telemetry but keeps ride state`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            runCurrent()
            skipItems(1)

            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(0x00, 80)))
            runCurrent()
            expectMostRecentItem()

            controller.submit(UserIntent.ToggleRecording)
            runCurrent()
            expectMostRecentItem()

            harness.transport.emit(TransportEvent.Disconnected(GattStatus.SUCCESS))
            runCurrent()
            val state = expectMostRecentItem()
            assertNull(state.telemetry.batteryLevel)
            assertEquals(RecordingState.Recording, state.ride.recording)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `page navigation wraps within 0 to 3`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            runCurrent()
            skipItems(1)

            controller.submit(UserIntent.PrevPage) // wraps 0 -> 3
            runCurrent()
            assertEquals(3, expectMostRecentItem().page)

            controller.submit(UserIntent.NextPage)
            runCurrent()
            assertEquals(0, expectMostRecentItem().page)

            controller.submit(UserIntent.SelectPage(99))
            runCurrent()
            assertEquals(3, expectMostRecentItem().page)

            controller.submit(UserIntent.SelectPage(-5))
            runCurrent()
            assertEquals(0, expectMostRecentItem().page)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ToggleRecording again stops recording`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            runCurrent()
            skipItems(1)

            controller.submit(UserIntent.ToggleRecording)
            runCurrent()
            assertEquals(RecordingState.Recording, expectMostRecentItem().ride.recording)

            controller.submit(UserIntent.ToggleRecording)
            runCurrent()
            assertEquals(RecordingState.Idle, expectMostRecentItem().ride.recording)
            assertEquals(1, harness.recorder.stopCalls.size)
            assertFalse(harness.recorder.startCalls.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // Regression: the scan timeout must be re-armed on every rescan (not only the
    // first scan window), and the keepalive ticker must be torn down on a drop.
    @Test
    fun `rescan re-arms scan timeout and cancels keepalive`() = runControllerTest { harness, controller ->
        controller.start()
        runCurrent()
        // Initial scan window is armed exactly once.
        assertEquals(1, harness.ticker.scheduled.count { it.intervalMs == 30_000L && !it.cancelled })

        // Connect (StopScan cancels the scan timeout; StartKeepalive arms a 12s ticker).
        harness.transport.emit(TransportEvent.DeviceFound(deviceId, "ow-test", emptyList()))
        harness.transport.emit(TransportEvent.Connected)
        runCurrent()
        assertEquals(0, harness.ticker.scheduled.count { it.intervalMs == 30_000L && !it.cancelled })
        assertEquals(1, harness.ticker.scheduled.count { it.intervalMs == 12_000L && !it.cancelled })

        // Drop -> rescan: keepalive cancelled, a fresh scan timeout armed.
        harness.transport.emit(TransportEvent.Disconnected(GattStatus.SUCCESS))
        runCurrent()
        assertEquals(0, harness.ticker.scheduled.count { it.intervalMs == 12_000L && !it.cancelled })
        assertEquals(1, harness.ticker.scheduled.count { it.intervalMs == 30_000L && !it.cancelled })
    }
}
