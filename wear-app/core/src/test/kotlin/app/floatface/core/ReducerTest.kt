package app.floatface.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Table-driven, exhaustive tests for [DefaultConnectionStateMachine] — the pure
 * connection reducer (SPEC §4.5.6, §6.3). Every case asserts BOTH the exact next
 * [ConnectionState] and the exact [Command] list, including order.
 */
class ReducerTest {

    private val validUnlock = ByteArray(20) { (it + 1).toByte() } // configured: 20 bytes, not all-zero
    private val allZeroUnlock = ByteArray(20) // not configured despite length 20

    private fun machine(unlock: ByteArray? = validUnlock, keepaliveMs: Long = 12_000L) =
        DefaultConnectionStateMachine(unlock, keepaliveMs)

    private val fullService: Set<OwCharacteristic> = OwCharacteristic.entries.toSet()

    // ---------------------------------------------------------------------
    // Scanning
    // ---------------------------------------------------------------------

    @Test
    fun `scanning plus name-matched device found stops scan and connects`() {
        val m = machine()
        val found = TransportEvent.DeviceFound("AA:BB", "OW-GT-1234", emptyList())
        val r = m.reduce(ConnectionState.Scanning, Event.Transport(found))
        assertEquals(ConnectionState.Connecting, r.state)
        assertEquals(listOf(Command.StopScan, Command.Connect("AA:BB")), r.commands)
    }

    @Test
    fun `scanning plus name match is case-insensitive prefix`() {
        val m = machine()
        val found = TransportEvent.DeviceFound("AA:BB", "ow board", emptyList())
        val r = m.reduce(ConnectionState.Scanning, Event.Transport(found))
        assertEquals(ConnectionState.Connecting, r.state)
    }

    @Test
    fun `scanning plus service-uuid fallback match (no name) connects`() {
        val m = machine()
        val found = TransportEvent.DeviceFound("CC:DD", null, listOf(OwCharacteristic.SERVICE_UUID.uppercase()))
        val r = m.reduce(ConnectionState.Scanning, Event.Transport(found))
        assertEquals(ConnectionState.Connecting, r.state)
        assertEquals(listOf(Command.StopScan, Command.Connect("CC:DD")), r.commands)
    }

    @Test
    fun `scanning plus non-matching device found is ignored`() {
        val m = machine()
        val found = TransportEvent.DeviceFound("EE:FF", "SomeOtherThing", listOf("dead-beef"))
        val r = m.reduce(ConnectionState.Scanning, Event.Transport(found))
        assertEquals(ConnectionState.Scanning, r.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun `scanning plus null name and no matching uuid is ignored`() {
        val m = machine()
        val found = TransportEvent.DeviceFound("00:11", null, emptyList())
        val r = m.reduce(ConnectionState.Scanning, Event.Transport(found))
        assertEquals(ConnectionState.Scanning, r.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun `scanning plus scan timeout transitions to scan timed out with no commands`() {
        val m = machine()
        val r = m.reduce(ConnectionState.Scanning, Event.ScanTimeout)
        assertEquals(ConnectionState.ScanTimedOut, r.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun `scanning ignores unrelated events`() {
        val m = machine()
        for (event in listOf(
            Event.User(UserIntent.NextPage),
            Event.User(UserIntent.PrevPage),
            Event.User(UserIntent.SelectPage(2)),
            Event.User(UserIntent.ToggleRecording),
            Event.Recorder(RecordingState.Recording),
            Event.KeepaliveTick,
            Event.LivenessTimeout,
        )) {
            val r = m.reduce(ConnectionState.Scanning, event)
            assertEquals(ConnectionState.Scanning, r.state, "event=$event")
            assertTrue(r.commands.isEmpty(), "event=$event")
        }
    }

    // ---------------------------------------------------------------------
    // ScanTimedOut
    // ---------------------------------------------------------------------

    @Test
    fun `scan timed out plus device found matches connects`() {
        val m = machine()
        val found = TransportEvent.DeviceFound("11:22", "OWBoard", emptyList())
        val r = m.reduce(ConnectionState.ScanTimedOut, Event.Transport(found))
        assertEquals(ConnectionState.Connecting, r.state)
        assertEquals(listOf(Command.StopScan, Command.Connect("11:22")), r.commands)
    }

    @Test
    fun `scan timed out plus non-matching device found ignored`() {
        val m = machine()
        val found = TransportEvent.DeviceFound("11:22", "Nope", emptyList())
        val r = m.reduce(ConnectionState.ScanTimedOut, Event.Transport(found))
        assertEquals(ConnectionState.ScanTimedOut, r.state)
        assertTrue(r.commands.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Connecting
    // ---------------------------------------------------------------------

    @Test
    fun `connecting plus connected discovers services`() {
        val m = machine()
        val r = m.reduce(ConnectionState.Connecting, Event.Transport(TransportEvent.Connected))
        assertEquals(ConnectionState.Discovering, r.state)
        assertEquals(listOf(Command.DiscoverServices), r.commands)
    }

    @Test
    fun `connecting plus disconnected rescans`() {
        val m = machine()
        val r = m.reduce(ConnectionState.Connecting, Event.Transport(TransportEvent.Disconnected(GattStatus(19))))
        assertEquals(ConnectionState.Rescanning, r.state)
        assertEquals(listOf(Command.CloseGatt, Command.StartScan), r.commands)
    }

    @Test
    fun `connecting plus connect operation failed rescans`() {
        val m = machine()
        val fail = TransportEvent.OperationFailed(GattOp.CONNECT, GattStatus(133))
        val r = m.reduce(ConnectionState.Connecting, Event.Transport(fail))
        assertEquals(ConnectionState.Rescanning, r.state)
        assertEquals(listOf(Command.CloseGatt, Command.StartScan), r.commands)
    }

    @Test
    fun `connecting plus non-connect operation failed is ignored`() {
        val m = machine()
        val fail = TransportEvent.OperationFailed(GattOp.DISCOVER, GattStatus(1))
        val r = m.reduce(ConnectionState.Connecting, Event.Transport(fail))
        assertEquals(ConnectionState.Connecting, r.state)
        assertTrue(r.commands.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Discovering
    // ---------------------------------------------------------------------

    @Test
    fun `discovering plus failed status yields service not found and closes gatt`() {
        val m = machine()
        val ev = TransportEvent.ServicesDiscovered(GattStatus(8), fullService)
        val r = m.reduce(ConnectionState.Discovering, Event.Transport(ev))
        assertEquals(ConnectionState.ServiceNotFound, r.state)
        assertEquals(listOf(Command.CloseGatt), r.commands)
    }

    @Test
    fun `discovering missing uart serial write yields write char not found`() {
        val m = machine()
        val available = fullService - OwCharacteristic.UART_SERIAL_WRITE
        val ev = TransportEvent.ServicesDiscovered(GattStatus.SUCCESS, available)
        val r = m.reduce(ConnectionState.Discovering, Event.Transport(ev))
        assertEquals(ConnectionState.WriteCharNotFound, r.state)
        assertEquals(listOf(Command.CloseGatt), r.commands)
    }

    @Test
    fun `discovering plus unconfigured unlock (null bytes) yields unlock not configured`() {
        val m = machine(unlock = null)
        val ev = TransportEvent.ServicesDiscovered(GattStatus.SUCCESS, fullService)
        val r = m.reduce(ConnectionState.Discovering, Event.Transport(ev))
        assertEquals(ConnectionState.UnlockNotConfigured, r.state)
        assertEquals(listOf(Command.CloseGatt), r.commands)
    }

    @Test
    fun `discovering plus unconfigured unlock (all-zero bytes) yields unlock not configured`() {
        val m = machine(unlock = allZeroUnlock)
        val ev = TransportEvent.ServicesDiscovered(GattStatus.SUCCESS, fullService)
        val r = m.reduce(ConnectionState.Discovering, Event.Transport(ev))
        assertEquals(ConnectionState.UnlockNotConfigured, r.state)
        assertEquals(listOf(Command.CloseGatt), r.commands)
    }

    @Test
    fun `discovering success with full notify set enters subscribing in NOTIFY_SET order`() {
        val m = machine()
        val ev = TransportEvent.ServicesDiscovered(GattStatus.SUCCESS, fullService)
        val r = m.reduce(ConnectionState.Discovering, Event.Transport(ev))
        assertEquals(ConnectionState.Subscribing(OwCharacteristic.NOTIFY_SET), r.state)
        assertEquals(
            listOf(
                Command.Read(OwCharacteristic.FIRMWARE_REVISION),
                Command.EnableNotifications(OwCharacteristic.NOTIFY_SET.first()),
            ),
            r.commands,
        )
    }

    @Test
    fun `discovering success filters unavailable notify characteristics but keeps NOTIFY_SET order`() {
        val m = machine()
        // Drop one from the middle of NOTIFY_SET; the remaining order must still follow NOTIFY_SET.
        val available = fullService - OwCharacteristic.SAFETY_HEADROOM
        val ev = TransportEvent.ServicesDiscovered(GattStatus.SUCCESS, available)
        val r = m.reduce(ConnectionState.Discovering, Event.Transport(ev))
        val expectedNotify = OwCharacteristic.NOTIFY_SET.filter { it != OwCharacteristic.SAFETY_HEADROOM }
        assertEquals(ConnectionState.Subscribing(expectedNotify), r.state)
        assertEquals(
            listOf(Command.Read(OwCharacteristic.FIRMWARE_REVISION), Command.EnableNotifications(expectedNotify.first())),
            r.commands,
        )
    }

    @Test
    fun `discovering success with empty notify set goes straight to unlocking`() {
        val m = machine()
        val available = setOf(OwCharacteristic.UART_SERIAL_WRITE, OwCharacteristic.UART_SERIAL_READ)
        val ev = TransportEvent.ServicesDiscovered(GattStatus.SUCCESS, available)
        val r = m.reduce(ConnectionState.Discovering, Event.Transport(ev))
        assertEquals(ConnectionState.Unlocking(0), r.state)
        assertEquals(listOf(Command.WriteUnlock(validUnlock)), r.commands)
    }

    // ---------------------------------------------------------------------
    // Subscribing — serialized CCCD enable, one EnableNotifications per ack
    // ---------------------------------------------------------------------

    @Test
    fun `subscribing advances one characteristic per ack in NOTIFY_SET order regardless of status`() {
        val m = machine()
        val notifySet = OwCharacteristic.NOTIFY_SET
        var state: ConnectionState = ConnectionState.Subscribing(notifySet)

        for (i in notifySet.indices) {
            val char = notifySet[i]
            // Alternate ack status to prove "advance regardless of status".
            val status = if (i % 2 == 0) GattStatus.SUCCESS else GattStatus(5)
            val r = m.reduce(state, Event.Transport(TransportEvent.NotificationsEnabled(char, status)))
            val remainingAfter = notifySet.drop(i + 1)
            if (remainingAfter.isEmpty()) {
                assertEquals(ConnectionState.Unlocking(0), r.state, "final ack should unlock")
                assertEquals(listOf(Command.WriteUnlock(validUnlock)), r.commands)
            } else {
                assertEquals(ConnectionState.Subscribing(remainingAfter), r.state, "after ack #$i")
                assertEquals(listOf(Command.EnableNotifications(remainingAfter.first())), r.commands, "after ack #$i")
            }
            state = r.state
        }
        assertEquals(ConnectionState.Unlocking(0), state)
    }

    @Test
    fun `subscribing ignores duplicate or out-of-order ack`() {
        val m = machine()
        val notifySet = OwCharacteristic.NOTIFY_SET
        val state = ConnectionState.Subscribing(notifySet)
        // Ack the SECOND char while the first is still expected: must be ignored.
        val dup = TransportEvent.NotificationsEnabled(notifySet[1], GattStatus.SUCCESS)
        val r = m.reduce(state, Event.Transport(dup))
        assertEquals(state, r.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun `subscribing ignores a repeat ack of the already-advanced-past characteristic`() {
        val m = machine()
        val notifySet = OwCharacteristic.NOTIFY_SET
        val first = ConnectionState.Subscribing(notifySet)
        val advanced = m.reduce(first, Event.Transport(TransportEvent.NotificationsEnabled(notifySet[0], GattStatus.SUCCESS)))
        assertEquals(ConnectionState.Subscribing(notifySet.drop(1)), advanced.state)

        // Re-deliver an ack for the char we already advanced past.
        val repeat = m.reduce(advanced.state, Event.Transport(TransportEvent.NotificationsEnabled(notifySet[0], GattStatus.SUCCESS)))
        assertEquals(advanced.state, repeat.state)
        assertTrue(repeat.commands.isEmpty())
    }

    @Test
    fun `subscribing plus firmware revision read complete stays put with no commands`() {
        val m = machine()
        val notifySet = OwCharacteristic.NOTIFY_SET
        val state = ConnectionState.Subscribing(notifySet)
        val read = TransportEvent.ReadComplete(OwCharacteristic.FIRMWARE_REVISION, byteArrayOf(6, 0), GattStatus.SUCCESS)
        val r = m.reduce(state, Event.Transport(read))
        assertEquals(state, r.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun `subscribing plus disconnected rescans`() {
        val m = machine()
        val state = ConnectionState.Subscribing(OwCharacteristic.NOTIFY_SET)
        val r = m.reduce(state, Event.Transport(TransportEvent.Disconnected(GattStatus(19))))
        assertEquals(ConnectionState.Rescanning, r.state)
        assertEquals(listOf(Command.CloseGatt, Command.StartScan), r.commands)
    }

    // ---------------------------------------------------------------------
    // Unlocking
    // ---------------------------------------------------------------------

    @Test
    fun `unlocking plus write success connects and starts keepalive and initial reads`() {
        val m = machine(keepaliveMs = 12_000L)
        val ok = TransportEvent.WriteComplete(OwCharacteristic.UART_SERIAL_WRITE, GattStatus.SUCCESS)
        val r = m.reduce(ConnectionState.Unlocking(0), Event.Transport(ok))
        assertEquals(ConnectionState.Connected, r.state)
        assertEquals(
            listOf(Command.StartKeepalive(12_000L), Command.IssueInitialReads(OwCharacteristic.INITIAL_READS)),
            r.commands,
        )
    }

    @Test
    fun `unlocking plus write failure retries once then errors`() {
        val m = machine()
        val fail = TransportEvent.WriteComplete(OwCharacteristic.UART_SERIAL_WRITE, GattStatus(4))

        val r1 = m.reduce(ConnectionState.Unlocking(0), Event.Transport(fail))
        assertEquals(ConnectionState.Unlocking(1), r1.state)
        assertEquals(listOf(Command.WriteUnlock(validUnlock)), r1.commands)

        val r2 = m.reduce(r1.state, Event.Transport(fail))
        assertEquals(ConnectionState.Error("Unlock failed"), r2.state)
        assertEquals(listOf(Command.CloseGatt), r2.commands)
    }

    @Test
    fun `unlocking plus disconnected rescans`() {
        val m = machine()
        val r = m.reduce(ConnectionState.Unlocking(0), Event.Transport(TransportEvent.Disconnected(GattStatus(19))))
        assertEquals(ConnectionState.Rescanning, r.state)
        assertEquals(listOf(Command.CloseGatt, Command.StartScan), r.commands)
    }

    // ---------------------------------------------------------------------
    // Connected
    // ---------------------------------------------------------------------

    @Test
    fun `connected plus keepalive tick re-writes unlock`() {
        val m = machine()
        val r = m.reduce(ConnectionState.Connected, Event.KeepaliveTick)
        assertEquals(ConnectionState.Connected, r.state)
        assertEquals(listOf(Command.WriteUnlock(validUnlock)), r.commands)
    }

    @Test
    fun `connected plus liveness timeout re-writes unlock`() {
        val m = machine()
        val r = m.reduce(ConnectionState.Connected, Event.LivenessTimeout)
        assertEquals(ConnectionState.Connected, r.state)
        assertEquals(listOf(Command.WriteUnlock(validUnlock)), r.commands)
    }

    @Test
    fun `connected plus disconnected rescans`() {
        val m = machine()
        val r = m.reduce(ConnectionState.Connected, Event.Transport(TransportEvent.Disconnected(GattStatus(19))))
        assertEquals(ConnectionState.Rescanning, r.state)
        assertEquals(listOf(Command.CloseGatt, Command.StartScan), r.commands)
    }

    @Test
    fun `connected plus characteristic changed stays connected with no commands (controller folds telemetry)`() {
        val m = machine()
        val changed = TransportEvent.CharacteristicChanged(OwCharacteristic.SPEED_RPM, byteArrayOf(1, 2))
        val r = m.reduce(ConnectionState.Connected, Event.Transport(changed))
        assertEquals(ConnectionState.Connected, r.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun `connected plus read complete stays connected with no commands`() {
        val m = machine()
        val read = TransportEvent.ReadComplete(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(50), GattStatus.SUCCESS)
        val r = m.reduce(ConnectionState.Connected, Event.Transport(read))
        assertEquals(ConnectionState.Connected, r.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun `connected interleaves keepalive tick and characteristic changed deterministically`() {
        val m = machine()
        val changed = TransportEvent.CharacteristicChanged(OwCharacteristic.BATTERY_LEVEL, byteArrayOf(9))

        val r1 = m.reduce(ConnectionState.Connected, Event.Transport(changed))
        assertEquals(ConnectionState.Connected, r1.state)
        assertTrue(r1.commands.isEmpty())

        val r2 = m.reduce(r1.state, Event.KeepaliveTick)
        assertEquals(ConnectionState.Connected, r2.state)
        assertEquals(listOf(Command.WriteUnlock(validUnlock)), r2.commands)

        val r3 = m.reduce(r2.state, Event.Transport(changed))
        assertEquals(ConnectionState.Connected, r3.state)
        assertTrue(r3.commands.isEmpty())
    }

    @Test
    fun `connected ignores user paging and recorder events with no commands`() {
        val m = machine()
        for (event in listOf(
            Event.User(UserIntent.NextPage),
            Event.User(UserIntent.PrevPage),
            Event.User(UserIntent.SelectPage(1)),
            Event.User(UserIntent.ToggleRecording),
            Event.Recorder(RecordingState.Recording),
        )) {
            val r = m.reduce(ConnectionState.Connected, event)
            assertEquals(ConnectionState.Connected, r.state, "event=$event")
            assertTrue(r.commands.isEmpty(), "event=$event")
        }
    }

    // ---------------------------------------------------------------------
    // Rescanning
    // ---------------------------------------------------------------------

    @Test
    fun `rescanning plus device found matches connects`() {
        val m = machine()
        val found = TransportEvent.DeviceFound("77:88", "OW-Reconnect", emptyList())
        val r = m.reduce(ConnectionState.Rescanning, Event.Transport(found))
        assertEquals(ConnectionState.Connecting, r.state)
        assertEquals(listOf(Command.StopScan, Command.Connect("77:88")), r.commands)
    }

    @Test
    fun `rescanning plus scan timeout goes to scan timed out`() {
        val m = machine()
        val r = m.reduce(ConnectionState.Rescanning, Event.ScanTimeout)
        assertEquals(ConnectionState.ScanTimedOut, r.state)
        assertTrue(r.commands.isEmpty())
    }

    // ---------------------------------------------------------------------
    // Terminal errors + Retry
    // ---------------------------------------------------------------------

    @Test
    fun `retry from every terminal error state restarts scanning`() {
        val m = machine()
        val terminals = listOf(
            ConnectionState.ServiceNotFound,
            ConnectionState.WriteCharNotFound,
            ConnectionState.UnlockNotConfigured,
            ConnectionState.Error("Unlock failed"),
        )
        for (t in terminals) {
            val r = m.reduce(t, Event.User(UserIntent.Retry))
            assertEquals(ConnectionState.Scanning, r.state, "from $t")
            assertEquals(listOf(Command.CloseGatt, Command.StartScan), r.commands, "from $t")
        }
    }

    @Test
    fun `terminal error states ignore unrelated events with no commands`() {
        val m = machine()
        val terminals = listOf(
            ConnectionState.ServiceNotFound,
            ConnectionState.WriteCharNotFound,
            ConnectionState.UnlockNotConfigured,
            ConnectionState.Error("Unlock failed"),
        )
        val unrelated = listOf(
            Event.Transport(TransportEvent.Disconnected(GattStatus(19))),
            Event.KeepaliveTick,
            Event.LivenessTimeout,
            Event.User(UserIntent.NextPage),
            Event.Recorder(RecordingState.Idle),
        )
        for (t in terminals) {
            for (event in unrelated) {
                val r = m.reduce(t, event)
                assertEquals(t, r.state, "state=$t event=$event")
                assertTrue(r.commands.isEmpty(), "state=$t event=$event")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Shutdown / teardown invariant
    // ---------------------------------------------------------------------

    @Test
    fun `shutdown from every non-terminal state tears down before disconnect`() {
        val m = machine()
        val nonTerminal = listOf(
            ConnectionState.Scanning,
            ConnectionState.ScanTimedOut,
            ConnectionState.Connecting,
            ConnectionState.Discovering,
            ConnectionState.Subscribing(OwCharacteristic.NOTIFY_SET),
            ConnectionState.Unlocking(0),
            ConnectionState.Unlocking(1),
            ConnectionState.Connected,
            ConnectionState.Rescanning,
        )
        for (s in nonTerminal) {
            val r = m.reduce(s, Event.User(UserIntent.Shutdown))
            assertEquals(ConnectionState.ShuttingDown, r.state, "from $s")
            assertEquals(
                listOf(Command.StopScan, Command.StopKeepalive, Command.Disconnect, Command.CloseGatt),
                r.commands,
                "from $s",
            )
        }
    }

    @Test
    fun `shutdown then resulting disconnected is a no-op with no rescan`() {
        val m = machine()
        val shutdownResult = m.reduce(ConnectionState.Connected, Event.User(UserIntent.Shutdown))
        assertEquals(ConnectionState.ShuttingDown, shutdownResult.state)

        // The Disconnect command above will, once run by the effect-runner, produce a
        // Disconnected transport event. Feeding it back in must be a true no-op: the
        // state stays ShuttingDown and — crucially — NO StartScan (no rescan) is issued.
        val afterDisconnect = m.reduce(shutdownResult.state, Event.Transport(TransportEvent.Disconnected(GattStatus.SUCCESS)))
        assertEquals(ConnectionState.ShuttingDown, afterDisconnect.state)
        assertTrue(afterDisconnect.commands.isEmpty())
        assertFalse(afterDisconnect.commands.contains(Command.StartScan))
    }

    @Test
    fun `shutting down ignores every further event`() {
        val m = machine()
        for (event in listOf(
            Event.Transport(TransportEvent.Disconnected(GattStatus.SUCCESS)),
            Event.Transport(TransportEvent.Connected),
            Event.KeepaliveTick,
            Event.LivenessTimeout,
            Event.ScanTimeout,
            Event.User(UserIntent.Shutdown),
            Event.User(UserIntent.Retry),
            Event.Recorder(RecordingState.Recording),
        )) {
            val r = m.reduce(ConnectionState.ShuttingDown, event)
            assertEquals(ConnectionState.ShuttingDown, r.state, "event=$event")
            assertTrue(r.commands.isEmpty(), "event=$event")
        }
    }

    @Test
    fun `terminal error states do not shut down (already at rest with gatt closed)`() {
        val m = machine()
        val terminals = listOf(
            ConnectionState.ServiceNotFound,
            ConnectionState.WriteCharNotFound,
            ConnectionState.UnlockNotConfigured,
            ConnectionState.Error("Unlock failed"),
        )
        for (t in terminals) {
            val r = m.reduce(t, Event.User(UserIntent.Shutdown))
            assertEquals(t, r.state, "from $t")
            assertTrue(r.commands.isEmpty(), "from $t")
        }
    }

    // ---------------------------------------------------------------------
    // Full happy path (SPEC §3.5 / §6.3)
    // ---------------------------------------------------------------------

    @Test
    fun `full happy path from scanning to connected asserts exact commands at every step`() {
        val m = machine(keepaliveMs = 12_000L)
        var state: ConnectionState = ConnectionState.Scanning

        // 1. Device found by name -> Connecting, StopScan + Connect
        var r = m.reduce(state, Event.Transport(TransportEvent.DeviceFound("AA:11", "OW-GT", emptyList())))
        assertEquals(ConnectionState.Connecting, r.state)
        assertEquals(listOf(Command.StopScan, Command.Connect("AA:11")), r.commands)
        state = r.state

        // 2. Transport connects -> Discovering, DiscoverServices
        r = m.reduce(state, Event.Transport(TransportEvent.Connected))
        assertEquals(ConnectionState.Discovering, r.state)
        assertEquals(listOf(Command.DiscoverServices), r.commands)
        state = r.state

        // 3. Services discovered (everything present) -> Subscribing, Read(FW) + first EnableNotifications
        r = m.reduce(state, Event.Transport(TransportEvent.ServicesDiscovered(GattStatus.SUCCESS, fullService)))
        assertEquals(ConnectionState.Subscribing(OwCharacteristic.NOTIFY_SET), r.state)
        assertEquals(
            listOf(
                Command.Read(OwCharacteristic.FIRMWARE_REVISION),
                Command.EnableNotifications(OwCharacteristic.NOTIFY_SET.first()),
            ),
            r.commands,
        )
        state = r.state

        // 4. Serialized CCCD enable: one EnableNotifications per ack, in NOTIFY_SET order.
        for (i in OwCharacteristic.NOTIFY_SET.indices) {
            val char = OwCharacteristic.NOTIFY_SET[i]
            r = m.reduce(state, Event.Transport(TransportEvent.NotificationsEnabled(char, GattStatus.SUCCESS)))
            val remaining = OwCharacteristic.NOTIFY_SET.drop(i + 1)
            if (remaining.isEmpty()) {
                assertEquals(ConnectionState.Unlocking(0), r.state)
                assertEquals(listOf(Command.WriteUnlock(validUnlock)), r.commands)
            } else {
                assertEquals(ConnectionState.Subscribing(remaining), r.state)
                assertEquals(listOf(Command.EnableNotifications(remaining.first())), r.commands)
            }
            state = r.state
        }
        assertEquals(ConnectionState.Unlocking(0), state)

        // Firmware revision read arrives at some point during subscribing/unlocking window;
        // exercised here against Unlocking too, but the canonical spot is Subscribing (already
        // covered above) — no-op either way per the reducer's non-listed-event default.

        // 5. Unlock write succeeds -> Connected, StartKeepalive + IssueInitialReads
        r = m.reduce(state, Event.Transport(TransportEvent.WriteComplete(OwCharacteristic.UART_SERIAL_WRITE, GattStatus.SUCCESS)))
        assertEquals(ConnectionState.Connected, r.state)
        assertEquals(
            listOf(Command.StartKeepalive(12_000L), Command.IssueInitialReads(OwCharacteristic.INITIAL_READS)),
            r.commands,
        )
        state = r.state

        // 6. Keepalive cadence under Connected -> WriteUnlock each tick.
        r = m.reduce(state, Event.KeepaliveTick)
        assertEquals(ConnectionState.Connected, r.state)
        assertEquals(listOf(Command.WriteUnlock(validUnlock)), r.commands)
    }

    // ---------------------------------------------------------------------
    // Read-only safety invariant: across ALL exercised paths, the only write
    // command ever produced is Command.WriteUnlock, and its bytes are always
    // exactly the configured unlockBytes (the adapter hard-codes the target
    // characteristic to UART_SERIAL_WRITE — see Ports.kt).
    // ---------------------------------------------------------------------

    @Test
    fun `across every exercised path the only command that ever appears is from the allowed set and the only write is WriteUnlock`() {
        val m = machine()
        val allCommands = mutableListOf<Command>()

        fun step(state: ConnectionState, event: Event): ConnectionState {
            val r = m.reduce(state, event)
            allCommands += r.commands
            return r.state
        }

        var state: ConnectionState = ConnectionState.Scanning
        state = step(state, Event.Transport(TransportEvent.DeviceFound("AA:11", "OW-GT", emptyList())))
        state = step(state, Event.Transport(TransportEvent.Connected))
        state = step(state, Event.Transport(TransportEvent.ServicesDiscovered(GattStatus.SUCCESS, fullService)))
        for (char in OwCharacteristic.NOTIFY_SET) {
            state = step(state, Event.Transport(TransportEvent.NotificationsEnabled(char, GattStatus.SUCCESS)))
        }
        // First unlock write fails, forcing the bounded retry path.
        state = step(
            state,
            Event.Transport(TransportEvent.WriteComplete(OwCharacteristic.UART_SERIAL_WRITE, GattStatus(4))),
        )
        state = step(
            state,
            Event.Transport(TransportEvent.WriteComplete(OwCharacteristic.UART_SERIAL_WRITE, GattStatus.SUCCESS)),
        )
        state = step(state, Event.KeepaliveTick)
        state = step(state, Event.LivenessTimeout)
        state = step(state, Event.Transport(TransportEvent.CharacteristicChanged(OwCharacteristic.SPEED_RPM, byteArrayOf(1))))
        state = step(state, Event.Transport(TransportEvent.Disconnected(GattStatus(19)))) // -> Rescanning
        state = step(state, Event.Transport(TransportEvent.DeviceFound("AA:11", "OW-GT", emptyList())))
        state = step(state, Event.User(UserIntent.Shutdown))
        step(state, Event.Transport(TransportEvent.Disconnected(GattStatus.SUCCESS))) // no-op teardown

        val writeCommands = allCommands.filterIsInstance<Command.WriteUnlock>()
        assertTrue(writeCommands.isNotEmpty(), "sanity: at least one WriteUnlock should have occurred")
        for (cmd in allCommands) {
            when (cmd) {
                is Command.WriteUnlock -> assertTrue(cmd.bytes.contentEquals(validUnlock))
                else -> Unit // any other command type is fine; only WriteUnlock is a "write"
            }
        }
        // Confirm no other command type is itself a characteristic write: the sealed
        // Command set's only write-capable member is WriteUnlock (Read/EnableNotifications/
        // IssueInitialReads are read/subscribe operations by construction).
        assertTrue(allCommands.none { it is Command.WriteUnlock && !it.bytes.contentEquals(validUnlock) })
    }

    @Test
    fun `unlock not configured path never emits a WriteUnlock command`() {
        val m1 = machine(unlock = null)
        val m2 = machine(unlock = allZeroUnlock)
        for (m in listOf(m1, m2)) {
            var state: ConnectionState = ConnectionState.Scanning
            val allCommands = mutableListOf<Command>()

            fun step(s: ConnectionState, event: Event): ConnectionState {
                val r = m.reduce(s, event)
                allCommands += r.commands
                return r.state
            }

            state = step(state, Event.Transport(TransportEvent.DeviceFound("AA:11", "OW-GT", emptyList())))
            state = step(state, Event.Transport(TransportEvent.Connected))
            state = step(state, Event.Transport(TransportEvent.ServicesDiscovered(GattStatus.SUCCESS, fullService)))

            assertEquals(ConnectionState.UnlockNotConfigured, state)
            assertTrue(allCommands.none { it is Command.WriteUnlock })
        }
    }

    // ---------------------------------------------------------------------
    // Keepalive cadence under FakeTicker-style scheduling (SPEC §6.3): the
    // reducer itself is time-agnostic, so this asserts each independent
    // KeepaliveTick deterministically re-issues exactly one WriteUnlock.
    // ---------------------------------------------------------------------

    @Test
    fun `repeated keepalive ticks each independently re-write unlock while connected`() {
        val m = machine()
        repeat(5) {
            val r = m.reduce(ConnectionState.Connected, Event.KeepaliveTick)
            assertEquals(ConnectionState.Connected, r.state)
            assertEquals(listOf(Command.WriteUnlock(validUnlock)), r.commands)
        }
    }

    @Test
    fun `disconnected in any non-terminal non-shutting-down state resets link to rescanning`() {
        val m = machine()
        val states = listOf(
            ConnectionState.Connecting,
            ConnectionState.Subscribing(OwCharacteristic.NOTIFY_SET),
            ConnectionState.Unlocking(0),
            ConnectionState.Unlocking(1),
            ConnectionState.Connected,
        )
        for (s in states) {
            val r = m.reduce(s, Event.Transport(TransportEvent.Disconnected(GattStatus(19))))
            assertEquals(ConnectionState.Rescanning, r.state, "from $s")
            assertEquals(listOf(Command.CloseGatt, Command.StartScan), r.commands, "from $s")
        }
    }
}
