# Floatface for Wear OS — Specification

> Standalone, direct-BLE Onewheel telemetry for a Google Pixel Watch (Wear OS),
> written in Kotlin. The Wear OS sibling of `garmin-app/`. No phone required.

**Status of this document:** Phase A draft (to be audited/honed in Phase B before
implementation). This spec is the single source of truth the implementation swarm
builds against.

---

## 1. Purpose, scope, and philosophy

### 1.1 What this is

A native Kotlin **Wear OS** app that runs on a **Google Pixel Watch** and talks
**directly** to a Onewheel (confirmed against **Onewheel GT**) over Bluetooth Low
Energy — no phone, no companion app, no FutureMotion app in the loop. It mirrors
the behavior already shipped and validated by the Garmin Connect IQ app in
`garmin-app/`:

- scan for the board, connect, and **unlock** it with the rider's own captured
  unlock bytes;
- **keep it unlocked** with a periodic keepalive write;
- subscribe to and decode **live telemetry** (speed, battery, riding mode, motor
  and battery temps, odometer, amp-hours, footpad status);
- show it across a small number of **glanceable pages**;
- **record a real GPS-tracked activity** (via Wear OS Health Services) with board
  telemetry attached;
- provide a **self-calibrating range estimate** and a **halfway-battery warning**.

This document ports the *proven* Garmin behavior to Wear OS idioms, and does not
re-open questions the `garmin-app`/`PROTOCOL.md` work already settled empirically.

### 1.2 Design philosophy (inherited from the project)

1. **Read-only / telemetry-only.** The app performs exactly one class of BLE
   write: the unlock/keepalive write to `uart_serial_write`. It performs **no**
   other writes — no riding-mode switching, no shaping, no settings. This is a
   hard safety rule; see `PROTOCOL.md` "Ride-mode switching investigated, NOT
   implemented" and `CONTRIBUTING.md` "What's out of scope".
2. **Show raw over guessing.** Where a value's real unit/meaning is unconfirmed
   (`safety_headroom`, `trip_amp_hours` scale), display it raw and labeled as
   such rather than asserting a calibrated interpretation. Match `garmin-app`'s
   honesty.
3. **Verify before implementing.** Every protocol constant in this spec is
   traceable to a confirmed finding in `PROTOCOL.md`. Assumptions are called out
   explicitly (§13).
4. **Don't distract the rider.** A Onewheel is a self-balancing transporter. The
   UI is glanceable; nothing on the happy path requires precise touch input while
   moving.

### 1.3 Goals

- G1. Standalone direct-BLE connection + unlock + keepalive on Wear OS.
- G2. Live decode + display of all telemetry `garmin-app` decodes.
- G3. Health Services activity recording with GPS + telemetry data points.
- G4. Self-calibrating range + halfway-battery warning, matching `garmin-app`.
- G5. A **fully unit-tested pure-Kotlin domain core** (TDD), with thin, testable
  Android adapters.
- G6. Per-owner unlock bytes provisioned at build time from a gitignored file,
  never committed (mirrors `LocalConfig.mc`).

### 1.4 Non-goals (v1)

- Any BLE write other than unlock/keepalive.
- Multi-board *simultaneous* support; one board at a time (the board only allows
  one BLE connection anyway).
- iOS / phone / tile / complication / watch-face surfaces (future, §14).
- Deriving/So­lving the unlock secret. The unlock value is captured by the owner
  exactly as in `garmin-app` (see `README.md` "Capturing your board's unlock
  bytes"). This app consumes it; it does not compute it.
- On-watch capture of unlock bytes.

---

## 2. Target platform & hardware constraints

| Concern | Decision | Rationale |
|---|---|---|
| Device | Google Pixel Watch (all gens) | User's device. |
| OS | Wear OS 3.5+ | Pixel Watch gen 1 shipped Wear OS 3.5. |
| `minSdk` | 30 | Covers Pixel Watch gen 1; Wear Compose + Health Services support it. |
| `compileSdk` / `targetSdk` | 35 / 34 | Current toolchain; `target 34` avoids new-in-35 behavior churn. |
| Language level | Java 17 / Kotlin JVM 17 | Toolchain runs JDK 21 at language level 17. |
| UI | Jetpack Compose for Wear OS | Modern standard; testable via Robolectric + Compose test. |
| Recording | Health Services (`ExerciseClient`) | The Wear-native equivalent of Garmin's `ActivityRecording`/FIT. |
| Display | ~450×450 round AMOLED | Pixel Watch is round; layout must respect round insets (§8.4). |

### 2.1 Platform facts that shape the design

- **The board allows exactly one BLE connection at a time.** With a phone
  connected via the official app, the board stops advertising entirely. The app
  must surface this clearly on scan timeout (§8.3). (README "The watch and the
  official Onewheel app can't both be connected at once".)
- **The board re-locks ~20 s after unlock.** A keepalive re-write is mandatory,
  not optional (`PROTOCOL.md` "Re-lock timing"). Interval **15 s** (proven).
- **Scan advertisements may not carry the service UUID.** Garmin's scanner never
  saw it; match by **device name prefix `"ow"`** primarily, service-UUID
  containment as a fallback (`PROTOCOL.md` "scan results never carry the service
  UUID"). Android's scanner is generally better here, but we keep the same
  robust dual match.
- **Wear OS aggressively manages background apps.** A ride can last 30+ minutes
  with the screen off/ambient. Holding a live BLE connection + recording requires
  a **foreground service** with an ongoing notification / Ongoing Activity, and
  care around Doze/ambient (§7, §9).
- **Runtime permissions** differ from Garmin's manifest model: `BLUETOOTH_SCAN`,
  `BLUETOOTH_CONNECT` (API 31+), location (BLE scan + Health Services GPS),
  `BODY_SENSORS`/`ACTIVITY_RECOGNITION` as required by Health Services,
  `POST_NOTIFICATIONS` (API 33+), `FOREGROUND_SERVICE` +
  `FOREGROUND_SERVICE_LOCATION`/`_CONNECTED_DEVICE` (§11).

---

## 3. Onewheel BLE protocol (authoritative constants)

All values below are **confirmed against a real Onewheel GT** per `PROTOCOL.md`
and encoded in `garmin-app`. This is a straight port; do not "improve" the
decoding without hardware re-validation.

### 3.1 Service & characteristics

Primary service: `e659f300-ea98-11e3-ac10-0800200c9a66`.

All characteristic UUIDs share the suffix `-ea98-11e3-ac10-0800200c9a66`; only
the 32-bit prefix varies:

| Name | UUID prefix | Use | Notify? |
|---|---|---|---|
| `firmware_revision` | `e659f311` | read at connect; gen = rev/1000 | — |
| `uart_serial_read` | `e659f3fe` | handshake (notify) | yes |
| `uart_serial_write` | `e659f3ff` | **unlock/keepalive write target** | — |
| `battery_level` | `e659f303` | battery % | on-change |
| `speed_rpm` | `e659f30b` | wheel RPM | continuous |
| `riding_mode` | `e659f302` | mode enum | on-change |
| `safety_headroom` | `e659f317` | raw (meaning unconfirmed) | yes |
| `motor_controller_temp` | `e659f310` | 2× signed-byte °C | continuous |
| `battery_low_temp` | `e659f315` | 2× signed-byte °C | yes |
| `status` | `e659f30f` | footpad bitmask | continuous |
| `trip_odometer` | `e659f30a` | raw board trip units | yes |
| `life_odometer` | `e659f319` | whole miles (GT-confirmed) | yes |
| `trip_amp_hours` | `e659f313` | raw (scale unconfirmed) | yes |
| `trip_regen_amp_hours` | `e659f314` | raw (scale unconfirmed) | yes |

> Note: `garmin-app`'s `OnewheelProfile.mc` uses `e659f315` for
> `BATTERY_LOW_TEMP_UUID`. `PROTOCOL.md` prose names `e659f31b` as
> `battery_cell_voltages` (confirmed empty on GT). We follow the **code**
> (`e659f315` = battery_low_temp), which is the validated artifact. **Flag for
> Phase B:** reconcile this against a live characteristic dump if one is
> available; if not, keep the code's UUID (it is the one that produced the
> validated temperature readings).

### 3.2 Byte order & decoding

- **All 16-bit telemetry is big-endian `uint16`** (`PROTOCOL.md` "Byte order").
- **`motor_controller_temp` and `battery_low_temp` are NOT uint16.** Each is
  **two independent signed bytes**, each a Celsius reading: `byte[0]` = sensor A,
  `byte[1]` = sensor B. Confirmed via FutureMotion's decompiled app
  (`b2.n.a()`), and re-validated on real rides. Display both; do not average.
- **`safety_headroom`** is treated by FM's app as a boolean (`value == 1`), but
  real rides show it stuck at `1`; we **display the raw number**, unlabeled as
  OK/WARN (`PROTOCOL.md` "safety_headroom decoded" / "puzzle").
- **`status`** is a footpad-engagement bitmask (left/right/both front pads);
  exact bit mapping not nailed down — decode to raw + expose a best-effort
  footpad interpretation flagged as provisional.

Decoding table (input `ByteArray` → domain value):

```
battery_level         : u16be                          -> Int  (percent 0..100)
speed_rpm             : u16be                          -> Int  (rpm)
riding_mode           : u16be                          -> Int  (enum; names §3.4)
safety_headroom       : u16be                          -> Int  (raw)
status                : u16be                          -> Int  (raw bitmask)
trip_odometer         : u16be                          -> Int  (raw)
life_odometer         : u16be                          -> Int  (miles, GT)
trip_amp_hours        : u16be                          -> Int  (raw)
trip_regen_amp_hours  : u16be                          -> Int  (raw)
motor_controller_temp : (s8 @0, s8 @1) each C->F       -> (Int,Int) °F
battery_low_temp      : (s8 @0, s8 @1) each C->F       -> (Int,Int) °F
firmware_revision     : u16be                          -> Int; generation = rev/1000
```

`C→F`: `round(c * 9/5 + 32)`. (`garmin-app` displays °F; app will offer a
unit toggle, §8.5, but the FIT/HealthServices datapoint stores a canonical unit —
Phase B to confirm °C-canonical + display conversion vs. store-as-displayed.)

Every characteristic value is only *meaningful* after unlock; before unlock the
board reports zeros / goes silent.

### 3.3 Unlock & keepalive

- Unlock value = **20 bytes**, framed `3-byte prefix + 16-byte digest +
  1-byte XOR checksum`, written to `uart_serial_write` (`e659f3ff`).
- For GT (firmware ≥ 4141) the value is **computed server-side by FutureMotion,
  tied to the rider's account** — it cannot be derived locally. The rider
  **captures their own board's 20 bytes once** (README Options A/B/C) and
  provides them to the app (§10). We **do not** implement the pre-GT MD5 scheme
  in v1 (kept as a future §14 item for older boards).
- After connecting and enabling notifications, **write the 20 unlock bytes**,
  then **re-write them every 15 s** (`KEEPALIVE_INTERVAL_MS = 15000`) for as long
  as connected. Missing the keepalive => telemetry silently drops to 0 after ~20s.
- A validation helper `UnlockBytes.validate(bytes)` checks length == 20 and the
  trailing XOR checksum consistency (checksum = XOR of the preceding 19 bytes —
  **Phase B: confirm whether the checksum is over bytes[0..18] or bytes[3..18]**;
  `PROTOCOL.md` says "3-byte prefix + 16-byte digest + 1-byte XOR checksum",
  implying the checksum covers the 19 preceding bytes). If the configured bytes
  are the all-zero placeholder, the app shows a clear "unlock bytes not
  configured" state instead of attempting a doomed unlock.

### 3.4 Riding modes (GT-confirmed enum)

| Value | Mode |
|---|---|
| 3 | Bay |
| 4 | Roam |
| 5 | Flow |
| 6 | Highline |
| 7 | Elevated |
| 8 | Apex |

Unknown values render as `"Mode <n>"`. This table is **GT-specific**; the model
abstraction (§3.6) allows other generations' tables later, defaulting to raw.

### 3.5 Reads vs. notifications

After unlock, some characteristics only **notify on change** (`battery_level`,
`riding_mode`) and some **push continuously** (`speed_rpm`,
`motor_controller_temp`, `status`). Per `PROTOCOL.md` "Reads vs. notifications",
the app should **also do a direct GATT read** of the on-change characteristics
immediately after unlock so the first screen isn't blank until the value happens
to change. Design the connection state machine to issue an initial read burst
for `battery_level`, `riding_mode`, `safety_headroom`, `life_odometer` after
CCCDs are enabled + unlock is sent.

### 3.6 Board-model abstraction

`garmin-app` hard-codes GT's tire diameter (11.5") and mode table. This spec
generalizes minimally:

- Read `firmware_revision` on connect; `generation = rev / 1000`.
- `BoardModel` lookup keyed by generation supplies `tireDiameterInches` and
  `ridingModeNames`. Only **generation 6 (GT)** is confirmed
  (`tireDiameterInches = 11.5`, mode table §3.4). All others fall back to
  GT's diameter for estimation **but flag the estimate as unconfirmed** and show
  raw mode numbers. (`docs/boards/` has the per-model status; do not invent data.)

---

## 4. Architecture

### 4.1 Module graph (Gradle multi-module, hexagonal)

```
:core   (Kotlin/JVM, NO Android deps)   <-- 100% unit-tested, TDD heart
   ^                          ^
   |                          |
:ble  (Android library)   :app (Wear OS application)
 android.bluetooth adapter    Compose UI + ViewModel + services + Health Services
        ^                          |
        +--------------------------+
             :app depends on :core and :ble
```

**Dependency rule:** `:core` depends on nothing Android. `:ble` and `:app`
depend on `:core`. `:app` depends on `:ble`. `:core` defines **ports**
(interfaces); `:ble`/`:app` provide **adapters**. This is what makes the domain
testable on the JVM without a device or the Android SDK.

### 4.2 Ports defined in `:core` (implemented by adapters)

```kotlin
// Transport abstraction over GATT. :ble implements it with android.bluetooth.
interface OnewheelTransport {
    val events: Flow<TransportEvent>            // scan/connect/discover/notify/write-ack/disconnect
    fun startScan()
    fun stopScan()
    fun connect(deviceId: String)
    fun enableNotifications(char: OwCharacteristic)
    fun read(char: OwCharacteristic)
    fun write(char: OwCharacteristic, value: ByteArray)
    fun disconnect()
}

// Wall clock + scheduling, faked in tests (no real delays).
interface Clock { fun nowMillis(): Long }
interface Ticker { fun schedule(intervalMs: Long, action: suspend () -> Unit): Cancellable }

// Rider alerts (vibration). :app implements with Wear vibrator.
interface Haptics { fun buzz(pattern: BuzzPattern) }

// Activity recording. :app implements with Health Services.
interface RideRecorder {
    val state: StateFlow<RecordingState>
    suspend fun start()
    suspend fun stop()
    fun onTelemetry(snapshot: TelemetrySnapshot)   // push latest values as data points
}

// Persisted per-owner config (unlock bytes come from BuildConfig, see §10).
interface UnlockConfig { fun unlockBytes(): ByteArray? }   // null/placeholder => not configured
```

### 4.3 Core components (all pure Kotlin, all unit-tested)

- **`TelemetryDecoder`** — pure functions `ByteArray -> value` per §3.2. No state.
- **`TelemetryState`** — immutable snapshot (`TelemetrySnapshot`) + reducer that
  folds decoded characteristic updates into a new snapshot.
- **`RpmSpeed`** — `rpmToMph(rpm, diameterInches)` (§5.1).
- **`DistanceIntegrator`** — integrates mph over elapsed wall-clock into
  trip miles; only while recording; resets per ride; ignores stale gaps (§5.2).
- **`RangeEstimator`** — self-calibrating remaining-range from distance vs.
  battery consumed this ride (§5.3).
- **`HalfwayWarning`** — fires once when battery ≤ half of ride-start battery
  (§5.4).
- **`ConnectionStateMachine`** — consumes `TransportEvent`s + `Ticker` ticks,
  emits `ConnectionState` + issues transport commands (scan→connect→discover→
  enable CCCDs→unlock→initial reads→keepalive; reconnect on drop; scan timeout).
  The single most important thing to TDD (§6.3).
- **`RideSession`** — owns start/stop, wires `DistanceIntegrator`,
  `RangeEstimator`, `HalfwayWarning`, and feeds `RideRecorder`.
- **`KeepaliveScheduler`** — uses `Ticker` to re-write unlock every 15 s.
- **`BoardModel`/generation lookup** (§3.6).
- **`UnlockBytes`** — validation (§3.3).

### 4.4 Threading / concurrency model

- Core is coroutine-based; all long-lived state lives behind a single
  `OnewheelController` exposing `StateFlow<UiState>`; the ViewModel just maps it.
- Transport callbacks (Android BLE runs on a binder thread) are marshaled into a
  single `Flow<TransportEvent>` consumed on one dispatcher, so the state machine
  is **single-threaded by construction** (no lock reasoning). Tests drive it with
  `StandardTestDispatcher` + a fake transport + fake ticker/clock — **zero real
  time** elapses.

---

## 5. Derived-metric algorithms (exact, from `garmin-app`)

### 5.1 Speed
```
circumferenceInches = PI * tireDiameterInches      // GT: 11.5"
mph = circumferenceInches * rpm * 60 / 63360.0
```
Labeled "mph (est.)". GPS cross-check on real GT rides came within ~2–3%
(`PROTOCOL.md` "Wheel diameter").

### 5.2 Distance integration
- Only accumulates **while a ride is recording** (so Trip starts at 0 on Start).
- On each speed update: `elapsedHours = (now - lastTs)/3.6e6; trip += mph*elapsedHours`.
- `lastTs` is reset to null on (dis)connect so a reconnect gap doesn't integrate a
  huge bogus jump. Trip/range are **not** cleared on a transient BLE drop (they're
  ride state, not link state) — matches `garmin-app` `resetState()`.

### 5.3 Self-calibrating range
```
consumed = startBattery - currentBattery          // percent points
if (consumed <= 0) range = null                    // not enough data yet
else range = (tripMiles / consumed) * currentBattery
```
Adapts to terrain/mode/rider automatically; deliberately not a fixed Wh model.

### 5.4 Halfway warning
- Latches once per ride. Fires when `currentBattery <= startBattery / 2`
  (integer division, matching `garmin-app`). On fire: single vibration
  (`Haptics.buzz`) + persistent on-screen "Past halfway — head back".
- Reset on ride start. (Note in `PROTOCOL.md`: battery % may be nonlinear near
  empty; threshold left as-is pending more data — do not "fix" without evidence.)

---

## 6. Testing / TDD strategy

TDD is a first-class requirement. The module split exists to make it real.

### 6.1 What is tested where

| Layer | Test type | Runs where | Coverage target |
|---|---|---|---|
| `:core` | Pure JUnit5 + kotlin-test + coroutines-test | JVM (Maven Central only) | **~100% of logic**; every algorithm + the state machine |
| `:ble` | Robolectric unit tests + fakes | JVM w/ Android SDK | Adapter mapping (event↔GATT), name-prefix matcher, CCCD ordering, permission gating |
| `:app` | Robolectric + Compose UI test + ViewModel tests | JVM w/ Android SDK | ViewModel state mapping, page rendering per state, recorder wiring |
| E2E on device | Manual (real board) | Pixel Watch | Out of automated scope |

### 6.2 Test doubles (in `:core` test + shared testFixtures)

- `FakeTransport` — script `TransportEvent`s in; assert commands out
  (scan/connect/enableNotifications/read/write/disconnect). Deterministic.
- `FakeTicker` / `FakeClock` — advance virtual time; assert keepalive cadence &
  distance integration without real delays.
- `FakeHaptics`, `FakeRideRecorder` — record calls.
- Real captured **byte vectors from `PROTOCOL.md`** used as decoder fixtures,
  e.g. `motor_controller_temp` raw `82 19` → (32°C,27°C) → (90°F,81°F);
  `battery_low_temp` `1d1c` → (29°C,28°C) → (84°F,82°F); `battery_level`
  `00 40` → 64; `custom_shaping` `08 00` → 2048 (for a negative/parse test).
  These make the decoder tests trace directly to hardware-confirmed data.

### 6.3 The connection state machine is the crown-jewel test target

Author (test-first) the full happy path and every failure edge as table-driven
tests over `(state, event) -> (nextState, commands)`:

- scan → found-by-name → connect → connected → discover → enable each CCCD in
  order → all CCCDs done → send unlock → write-ack → initial reads issued →
  keepalive scheduled → telemetry flows.
- scan timeout (30 s) → `ScanTimedOut` (still scanning) → surfaces the
  "phone Bluetooth?" hint.
- disconnect mid-ride → reset link state (not ride state) → auto-rescan.
- unlock write before `uart_serial_write` discovered → error state.
- board re-lock (telemetry → 0 with no keepalive) is *prevented* by keepalive;
  test asserts keepalive fires at 15 s cadence under `FakeTicker`.
- app shutdown → stop scan, cancel timers, disconnect/unpair, no auto-reconnect.
- unlock bytes not configured → dedicated state, no write attempted.

### 6.4 TDD workflow the swarm follows

For each `:core` component: write the failing test list first (from §5/§6), then
minimum implementation to green, then refactor. The build harness
(`gradle test`) is the green bar. Android adapters are TDD'd against the same
ports with Robolectric.

---

## 7. Activity recording (Health Services)

Wear-native analogue of Garmin's FIT recording.

- Use `HealthServicesClient.exerciseClient`. Configure an `ExerciseConfig` with
  `ExerciseType.UNKNOWN` (or closest; Onewheel has no first-class type),
  GPS enabled (`LOCATION`), and the metrics we want the watch to also collect
  (distance/speed from GPS as an independent cross-check of our RPM estimate).
- Board telemetry (RPM, battery %, motor temps, safety_headroom, mode) is pushed
  via `RideRecorder.onTelemetry` and persisted. Health Services does not accept
  arbitrary custom metrics the way FIT custom fields do, so v1 persists board
  telemetry to a **local per-ride log** (structured, e.g. a room/DataStore or
  JSONL file) timestamped to align with the exercise; export/upload is a §14
  item. **Phase B decision:** confirm the minimal viable persistence (local log
  vs. Health Connect records vs. none-but-live-only) — do not over-build.
- GPS must be explicitly enabled and permitted; Garmin's bug (GPS silently not
  recording because location events were never enabled) is the direct analogue of
  forgetting the location permission/`LOCATION` data type here — call it out in
  tests/checklist.
- Start/Stop is user-driven (§8) and also auto-saved on app stop if recording
  (mirrors `saveIfRecording`).

---

## 8. UI / UX (Wear Compose)

### 8.1 Pages (horizontal pager, matches `garmin-app`'s 4 pages)

0. **Ride** — big speed (mph est.), battery % (color-coded), recording state,
   halfway warning, scan-timeout hint.
1. **Stats** — Trip (est.) mi, Range left (est.) mi, halfway note.
2. **Board** — riding mode name, motor temps A/B, battery temps A/B, life
   odometer, raw `safety_headroom`.
3. **Diagnostics** — trip/regen amp-hours (raw), footpad/status raw, firmware/gen,
   connection status detail.

Top of every page: connection status line + clock (glanceable). Page indicator
dots at bottom (Wear `HorizontalPageIndicator`).

### 8.2 Navigation & input

- Horizontal swipe / rotary between pages (Wear `Pager` + rotary support).
- **Start/Stop recording**: a clearly-tappable button on the Ride page and/or the
  physical side button mapped where possible. (Garmin used the physical
  Start/Stop; Wear apps typically use an on-screen button — a large, bottom-anchored
  target so it's usable without precision.)
- Swipe-to-dismiss handled per Wear guidelines (don't accidentally kill an active
  ride; confirm exit while recording).

### 8.3 States the UI must represent

`Scanning…`, `Board not found — is your phone's Bluetooth off?` (after 30 s),
`Connecting…`, `Discovering…`, `Subscribing…`, `Unlocking…`, `Connected`,
`Disconnected, rescanning…`, `Unlock bytes not configured`, and an error line.
Battery color thresholds match `garmin-app`: ≥75 blue, ≥50 green, ≥25 yellow,
else red; null grey.

### 8.4 Round-display layout

Pixel Watch is round; use Wear Compose `ScalingLazyColumn`/scaffold with proper
`CurvedText` for the status/time header where appropriate, and respect
round insets. (Garmin hand-rolled round-aware text fitting; Wear Compose provides
this via components.)

### 8.5 Ambient / always-on

While recording, support **ambient mode** (low-power screen) so the rider can
glance without waking fully; keep speed + battery visible. This interacts with
the foreground service (§9). Unit toggle (°F/°C, mph/kmh) is a settings item;
default °F/mph to match `garmin-app`.

---

## 9. Lifecycle, foreground service & power

- A **foreground service** owns the BLE connection + keepalive + recording so the
  ride survives screen-off/ambient and app backgrounding. Type includes
  `connectedDevice` and `location` (for GPS recording).
- Ongoing Activity API surfaces the ride on the watch face / recents.
- The controller/state machine lives in the service (or a repository the service
  hosts); the UI observes via `StateFlow`. On service stop, `saveIfRecording()`
  then teardown (stop scan, cancel keepalive, disconnect) — the direct analogue
  of `FloatfaceApp.onStop` + `OnewheelConnection.shutdown`.
- Keepalive must keep firing in Doze/ambient during an active ride; validate the
  15 s cadence holds (coroutine on the service, not a UI-scoped scope).

---

## 10. Configuration & secrets (unlock bytes)

- The 20 unlock bytes are **per-owner, sensitive, and never committed** — exactly
  like `garmin-app/source/LocalConfig.mc` (gitignored).
- Provisioning: a **gitignored `wear-app/local.properties`** (or
  `unlock.properties`) key, e.g. `onewheel.unlockBytesHex=<40 hex chars>`, read
  by the `:app` Gradle build into a `BuildConfig` field
  (`UNLOCK_BYTES_HEX`). Provide `unlock.properties.example` with the all-zero
  placeholder and a pointer to `README.md` capture steps.
- `.gitignore` must exclude the real file. The all-zero placeholder → app shows
  "Unlock bytes not configured" (§8.3) and never attempts a doomed write.
- **Phase B decision:** BuildConfig (compile-time, matches `garmin-app`) vs.
  on-watch entry vs. Wear Data Layer from a phone. Recommend BuildConfig for v1
  (simplest, matches precedent, no phone). Document the security note verbatim
  from `LocalConfig.mc.example`.

---

## 11. Permissions & manifest

Runtime + manifest permissions (request with rationale UI on first launch):

- `BLUETOOTH_SCAN` (with `neverForLocation` **not** usable here — scan match may
  need location on some versions; evaluate), `BLUETOOTH_CONNECT` (API 31+).
- Legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` for `minSdk 30`
  BLE scanning.
- `ACCESS_FINE_LOCATION` (Health Services GPS) + `FOREGROUND_SERVICE_LOCATION`.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- `POST_NOTIFICATIONS` (API 33+) for the ongoing notification.
- Health Services may require `ACTIVITY_RECOGNITION` / `BODY_SENSORS` depending on
  requested metrics — request only what the chosen `ExerciseConfig` needs.

A permission-gating layer sits in `:app`; `:ble` assumes permissions are granted
and fails cleanly (typed error) if a `SecurityException` occurs, surfaced as a UI
state.

---

## 12. Build & tooling (verified in this environment)

- **Gradle 8.14.3** (wrapper pinned), **AGP 8.7.3**, **Kotlin 2.0.21**.
- `compileSdk 35`, `minSdk 30`, `targetSdk 34`; Java/Kotlin language level 17,
  running on JDK 21 (no toolchain download; rely on running JDK).
- `gradle.properties`: `android.useAndroidX=true`, sensible `jvmargs`.
- **Version catalog** `gradle/libs.versions.toml` for all deps.
- Repositories: `google()`, `mavenCentral()`, `gradlePluginPortal()` — all
  reachable here (Google Maven confirmed reachable).
- Key deps: Wear Compose (`androidx.wear.compose:compose-material`,
  `:compose-foundation`, `:compose-navigation`), `androidx.activity:activity-compose`,
  `androidx.lifecycle:lifecycle-*`, `androidx.health:health-services-client`,
  `androidx.wear:wear-ongoing`, coroutines; test: JUnit5 (core), Robolectric 4.13,
  `androidx.test:core`, Compose UI test, Turbine (Flow testing).
- `:core` is `org.jetbrains.kotlin.jvm` only (no Android) so its tests need only
  Maven Central — the fast, always-green TDD loop.

Module test entrypoints: `gradle :core:test`, `gradle :ble:testDebugUnitTest`,
`gradle :app:testDebugUnitTest`, and `gradle test` for all. `gradle :app:assembleDebug`
must produce an APK.

---

## 13. Assumptions & open questions (to resolve in Phase B)

1. **`battery_low_temp` UUID** `e659f315` (from code) vs. `e659f31b`
   (`battery_cell_voltages` in prose). Follow code; flag.
2. **Unlock XOR checksum span** (bytes[0..18] vs [3..18]). Validate helper must
   match reality; if uncertain, checksum validation is advisory (warn, don't block).
3. **Temperature canonical storage unit** for recording (°C vs display unit).
4. **Telemetry persistence** depth for recording (local log vs Health Connect).
5. **Start/Stop input**: on-screen button vs physical button mapping on Pixel Watch.
6. **`status` footpad bit mapping** unknown — expose raw + provisional flags only.
7. **Non-GT boards**: estimation uses GT tire diameter as fallback, flagged.
8. **`BLUETOOTH_SCAN` `neverForLocation`** viability vs. needing location for scan
   match on `minSdk 30`.

---

## 14. Future / out of scope (v1)

- Pre-GT local MD5 unlock (older boards; `unlock.py` has the scheme).
- Tiles, complications, watch face data, phone companion for unlock-byte transfer.
- Telemetry export/upload; Health Connect integration; ride history UI.
- Multi-board profiles; per-model tire/mode data as `docs/boards/` fills in.
- Riding-mode switching — **explicitly excluded** for safety (see §1.2).

---

## 15. Directory layout (target)

```
wear-app/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradle/libs.versions.toml
  gradle/wrapper/…                     (Gradle 8.14.3)
  unlock.properties.example
  .gitignore                           (excludes unlock.properties, local.properties)
  core/                                (Kotlin/JVM — TDD heart)
    src/main/kotlin/app/floatface/core/…
    src/test/kotlin/…
  ble/                                 (Android library adapter)
    src/main/kotlin/app/floatface/ble/…
    src/test/kotlin/…
  app/                                 (Wear OS application)
    src/main/AndroidManifest.xml
    src/main/kotlin/app/floatface/wear/…
    src/test/kotlin/…
  README.md                            (build/run, links to root README capture steps)
```

Package root: `app.floatface` (mirrors the Floatface identity).
