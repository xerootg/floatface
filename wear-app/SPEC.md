# Floatface for Wear OS — Specification (v2, honed)

> Standalone, direct-BLE Onewheel telemetry for a Google Pixel Watch (Wear OS),
> written in Kotlin. The Wear OS sibling of `garmin-app/`. No phone required.

**Status:** v2 — audited (7-dimension expert review) and honed. This is the
**implementation-ready** source of truth the TDD swarm builds against. Section
**§4.5 (Core contracts, frozen)** is normative: types there are copied verbatim
into `:core`; nothing may diverge from them.

Change log v1→v2: froze all shared contracts; made the read-only guarantee
compile-time-enforced (transport exposes only `writeUnlock`); added the Android
single-outstanding-GATT-op queue; committed to a pure reducer + effect-runner;
added terminal teardown state; fixed the arithmetic-impossible temp fixture;
added Compose-compiler plugin, foreground-service `health` type, full BuildConfig
unlock path, error taxonomy, preconditions/recovery, process-death recovery,
persistence, CI, entry point, and pinned the version catalog. Closed all §13
open questions with evidence.

---

## 1. Purpose, scope, and philosophy

### 1.1 What this is

A native Kotlin **Wear OS** app for a **Google Pixel Watch** that talks
**directly** to a Onewheel (confirmed against **Onewheel GT**) over BLE — no
phone, no companion, no FutureMotion app. It ports the proven behavior of the
Garmin Connect IQ app in `garmin-app/`: scan → connect → **unlock** with the
rider's captured bytes → **keepalive** → decode & display **live telemetry** →
**record a GPS activity** with telemetry → **self-calibrating range** +
**halfway-battery warning**.

### 1.2 Design philosophy (inherited; non-negotiable)

1. **Read-only / telemetry-only, enforced by construction.** The app issues
   exactly one class of BLE write — the unlock/keepalive write to
   `uart_serial_write` (`e659f3ff`). This is not a convention: the `:core`
   transport port exposes only `writeUnlock(bytes)` and its `:ble` adapter
   hard-codes the target characteristic, so **no code path exists** to write any
   other characteristic. No riding-mode/shaping/settings writes, ever (see
   `PROTOCOL.md` "Ride-mode switching investigated, NOT implemented";
   `CONTRIBUTING.md` "What's out of scope").
2. **Show raw over guessing.** Unconfirmed values (`safety_headroom`,
   `trip_amp_hours` scale, `status` bits) are shown raw and labeled, never as a
   confident calibrated interpretation.
3. **Verify before implementing.** Every protocol constant here traces to a
   confirmed finding in `PROTOCOL.md` / `garmin-app`.
4. **Don't distract the rider.** Glanceable UI; nothing on the happy path needs
   precise touch at speed. Start/Stop is a stationary mount/dismount action.

### 1.3 Goals

G1 standalone direct-BLE connect+unlock+keepalive · G2 live decode/display of
all telemetry `garmin-app` decodes · G3 Health Services GPS recording + local
telemetry log · G4 self-calibrating range + halfway warning · G5 a
**fully unit-tested pure-Kotlin core** with thin, testable Android adapters ·
G6 per-owner unlock bytes provisioned at build time, never committed.

### 1.4 Non-goals (v1)

Any BLE write other than unlock/keepalive · multi-board simultaneous support ·
iOS/phone/tile/complication/watch-face · deriving the unlock secret (owner
captures it per `README.md`) · on-watch capture of unlock bytes · pre-GT local
MD5 unlock (§14).

---

## 2. Target platform & constraints

| Concern | Decision | Rationale |
|---|---|---|
| Device / OS | Pixel Watch (all gens) / Wear OS 3.5+ | User's device. |
| `minSdk` | **30** (FIXED) | `health-services-client` floors at API 30. |
| `compileSdk` / `targetSdk` | 35 / 34 | Current toolchain; target 34 avoids new-in-35 churn. |
| Language | Java/Kotlin **JVM 17** on JDK 21 (no toolchain download) | Verified in-env. |
| UI | Jetpack Compose for **Wear OS** | Testable via Robolectric + Compose test. |
| Recording | Health Services `ExerciseClient` | Wear-native; **live data only — the app persists it** (§7). |
| Display | ~450×450 round AMOLED | Respect round insets (§8.4). |

### 2.1 Platform facts that shape the design

- **The board allows exactly one BLE connection.** With a phone on the official
  app, the board stops advertising. Surface this on scan timeout (§8.3).
- **The board re-locks ~20 s after unlock.** Keepalive is mandatory; interval
  **12 s** (inside the confirmed <20 s window, widened for Doze margin; Garmin
  used 15 s).
- **Scan may not carry the service UUID.** Match by **device name prefix `"ow"`**
  primarily, service-UUID containment as fallback (`PROTOCOL.md`). This decision
  lives in the `:core` reducer (§4.5), not the adapter.
- **Android scan hygiene:** one long-running scan (low-latency while searching,
  low-power for background reconnect); coalesce rescans with backoff; handle
  `onScanFailed(SCANNING_TOO_FREQUENTLY)`; never restart an already-running scan.
- **Wear OS manages background apps aggressively.** A live BLE link + recording
  requires a **foreground service** with the correct service types (§9) and a
  `PARTIAL_WAKE_LOCK` held only while connected.
- **Runtime permissions** (§11): `BLUETOOTH_SCAN` (neverForLocation),
  `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `ACTIVITY_RECOGNITION`,
  `POST_NOTIFICATIONS`, foreground-service permissions incl.
  `FOREGROUND_SERVICE_HEALTH`.

---

## 3. Onewheel BLE protocol (authoritative constants)

All values are **confirmed against a real Onewheel GT** per `PROTOCOL.md` and
encoded in `garmin-app`. Straight port; do not "improve" decoding without
hardware re-validation.

### 3.1 Service & characteristics

Primary service `e659f300-ea98-11e3-ac10-0800200c9a66`; all characteristics
share suffix `-ea98-11e3-ac10-0800200c9a66`, only the 32-bit prefix varies:

| `OwCharacteristic` | prefix | Use | Notify |
|---|---|---|---|
| `FIRMWARE_REVISION` | `e659f311` | read post-discovery; gen = rev/1000 | — |
| `UART_SERIAL_READ` | `e659f3fe` | handshake notify | yes |
| `UART_SERIAL_WRITE` | `e659f3ff` | **sole write target** (unlock/keepalive) | — |
| `BATTERY_LEVEL` | `e659f303` | battery % | on-change |
| `SPEED_RPM` | `e659f30b` | wheel RPM | continuous |
| `RIDING_MODE` | `e659f302` | mode enum | on-change |
| `SAFETY_HEADROOM` | `e659f317` | raw (meaning unconfirmed) | yes |
| `MOTOR_CONTROLLER_TEMP` | `e659f310` | 2× signed-byte °C | continuous |
| `BATTERY_LOW_TEMP` | `e659f315` | 2× signed-byte °C | yes |
| `STATUS` | `e659f30f` | footpad bitmask (raw) | continuous |
| `TRIP_ODOMETER` | `e659f30a` | raw board trip units | yes |
| `LIFE_ODOMETER` | `e659f319` | whole miles (GT) | yes |
| `TRIP_AMP_HOURS` | `e659f313` | raw (scale unconfirmed) | yes |
| `TRIP_REGEN_AMP_HOURS` | `e659f314` | raw (scale unconfirmed) | yes |

**`BATTERY_LOW_TEMP = e659f315` is CONFIRMED** (it produces the validated,
live-updating temperature readings in `PROTOCOL.md`). `e659f31b` is a *different*
characteristic, `battery_cell_voltages`, confirmed to read empty (`0000`) on GT
and **not used**. There is no conflict; never switch battery_low_temp to
`e659f31b` (that would freeze temps at 32°F).

### 3.2 Byte order & decoding

- All 16-bit telemetry is **big-endian `uint16`**.
- **`MOTOR_CONTROLLER_TEMP` / `BATTERY_LOW_TEMP` are two independent signed
  bytes**, each a Celsius reading: `s8@0` = sensor A, `s8@1` = sensor B. Confirmed
  via FM's decompiled `b2.n.a()`. Display both; never average.
- `SAFETY_HEADROOM`, `STATUS` → decode to raw `Int`; no OK/WARN or footpad
  labeling asserted.
- `C→F = round(c*9/5 + 32)`.

Decoder (pure `ByteArray → value`):

```
u16be(b)            = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
s8(b, i)            = b[i].toInt()                        // Kotlin Byte is signed
battery_level       : u16be                              -> Int (0..100)
speed_rpm           : u16be                              -> Int (rpm)
riding_mode         : u16be                              -> Int (enum §3.4)
safety_headroom     : u16be                              -> Int (raw)
status              : u16be                              -> Int (raw)
trip_odometer       : u16be                              -> Int (raw)
life_odometer       : u16be                              -> Int (miles)
trip_amp_hours      : u16be                              -> Int (raw)
trip_regen_amp_hours: u16be                              -> Int (raw)
motor_controller_temp: (cToF(s8@0), cToF(s8@1))          -> Pair<Int,Int> °F
battery_low_temp    : (cToF(s8@0), cToF(s8@1))           -> Pair<Int,Int> °F
firmware_revision   : u16be ; generation = rev / 1000    -> Int
```

Board temperatures are logged raw (Celsius bytes) and converted for display via
the §8.5 unit toggle (there is no FIT-style custom field needing a canonical
unit).

### 3.3 Unlock & keepalive

- Unlock = **20 bytes**, framed `3-byte prefix + 16-byte digest + 1-byte XOR
  checksum`, written to `UART_SERIAL_WRITE`. For GT (fw ≥ 4141) the value is
  computed server-side by FM, account-tied; **the owner captures their 20 bytes
  once** (README A/B/C) and provides them (§10). We do not compute it.
- **The only hard gate before writing is: `length == 20` AND not all-zero.**
  XOR-checksum verification is **advisory-only** (log a warning, never block) and
  **no checksum span is hard-coded** — a captured GT value is known-good and
  cannot be recomputed, so a wrong-span hard gate would reject a valid capture.
- After the unlock write **completes with SUCCESS**, re-write every
  **12 s** while connected. A missed keepalive silently drops telemetry to 0
  after ~20 s.
- Keepalive robustness: route through the GATT op queue (§4.5.4) with a per-op
  timeout; retry a failed/timed-out keepalive; after **N=3** consecutive failures
  treat the link as lost → disconnect + rescan (don't wait for the ~20 s
  supervision timeout). Liveness watchdog: if no telemetry notification arrives
  for **>5 s** while `Connected`, re-issue the unlock immediately.

### 3.4 Riding modes (GT-confirmed)

`3→Bay 4→Roam 5→Flow 6→Highline 7→Elevated 8→Apex`; unknown → `"Mode <n>"`.
GT-specific; other generations fall back to raw numbers (§3.6).

### 3.5 Reads vs. notifications & the canonical connect sequence

Some chars only **notify on change** (`battery_level`, `riding_mode`); others
push continuously. So after subscribing, do an **initial read burst**.

**Canonical connect order (normative):**
1. `Connect(deviceId)` (autoConnect=false).
2. On `Connected` → request service discovery; wait for
   `ServicesDiscovered(status)` — `getService()` returns null before this.
3. Read `FIRMWARE_REVISION` (readable pre-unlock) → compute generation →
   resolve `BoardModel`. Until resolved, use GT defaults flagged unconfirmed.
4. `EnableNotifications` for each notify characteristic, **serialized** (one at a
   time, advancing on each `NotificationsEnabled` ack) in the fixed §4.5 order.
5. `WriteUnlock(bytes)` (Write-With-Response) → await
   `WriteComplete(UART_SERIAL_WRITE, SUCCESS)`. On non-SUCCESS: one retry, then
   error/disconnect — **never** a false `Connected`.
6. `IssueInitialReads` (serialized) of the on-change set — **PROTOCOL-confirmed:
   `battery_level`, `riding_mode`, `safety_headroom`**; `life_odometer`
   best-effort/unverified.
7. `StartKeepalive(12000)` (idempotent) → transition to `Connected`.

### 3.6 Board-model abstraction

`generation = firmware_revision / 1000`. `BoardModel` lookup by generation gives
`tireDiameterInches` + `ridingModeNames`. **Only generation 6 (GT) is
confirmed** (`11.5"`, §3.4 table). Others fall back to GT's diameter for
estimation **flagged unconfirmed** and show raw mode numbers. Do not invent
per-model data (`docs/boards/`).

---

## 4. Architecture

### 4.1 Module graph (hexagonal)

```
:core (Kotlin/JVM, NO android.*)  — reducer + controller + domain — 100% unit-tested
   ^                    ^
:ble (Android library)  :app (Wear OS application)
 GATT adapter + queue    Compose UI + ViewModel + FGS + Health Services + adapters
        ^                    |
        +--------------------+   (:app depends on :core and :ble; :ble on :core)
```

**Dependency rule:** `:core` depends on nothing Android (enforced: no `android.*`
import in `:core`, verified by a test/lint). `:core` defines **ports**;
`:ble`/`:app` provide **adapters**.

### 4.2 Ports (interfaces in `:core`) — see §4.5 for exact signatures

`OnewheelTransport` (scan/connect/notify/read/**writeUnlock only**/disconnect/
closeGatt; emits `Flow<TransportEvent>`) · `Clock` (monotonic,
`SystemClock.elapsedRealtime`-backed) · `Ticker` · `Haptics` · `RideRecorder` ·
`RideLog` · `UnlockConfig` · `SettingsStore` · `Logger`.

### 4.3 Core components (pure Kotlin, unit-tested)

`TelemetryDecoder` (§3.2) · `TelemetryReducer`/`ConnectionStateMachine`
(**pure** `reduce(state,event)->Reduction`, §4.5.6) · `OnewheelController` (thin
effect-runner merging all inputs on one dispatcher, §4.4) · `RpmSpeed` (§5.1) ·
`DistanceIntegrator` (§5.2) · `RangeEstimator` (§5.3) · `HalfwayWarning` (§5.4) ·
`KeepaliveScheduler` · `BoardModels` (§3.6) · `UnlockBytes` (§3.3, §4.5.7) ·
name-prefix matcher (in the reducer).

### 4.4 Concurrency model (committed)

- `ConnectionStateMachine.reduce` is a **pure function** — no coroutines, no I/O,
  fully table-testable.
- `OnewheelController` is the **effect-runner**: it merges **all** inputs —
  transport events, ticker ticks, user intents (start/stop, page nav),
  recorder-state changes — into **one** `Flow` (Channel/merge) consumed by **one
  coroutine on one single-threaded dispatcher**. It feeds each event to `reduce`,
  applies the returned `Command`s against the ports, and re-posts any adapter
  callbacks onto that dispatcher (callbacks never touch state directly). This
  makes the whole domain single-threaded by construction.
- **Single-threading the machine does NOT satisfy Android's one-outstanding-
  GATT-op rule** — that is the `:ble` queue's job (§4.5.4 / §6.1).
- Tests: reducer via pure table tests (no dispatcher); controller via an
  integration test with `FakeTransport` + `FakeTicker` + `StandardTestDispatcher`
  (zero real time); `Turbine` asserts ordered `StateFlow<UiState>` emissions.

### 4.5 Core contracts (FROZEN — normative)

> These declarations are copied verbatim into `:core`
> (`app.floatface.core`). Adapters and tests build against them; **no divergence**.
> No `android.*` types appear here.

#### 4.5.1 Characteristics & GATT status
```kotlin
enum class OwCharacteristic(val uuid: String) {
    FIRMWARE_REVISION("e659f311-ea98-11e3-ac10-0800200c9a66"),
    UART_SERIAL_READ ("e659f3fe-ea98-11e3-ac10-0800200c9a66"),
    UART_SERIAL_WRITE("e659f3ff-ea98-11e3-ac10-0800200c9a66"),
    BATTERY_LEVEL    ("e659f303-ea98-11e3-ac10-0800200c9a66"),
    SPEED_RPM        ("e659f30b-ea98-11e3-ac10-0800200c9a66"),
    RIDING_MODE      ("e659f302-ea98-11e3-ac10-0800200c9a66"),
    SAFETY_HEADROOM  ("e659f317-ea98-11e3-ac10-0800200c9a66"),
    MOTOR_CONTROLLER_TEMP("e659f310-ea98-11e3-ac10-0800200c9a66"),
    BATTERY_LOW_TEMP ("e659f315-ea98-11e3-ac10-0800200c9a66"),
    STATUS           ("e659f30f-ea98-11e3-ac10-0800200c9a66"),
    TRIP_ODOMETER    ("e659f30a-ea98-11e3-ac10-0800200c9a66"),
    LIFE_ODOMETER    ("e659f319-ea98-11e3-ac10-0800200c9a66"),
    TRIP_AMP_HOURS   ("e659f313-ea98-11e3-ac10-0800200c9a66"),
    TRIP_REGEN_AMP_HOURS("e659f314-ea98-11e3-ac10-0800200c9a66");
    companion object {
        const val SERVICE_UUID = "e659f300-ea98-11e3-ac10-0800200c9a66"
        // Notify set, in the fixed serialization order used at connect:
        val NOTIFY_SET = listOf(
            BATTERY_LEVEL, SPEED_RPM, RIDING_MODE, SAFETY_HEADROOM,
            MOTOR_CONTROLLER_TEMP, BATTERY_LOW_TEMP, STATUS,
            TRIP_ODOMETER, LIFE_ODOMETER, TRIP_AMP_HOURS, TRIP_REGEN_AMP_HOURS)
        // Initial read burst (PROTOCOL-confirmed first three; last is best-effort):
        val INITIAL_READS = listOf(BATTERY_LEVEL, RIDING_MODE, SAFETY_HEADROOM, LIFE_ODOMETER)
        fun fromUuid(u: String): OwCharacteristic? = entries.find { it.uuid.equals(u, true) }
    }
}
data class GattStatus(val code: Int) { val isSuccess get() = code == 0
    companion object { val SUCCESS = GattStatus(0) } }
enum class GattOp { DISCOVER, ENABLE_NOTIFY, READ, WRITE, MTU, CONNECT }
```

#### 4.5.2 Transport events (adapter → core; Android-free payloads)
```kotlin
sealed interface TransportEvent {
    data class DeviceFound(val deviceId: String, val name: String?, val serviceUuids: List<String>) : TransportEvent
    data object Connected : TransportEvent
    data class ServicesDiscovered(val status: GattStatus, val available: Set<OwCharacteristic>) : TransportEvent
    data class NotificationsEnabled(val char: OwCharacteristic, val status: GattStatus) : TransportEvent
    data class ReadComplete(val char: OwCharacteristic, val value: ByteArray, val status: GattStatus) : TransportEvent
    data class WriteComplete(val char: OwCharacteristic, val status: GattStatus) : TransportEvent
    data class CharacteristicChanged(val char: OwCharacteristic, val value: ByteArray) : TransportEvent // notifications only
    data class MtuChanged(val mtu: Int, val status: GattStatus) : TransportEvent
    data class Disconnected(val status: GattStatus) : TransportEvent
    data object ScanStopped : TransportEvent
    data class ScanFailed(val reason: String) : TransportEvent
    data class OperationFailed(val op: GattOp, val status: GattStatus) : TransportEvent // incl. per-op timeout
}
```
(`equals`/`hashCode` for the `ByteArray`-carrying classes must be
content-based; provide explicit overrides or wrap in a value holder.)

#### 4.5.3 Merged input alphabet & user intents
```kotlin
sealed interface Event {
    data class Transport(val e: TransportEvent) : Event
    data object KeepaliveTick : Event
    data object ScanTimeout : Event
    data object LivenessTimeout : Event                    // >5s no notification
    data class User(val intent: UserIntent) : Event
    data class Recorder(val state: RecordingState) : Event
}
sealed interface UserIntent {
    data object ToggleRecording : UserIntent
    data object NextPage : UserIntent
    data object PrevPage : UserIntent
    data class SelectPage(val index: Int) : UserIntent
    data object Shutdown : UserIntent
    data object Retry : UserIntent                          // manual retry from a terminal error
}
```

#### 4.5.4 Commands (core → effect-runner)
```kotlin
sealed interface Command {
    data object StartScan : Command
    data object StopScan : Command
    data class  Connect(val deviceId: String) : Command
    data object CloseGatt : Command
    data class  EnableNotifications(val char: OwCharacteristic) : Command
    data class  Read(val char: OwCharacteristic) : Command
    data class  IssueInitialReads(val chars: List<OwCharacteristic>) : Command
    data class  WriteUnlock(val bytes: ByteArray) : Command
    data class  StartKeepalive(val intervalMs: Long) : Command
    data object StopKeepalive : Command
    data class  Buzz(val pattern: BuzzPattern) : Command
    data object Disconnect : Command
    data object StartRecording : Command
    data object StopRecording : Command
}
```

#### 4.5.5 Connection state, UI state, telemetry
```kotlin
sealed interface ConnectionState {
    data object Scanning : ConnectionState
    data object ScanTimedOut : ConnectionState              // still scanning underneath
    data object Connecting : ConnectionState
    data object Discovering : ConnectionState
    data object Subscribing : ConnectionState
    data object Unlocking : ConnectionState
    data object Connected : ConnectionState
    data object Rescanning : ConnectionState
    data object UnlockNotConfigured : ConnectionState
    data object ShuttingDown : ConnectionState              // terminal (teardown)
    data object ServiceNotFound : ConnectionState           // terminal setup error
    data object WriteCharNotFound : ConnectionState         // terminal setup error
    data class  Error(val message: String) : ConnectionState
}
enum class RecordingState { Idle, Recording }
enum class BuzzPattern { HalfwayWarning }
enum class SpeedUnit { MPH, KMH }; enum class TempUnit { F, C }

data class TelemetrySnapshot(
    val batteryLevel: Int? = null, val speedRpm: Int? = null, val speedMph: Double? = null,
    val ridingMode: Int? = null, val safetyHeadroom: Int? = null,
    val motorTempAF: Int? = null, val motorTempBF: Int? = null,
    val batteryTempAF: Int? = null, val batteryTempBF: Int? = null,
    val status: Int? = null, val tripOdometer: Int? = null, val lifeOdometer: Int? = null,
    val tripAmpHours: Int? = null, val tripRegenAmpHours: Int? = null,
    val firmwareRevision: Int? = null,
) { companion object { val EMPTY = TelemetrySnapshot() } }

data class RideState(
    val recording: RecordingState = RecordingState.Idle,
    val distanceMiles: Double = 0.0,
    val estimatedRangeMiles: Double? = null,
    val halfwayWarningActive: Boolean = false,
)
data class UiState(
    val connection: ConnectionState = ConnectionState.Scanning,
    val telemetry: TelemetrySnapshot = TelemetrySnapshot.EMPTY,
    val ride: RideState = RideState(),
    val board: BoardModel = BoardModel.GT,
    val page: Int = 0,
    val error: AppError? = null,          // §8a
)
```

#### 4.5.6 Reducer & effect-runner
```kotlin
data class Reduction(val state: ConnectionState, val commands: List<Command> = emptyList())
interface ConnectionStateMachine { fun reduce(state: ConnectionState, event: Event): Reduction }
// OnewheelController exposes: val uiState: StateFlow<UiState>; fun submit(intent: UserIntent)
// and runs the merged-event loop; it lives in :core and is app-scoped.
```
**Teardown invariant:** on `UserIntent.Shutdown` the machine transitions to
`ShuttingDown` **before** emitting `Disconnect`+`CloseGatt`, so the resulting
`Disconnected` event is a no-op (no rescan). This is the Android analogue of
nulling `_device` before `unpairDevice` (`OnewheelConnection.mc:420-431`). A
`Disconnected` received in any non-terminal state → reset **link** state (not
ride state) + `StartScan` (rescan).

#### 4.5.7 Ports & pure helpers
```kotlin
interface OnewheelTransport {
    val events: Flow<TransportEvent>
    fun startScan(); fun stopScan()
    fun connect(deviceId: String); fun closeGatt()
    fun discoverServices()
    fun enableNotifications(char: OwCharacteristic)
    fun read(char: OwCharacteristic)
    fun writeUnlock(value: ByteArray)      // ONLY write; adapter hard-codes UART_SERIAL_WRITE
    fun disconnect()
}
interface Clock { fun elapsedRealtimeMs(): Long; fun wallClockMs(): Long }
interface Cancellable { fun cancel() }
interface Ticker { fun schedule(intervalMs: Long, repeat: Boolean, action: suspend () -> Unit): Cancellable }
interface Haptics { fun buzz(pattern: BuzzPattern) }
interface RideRecorder {
    val state: StateFlow<RecordingState>
    suspend fun start(); suspend fun stop()
    fun onTelemetry(snapshot: TelemetrySnapshot, nowMs: Long)   // persists via RideLog + Health Services location
}
interface RideLog { fun open(sessionId: String); fun append(line: RideLogSample); fun close() }
interface UnlockConfig { fun unlockBytes(): ByteArray? }        // null/placeholder => not configured
interface SettingsStore {
    val speedUnit: StateFlow<SpeedUnit>; val tempUnit: StateFlow<TempUnit>
    suspend fun setSpeedUnit(u: SpeedUnit); suspend fun setTempUnit(u: TempUnit)
}
interface Logger { fun d(tag: String, msg: String); fun w(tag: String, msg: String); fun e(tag: String, msg: String, t: Throwable? = null) }

data class BoardModel(val generation: Int, val tireDiameterInches: Double, val ridingModeNames: Map<Int, String>, val confirmed: Boolean) {
    companion object {
        val GT = BoardModel(6, 11.5, mapOf(3 to "Bay",4 to "Roam",5 to "Flow",6 to "Highline",7 to "Elevated",8 to "Apex"), true)
        fun forGeneration(gen: Int): BoardModel = if (gen == 6) GT else GT.copy(generation = gen, confirmed = false, ridingModeNames = emptyMap())
    }
}
object UnlockBytes {
    fun fromHex(hex: String): ByteArray?      // null on odd length / non-hex
    fun isConfigured(bytes: ByteArray?): Boolean // == 20 bytes AND not all-zero
}
```

---

## 5. Derived-metric algorithms (exact, from `garmin-app`)

### 5.1 Speed
`mph = (PI * tireDiameterInches * rpm * 60) / 63360.0`. Labeled "mph (est.)".
GPS cross-check within ~2–3% on real GT rides.

### 5.2 Distance integration
`DistanceIntegrator.onSpeed(mph: Double, nowMillis: Long)` — time injected by
caller (deterministic under `FakeClock`, using `Clock.elapsedRealtimeMs`). Only
accumulates while recording. On each sample:
`elapsedHours = (now-lastTs)/3.6e6; trip += mph*elapsedHours`. `lastTs` resets to
null on **ride start, connect, and disconnect** (so first-after-start and
reconnect-gap samples integrate 0). Trip/range persist across a transient BLE
drop (ride state, not link state).

### 5.3 Self-calibrating range
`consumed = startBattery - currentBattery; range = if (consumed<=0) null else
(tripMiles/consumed)*currentBattery`.

### 5.4 Halfway warning
Latches once per ride; fires when `currentBattery <= startBattery/2` (integer
division). On fire: `Haptics.buzz(HalfwayWarning)` + persistent "Past halfway —
head back". Reset on ride start. Threshold left as-is per `PROTOCOL.md` battery-
nonlinearity caveat — do not "fix" without evidence.

---

## 6. Testing / TDD strategy

### 6.1 What is tested where

| Layer | Framework | Runs | Focus |
|---|---|---|---|
| `:core` | **JUnit5** + kotlin-test-junit5 + coroutines-test + Turbine | JVM (Maven Central only) | ~100% of logic: decoder, reducer table (§6.3), controller integration, speed/distance/range/halfway, unlock, name-match, CCCD ordering |
| `:ble` | **JUnit4 + Robolectric 4.13** | JVM + Android SDK | GATT **op-queue** (one-at-a-time, timeout), Android-callback↔`TransportEvent` translation, `OwCharacteristic`↔UUID, writeUnlock hard-codes target |
| `:app` | JUnit4 + Robolectric + Compose UI test | JVM + Android SDK | ViewModel state mapping, page render per §8.3 state, ExerciseConfig construction, recorder/RideLog wiring, DataStore settings |
| Screenshots | **Roborazzi** (JVM) | JVM + SDK | each §8.3 state at ~450×450 to catch round-inset overflow |
| On-device E2E | manual (real board) | Pixel Watch | out of automated scope |

`:core` uses the **java-test-fixtures** plugin: `core/src/testFixtures/kotlin`
holds `FakeTransport`, `FakeTicker`, `FakeClock`, `FakeHaptics`,
`FakeRideRecorder`, `FakeRideLog`, `FakeUnlockConfig`, `FakeSettingsStore`, and
the byte-vector fixtures — **pure Kotlin, no JUnit/assertion imports**. Consumers
add `testImplementation(testFixtures(project(":core")))`. `:core` sets
`tasks.withType<Test>{ useJUnitPlatform() }`; `:ble`/`:app` stay on
JUnit4+Robolectric with `testOptions.unitTests.isIncludeAndroidResources = true`.

### 6.2 Decoder fixtures (arithmetic-verified)

- `battery_level` `00 40` → 64.
- `motor_controller_temp` **`20 1B`** (bytes [32,27]) → (32°C,27°C) → (90°F,81°F).
  *(Note: the big-endian u16 misread of these bytes is 8219 — the source of
  `PROTOCOL.md`'s confusing "82 19 / 8219". Do not use `82 19` as the fixture; it
  decodes to (−126°C,25°C).)*
- `battery_low_temp` `1d 1c` (bytes [29,28]) → (29°C,28°C) → (84°F,82°F).
- **Negative-temp path** `fb fe` (bytes [−5,−2]) → (−5°C,−2°C) → (23°F,28°F) —
  exercises the `s8` sign.
- u16 high-byte parse check: `08 00` → 2048 (positive; a plain parse test).

### 6.3 Reducer is the crown-jewel test target

Table-driven pure tests over `reduce(state, event) → (nextState, commands)`
covering the full happy path (§3.5) and every edge: scan→found-by-name→connect→
discovered→serialized CCCD enables (assert one `EnableNotifications` per ack, in
`NOTIFY_SET` order)→unlock write→`WriteComplete(SUCCESS)`→initial reads→keepalive
scheduled→`Connected`; scan timeout (30 s)→`ScanTimedOut`; `Disconnected`
mid-session→link reset + rescan; **`Shutdown`→`ShuttingDown` before `Disconnect`,
then `Disconnected` is a no-op**; unlock write non-SUCCESS→retry→error;
`ServicesDiscovered` missing `UART_SERIAL_WRITE`→`WriteCharNotFound`; missing
service→`ServiceNotFound`; unlock bytes not configured→`UnlockNotConfigured` (no
write); keepalive cadence under `FakeTicker`; `LivenessTimeout`→re-unlock;
name-prefix match vs service-UUID fallback; **read-only assertion: across all
paths the only write command is `WriteUnlock`** and it only ever targets
`UART_SERIAL_WRITE`; interleave `KeepaliveTick` with `CharacteristicChanged` and
assert deterministic ordering; on `Disconnected`, live telemetry fields reset to
null while ride state persists.

### 6.4 Workflow

Per component: write the failing test list first (from §5/§6), minimal impl to
green, refactor. `gradle test` is the green bar. Android adapters are TDD'd
against the same ports with Robolectric.

---

## 7. Activity recording (Health Services)

- **Health Services provides live data only; the app persists everything.**
- `HealthServicesClient.exerciseClient`: call `getCapabilitiesAsync()` at start,
  verify `LOCATION`/`DISTANCE`/`SPEED` supported, then
  `ExerciseConfig.Builder(<supported GPS-bearing type: WALKING or
  INLINE_SKATING>)` with `.setIsGpsEnabled(true)` and `DataType.LOCATION` +
  distance/speed. `ExerciseType.UNKNOWN` yields no GPS — **do not use it**.
- Register an `ExerciseUpdateCallback`; capture `LOCATION` points into the ride
  log. Board telemetry (RPM, battery %, temps, mode, raw safety_headroom,
  amp-hours, status) is persisted by the app via `RideLog` (§4a schema) — Health
  Services takes no custom metrics. **Health Connect is not the on-watch store**;
  it's at most a later phone-side WorkManager sync (§14).
- Start/Stop is user-driven (§8.2) and auto-saved on service stop if recording
  (`saveIfRecording` analogue). On (re)start after process death, detect an
  in-progress exercise via `getCurrentExerciseInfoAsync` and **re-register** the
  callback rather than starting a new exercise (§9a).
- **GPS gotcha:** the Garmin bug (GPS silently not recording) maps here to
  forgetting GPS enablement / the location permission — covered by tests + the
  §11 checklist.

---

## 8. UI / UX (Wear Compose)

### 8.1 Pages (rotary-primary pager; matches `garmin-app`'s 4)

0 **Ride** — big speed (unit per §8.5, "est."), battery % (color + always-visible
numeric), recording state, halfway warning, scan-timeout hint. 1 **Stats** —
Trip (est.), Range left (est.), halfway note. 2 **Board** — mode name, motor
temps A/B, battery temps A/B, life odometer, raw safety_headroom labeled `s_h`
("meaning unconfirmed"). 3 **Diagnostics** — trip/regen amp-hours (raw), status
raw, firmware/gen, connection detail. Header: status line + clock; bottom:
`HorizontalPageIndicator`.

### 8.2 Navigation & input

- **Rotary crown is primary** page navigation (analogue of Garmin Up/Down);
  horizontal swipe secondary.
- **Start/Stop**: large bottom-anchored on-screen button on the Ride page. The
  single Pixel Watch side button is system-reserved (not remappable) — no
  physical-button mapping.
- The **foreground service owning BLE/keepalive/recording is independent of the
  Activity**: swipe-to-dismiss / task removal only hides the UI; it does **not**
  stop recording or keepalive; re-launch re-attaches to the running service. A
  ride ends only via explicit **Stop**. No "confirm exit" modal. Test:
  `onTaskRemoved` does not tear down the service while recording.

### 8.3 States the UI must represent

`Scanning…`, `Board not found — is your phone's Bluetooth off?` (after 30 s),
`Connecting…`, `Discovering…`, `Subscribing…`, `Unlocking…`, `Connected`,
`Disconnected, rescanning…`, `Unlock bytes not configured`, `ShuttingDown`,
`Service not found`, `uart_serial_write not found`, plus every `AppError` (§8a).
Battery color: ≥75 blue, ≥50 green, ≥25 yellow, else red; null grey — always with
a redundant numeric % (accessibility, §8.6).

### 8.4 Round-display layout

Wear Compose scaffold + `ScalingLazyColumn` + `CurvedText` header where apt;
respect round insets; Roborazzi screenshots (§6.1) guard overflow.

### 8.5 Ambient / always-on & units

Support ambient (low-power) while recording (keep speed + battery visible) via
Compose ambient helpers / `AmbientLifecycleObserver` (note `AmbientModeSupport`
is deprecated); `androidx.wear:wear` in the catalog. Unit toggle (°F/°C, mph/kmh)
persisted in `SettingsStore` (DataStore); default **°F / mph**.

### 8.6 Accessibility

`contentDescription`/semantics on key values + the recording toggle; honor
system font scaling; battery uses a non-color-only cue (numeric + label at low
levels).

## 8a. Error taxonomy

```kotlin
sealed interface AppError {
    data object BluetoothOff : AppError            // watch's own adapter disabled (≠ phone-BT hint)
    data object LocationServicesOff : AppError     // system location off (blocks scan match + GPS)
    data class  PermissionDenied(val perm: String, val permanent: Boolean) : AppError
    data class  ConnectFailed(val status: Int, val attempts: Int) : AppError   // incl. status 133
    data object ScanTooFrequent : AppError
    data object NotificationEnableFailed : AppError
    data object UnlockWriteFailed : AppError
    data object ConnectTimeout : AppError
    data object BoardOutOfRange : AppError
    data object HealthServicesUnavailable : AppError
    data object ExerciseOwnershipLost : AppError
    data class  Fatal(val message: String) : AppError
}
```
Each maps to a §8.3 UI state and a machine transition (retry/rescan/terminal).
Terminal setup errors (`ServiceNotFound`, `WriteCharNotFound`) offer a manual
`Retry` (disconnect+close+backoff rescan) rather than the reference's silent
stuck state.

## 8b. Preconditions & recovery

Detect + surface (re-checked on resume, since a rider can toggle mid-session):
(a) runtime permission denied/permanently-denied → rationale re-prompt / deep
link to settings; (b) **watch's own Bluetooth off** (`BluetoothAdapter` +
`ACTION_REQUEST_ENABLE`); (c) system **Location Services off**. Each is an
explicit `AppError` + testable ViewModel input.

---

## 9. Lifecycle, foreground service & power

- A **foreground service** owns BLE + keepalive + recording. Manifest:
  `android:foregroundServiceType="connectedDevice|location|health"` and
  `FOREGROUND_SERVICE_HEALTH` (required for `ExerciseClient` on Wear OS 5 /
  Android 14 — without the `health` type `startForeground` throws and no ride
  records). Holds a `PARTIAL_WAKE_LOCK` only while connected.
- Ongoing Activity API surfaces the ride. On stop: `saveIfRecording()` → teardown
  (`Shutdown` intent → `ShuttingDown` → disconnect + `gatt.close()`; §4.5.6).
- Keepalive coroutine runs in the **service scope** (not UI-scoped) so it fires
  in Doze/ambient.

## 9a. Process-death recovery

`onStartCommand` returns `START_STICKY`. Persist minimal ride state (start
battery, accumulated distance, halfway latch, ride start `elapsedRealtime` +
wall-clock) so a restart restores it. On (re)start the service detects an
in-progress exercise (`getCurrentExerciseInfoAsync`) and re-registers the
`ExerciseUpdateCallback` instead of starting a new exercise. Tests cover restore.

---

## 10. Configuration & secrets (unlock bytes)

- Per-owner, sensitive, **never committed** (like `LocalConfig.mc`).
- Provisioning: gitignored **`wear-app/unlock.properties`**, key
  `onewheel.unlockBytesHex=<40 hex chars>`, read by the `:app` Gradle build into
  `BuildConfig.UNLOCK_BYTES_HEX`. `:app` sets `buildFeatures { buildConfig =
  true }` (off by default in AGP 8). The Gradle read defaults to `"00"*20` when
  the file/key is absent, so a clean checkout / CI `assembleDebug` and all tests
  build **without** the secret.
- `:core` `UnlockBytes.fromHex` (null on odd/invalid hex); the `:app`
  `UnlockConfig` adapter does `BuildConfig.UNLOCK_BYTES_HEX → fromHex → validate`,
  mapping null/invalid/all-zero to `UnlockNotConfigured` (§8.3).
- Ship `unlock.properties.example` (all-zero) with a pointer to `README.md`
  capture steps and the `LocalConfig.mc.example` security note verbatim.
  `.gitignore` excludes `unlock.properties` + `local.properties` (done).

---

## 11. Permissions & manifest

```xml
<uses-feature android:name="android.hardware.type.watch"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.BLUETOOTH"     android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/> <!-- API-30 scan path + Health Services GPS -->
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION"/> <!-- health FGS-type prerequisite -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
```
`neverForLocation` is viable (name-prefix match + connect works on API 31+ with
`BLUETOOTH_SCAN` alone). `ACTIVITY_RECOGNITION` is required at runtime as the
health-FGS-type prerequisite (no heart-rate data used; prefer it over deprecated
`BODY_SENSORS`). A permission-gating layer in `:app`; `:ble` assumes granted and
fails cleanly (typed error) on `SecurityException`.

---

## 12. Build & tooling (verified in this environment)

- **Gradle 8.14.3** (wrapper pinned), **AGP 8.7.3**, **Kotlin 2.0.21**;
  `compileSdk 35`, `minSdk 30` (FIXED by Health Services), `targetSdk 34`.
- Every module: `compileOptions { source/target = VERSION_17 }` +
  `compilerOptions.jvmTarget = JVM_17`; **do NOT** call `jvmToolchain(17)` (it
  provisions/downloads a JDK). Run on JDK 21.
- `:core` = `org.jetbrains.kotlin.jvm` only (no Android; tests need only Maven
  Central — the fast green loop) + `java-test-fixtures`.
- `:ble` `namespace app.floatface.ble` (`com.android.library`); `:app`
  `namespace/applicationId app.floatface.wear` (`com.android.application`).
- **Compose Compiler plugin** `org.jetbrains.kotlin.plugin.compose` (version = the
  Kotlin version) applied in `:app`; **do NOT** use
  `composeOptions.kotlinCompilerExtensionVersion` under Kotlin 2.x; enable
  `buildFeatures.compose = true` in `:app`.
- `gradle.properties`: `android.useAndroidX=true`, `org.gradle.jvmargs=-Xmx2g`.
- No core-library desugaring at minSdk 30 (do not enable). `:app:assembleDebug`
  uses the default debug keystore (no signing config for the build bar). Optional
  `@Config(sdk=[34])` to pin Robolectric's emulated SDK.
- Repos: `google()`, `mavenCentral()`, `gradlePluginPortal()` (all reachable).

### 12.1 Version catalog (`gradle/libs.versions.toml`, pinned)

Verified-on-resolve pins the scaffolder confirms against `google()`/Maven
Central:
- Wear Compose **1.5.0** (`androidx.wear.compose:compose-material`,
  `:compose-foundation`, `:compose-navigation`) — versioned **independently** of
  the base Compose BOM.
- Base Compose via `androidx.compose:compose-bom` (`platform()`) to align test
  artifacts: `ui-test-junit4` (testImpl), `ui-test-manifest` (debugImpl, or
  `createComposeRule` crashes), `ui-tooling-preview` (+ `ui-tooling` debug).
- `androidx.health:health-services-client` = **1.1.0-rc02** (pre-stable; **not**
  1.0.0 which never shipped).
- `androidx.activity:activity-compose` 1.9.x; `androidx.lifecycle` 2.8.x
  (incl. `lifecycle-service` for the FGS, `-runtime-compose`,
  `-viewmodel-compose`); `androidx.wear:wear` + `androidx.wear:wear-ongoing`.
- coroutines(-core/-android/-test) 1.9.0; DataStore (preferences) current.
- Test: `junit-jupiter` 5.10.x + `kotlin-test-junit5` (`:core`); `junit` 4.13.2 +
  `robolectric` 4.13 + `androidx.test:core` 1.6.x (`:ble`/`:app`); `turbine` 1.x;
  `roborazzi` current.

### 12.2 CI

`.github/workflows/ci.yml` (JDK 21, Gradle 8.14.3, AGP 8.7.3) on push/PR:
`:core:test`, `:ble:testDebugUnitTest`, `:app:testDebugUnitTest`,
`:app:assembleDebug`, Gradle cache, device-less (Robolectric/JVM only). Provides
a dummy `unlock.properties`-absent build (falls back to all-zero).

---

## 4a. Ride-log schema (persistence)

`RideLog` (port in `:core`, `:app` adapter) writes **JSONL**, one line per
sample, to app-private storage named by exercise/session id:
`{ tsElapsedMs, tsWallMs, speedRpm, mphEst, battery, motorTempA_C, motorTempB_C,
batteryTempA_C, batteryTempB_C, ridingMode, safetyHeadroomRaw, tripAmpHoursRaw,
tripRegenAmpHoursRaw, statusRaw, lat?, lon? }`. Temperatures stored raw Celsius.
Retention: keep last N rides (configurable; default 20), prune oldest. GPS points
from `ExerciseUpdate` LOCATION are merged in. `SettingsStore` (DataStore) holds
the unit preferences.

---

## 13. Object graph & wiring

Manual DI via an `AppContainer` on the `Application` (Hilt only if later added to
§12). `AppContainer` instantiates and owns lifetimes of: `OnewheelTransport`
(`:ble`), `RideRecorder`, `RideLog`, `Haptics`, `Clock`, `Ticker`,
`UnlockConfig`, `SettingsStore`, `Logger`, and the **application-scoped singleton
`OnewheelController`** (holds the state machine). The foreground service drives
the controller; the `ViewModel` observes `controller.uiState` (a shared singleton
in `AppContainer`, **not** a bound-service round-trip) so the UI survives service
rebinds/process re-attach.

## 13a. Logging & observability

`Logger` port (keeps `:core` Android-free; `:app` Logcat adapter). Debug-log BLE
state transitions, raw characteristic hex (for decoder spot-checks), and
keepalive/re-lock events. **NEVER log the unlock bytes.** Crash reporting: none
in v1.

---

## 14. Future / out of scope (v1)

Pre-GT local MD5 unlock; tiles/complications/watch-face; phone companion for
unlock-byte transfer + Health Connect sync via WorkManager; ride-history UI +
export; multi-board profiles as `docs/boards/` fills in; release buildType
(minify + `proguard-rules.pro` keeps for Health Services/Compose, gitignored
keystore, versionCode/Name). **Riding-mode switching — permanently excluded**
(safety, §1.2).

---

## 15. Directory layout (target)

```
wear-app/
  settings.gradle.kts · build.gradle.kts · gradle.properties
  gradle/libs.versions.toml · gradle/wrapper/…            (Gradle 8.14.3, present)
  unlock.properties.example · .gitignore                   (.gitignore present)
  .github/workflows/ci.yml
  core/  (kotlin-jvm + java-test-fixtures)
    src/main/kotlin/app/floatface/core/…
    src/test/kotlin/…   src/testFixtures/kotlin/…          (fakes + byte vectors)
  ble/   (com.android.library, namespace app.floatface.ble)
    src/main/kotlin/app/floatface/ble/…   src/test/kotlin/…
  app/   (com.android.application, app.floatface.wear)
    src/main/AndroidManifest.xml  (standalone meta-data + watch feature)
    src/main/kotlin/app/floatface/wear/…   src/main/res/…  (strings, mipmaps porting FFicon)
    src/test/kotlin/…
  README.md   (build/run; links to root README capture steps)
```
Package root `app.floatface`. Launcher = Wear `ComponentActivity` (LAUNCHER
filter); manifest carries
`<meta-data com.google.android.wearable.standalone = true>` and the watch
`uses-feature`; all §8.3 copy externalized to `res/values/strings.xml`.
