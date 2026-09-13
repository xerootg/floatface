package app.floatface.core

import app.cash.turbine.test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
            advanceUntilIdle()
            assertEquals(1, harness.transport.startScanCalls.size)

            harness.transport.emit(TransportEvent.DeviceFound(deviceId, "Onewheel", emptyList()))
            advanceUntilIdle()
            assertEquals(ConnectionState.Connecting, expectMostRecentItem().connection)
            assertEquals(listOf(deviceId), harness.transport.connected)

            harness.transport.emit(TransportEvent.Connected)
            advanceUntilIdle()
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
            advanceUntilIdle()
            harness.transport.emit(TransportEvent.DeviceFound(deviceId, null, emptyList()))
            harness.transport.emit(TransportEvent.Connected)
            advanceUntilIdle()

            assertEquals(1, harness.transport.writes.size) // the initial unlock write on Connected

            // FakeTicker doesn't auto-repeat: firing the scheduled keepalive task simulates cadence.
            repeat(3) {
                harness.ticker.fireLast()
                advanceUntilIdle()
            }

            assertEquals(4, harness.transport.writes.size)
            harness.transport.writes.forEach { w -> assertTrue(w.contentEquals(unlockBytes)) }
        }

    @Test
    fun `CharacteristicChanged BATTERY_LEVEL decodes into telemetry`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            advanceUntilIdle()
            skipItems(1)

            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(0x00, 0x40)))
            advanceUntilIdle()
            assertEquals(64, expectMostRecentItem().telemetry.batteryLevel)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ToggleRecording then SPEED_RPM samples accumulate distance`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            advanceUntilIdle()
            skipItems(1)

            controller.submit(UserIntent.ToggleRecording)
            advanceUntilIdle()
            assertEquals(RecordingState.Recording, expectMostRecentItem().ride.recording)
            assertEquals(1, harness.recorder.startCalls.size)

            val rpm = 6000
            fun rpmBytes(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

            harness.clock.elapsed = 0L
            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.SPEED_RPM, rpmBytes(rpm)))
            advanceUntilIdle()
            // First sample after reset() integrates zero.
            assertEquals(0.0, expectMostRecentItem().ride.distanceMiles, 1e-9)

            harness.clock.elapsed = 3_600_000L // +1 hour
            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.SPEED_RPM, rpmBytes(rpm)))
            advanceUntilIdle()

            val expectedMph = RpmSpeed.toMph(rpm, BoardModel.GT.tireDiameterInches)
            assertEquals(expectedMph, expectMostRecentItem().ride.distanceMiles, 1e-6)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `halfway warning buzzes exactly once per ride`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            advanceUntilIdle()
            skipItems(1)

            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(0x00, 100)))
            advanceUntilIdle()
            expectMostRecentItem()

            controller.submit(UserIntent.ToggleRecording) // startBattery = 100
            advanceUntilIdle()
            expectMostRecentItem()

            assertEquals(0, harness.haptics.buzzes.size)

            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(0x00, 50)))
            advanceUntilIdle()
            var state = expectMostRecentItem()
            assertEquals(1, harness.haptics.buzzes.size)
            assertTrue(state.ride.halfwayWarningActive)

            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(0x00, 40)))
            advanceUntilIdle()
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
            advanceUntilIdle()
            skipItems(1)

            harness.transport.emit(TransportEvent.DeviceFound(deviceId, null, emptyList()))
            harness.transport.emit(TransportEvent.Connected)
            advanceUntilIdle()
            assertEquals(ConnectionState.Connected, expectMostRecentItem().connection)
            val startScansSoFar = harness.transport.startScanCalls.size

            controller.submit(UserIntent.Shutdown)
            advanceUntilIdle()
            assertEquals(ConnectionState.ShuttingDown, expectMostRecentItem().connection)
            assertEquals(1, harness.transport.disconnectCalls.size)
            assertEquals(1, harness.transport.closeGattCalls.size)

            // The resulting Disconnected must be a no-op: no rescan.
            harness.transport.emit(TransportEvent.Disconnected(GattStatus.SUCCESS))
            advanceUntilIdle()
            assertEquals(ConnectionState.ShuttingDown, expectMostRecentItem().connection)
            assertEquals(startScansSoFar, harness.transport.startScanCalls.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Disconnected resets live telemetry but keeps ride state`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            advanceUntilIdle()
            skipItems(1)

            harness.transport.emit(TransportEvent.CharacteristicChanged(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(0x00, 80)))
            advanceUntilIdle()
            expectMostRecentItem()

            controller.submit(UserIntent.ToggleRecording)
            advanceUntilIdle()
            expectMostRecentItem()

            harness.transport.emit(TransportEvent.Disconnected(GattStatus.SUCCESS))
            advanceUntilIdle()
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
            advanceUntilIdle()
            skipItems(1)

            controller.submit(UserIntent.PrevPage) // wraps 0 -> 3
            advanceUntilIdle()
            assertEquals(3, expectMostRecentItem().page)

            controller.submit(UserIntent.NextPage)
            advanceUntilIdle()
            assertEquals(0, expectMostRecentItem().page)

            controller.submit(UserIntent.SelectPage(99))
            advanceUntilIdle()
            assertEquals(3, expectMostRecentItem().page)

            controller.submit(UserIntent.SelectPage(-5))
            advanceUntilIdle()
            assertEquals(0, expectMostRecentItem().page)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ToggleRecording again stops recording`() = runControllerTest { harness, controller ->
        controller.uiState.test {
            controller.start()
            advanceUntilIdle()
            skipItems(1)

            controller.submit(UserIntent.ToggleRecording)
            advanceUntilIdle()
            assertEquals(RecordingState.Recording, expectMostRecentItem().ride.recording)

            controller.submit(UserIntent.ToggleRecording)
            advanceUntilIdle()
            assertEquals(RecordingState.Idle, expectMostRecentItem().ride.recording)
            assertEquals(1, harness.recorder.stopCalls.size)
            assertFalse(harness.recorder.startCalls.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
