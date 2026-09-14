# Floatface for Wear OS

**Onewheel telemetry on a Google Pixel Watch — no phone required.**

The Wear OS / Kotlin sibling of [`../garmin-app`](../garmin-app). A standalone
Wear OS app that connects **directly** to a Onewheel (confirmed against a
Onewheel GT) over Bluetooth Low Energy, unlocks it, keeps it unlocked, and shows
live speed, battery, riding mode, motor/battery temps and more — while recording
a GPS-tracked activity via Health Services. No phone, no FutureMotion app in the
loop.

See [`SPEC.md`](SPEC.md) for the full design, and the repository root
[`PROTOCOL.md`](../PROTOCOL.md) for what is confirmed vs. assumed about the BLE
protocol. This app ports the *proven* behavior of the Garmin app; it does not
re-open protocol questions that project already settled on real hardware.

> **Read-only, by construction.** The only BLE write this app performs is the
> unlock/keepalive write to `uart_serial_write`. The transport port exposes a
> single `writeUnlock()` method whose adapter hard-codes that characteristic, so
> there is no code path that can write anything else. No riding-mode/shaping
> writes, ever (see SPEC §1.2 and `../CONTRIBUTING.md`).

## Architecture

Hexagonal, three Gradle modules:

| Module | What | Tested |
|---|---|---|
| `:core` | Pure Kotlin/JVM domain — telemetry decoder, derived metrics, the pure connection **reducer**, the effect-runner controller, and all ports. No `android.*`. | JUnit5 on the JVM (fast, no device) |
| `:ble` | Android adapter: a single-outstanding-operation GATT queue + `OnewheelTransport` over `android.bluetooth`. | Robolectric |
| `:app` | Wear Compose UI, ViewModel, foreground service, Health Services recorder + ride log, platform adapters, DI. | Robolectric + Compose test |
| `:phone` | Optional companion **phone** app (Compose Material3): pushes board config to the watch over the Wear Data Layer and helps install the watch app. Shares `:core`. | Robolectric + Compose test |

The wire contract between phone and watch lives in `:core` (`ConfigSync`) so both
sides reference one source of truth; the phone sends and the watch's
`BoardConfigListenerService` applies via the same pure `applyPushedConfig`.

`:core` defines interfaces (ports); `:ble`/`:app` provide adapters. The domain is
single-threaded by construction: all inputs (BLE events, timer ticks, user
intents, recorder state) are merged into one channel consumed on one dispatcher,
which feeds the pure `reduce(state, event)` and applies the returned commands.

## Prerequisites

- JDK 17+ (built and tested here on JDK 21) and the Android SDK
  (`ANDROID_HOME` / `local.properties` `sdk.dir`), platform 35 + build-tools 35.
- The Gradle wrapper is pinned (8.14.3); use `./gradlew`.

## Your board's unlock bytes + BLE MAC

GT-class boards compute their unlock server-side (tied to your FutureMotion
account), so it cannot be derived — **you capture your own board's 20 unlock
bytes once**. Follow the repository root
[README "Capturing your board's unlock bytes"](../README.md#capturing-your-boards-unlock-bytes)
(the same procedure the Garmin app uses). You can then provide them (and,
optionally, the board's BLE MAC) in either of two ways:

### Option A — configure on the watch (recommended, no rebuild)

Open the app → **Diagnostics** page → **Configure board**. Enter the 40-hex
unlock string and, optionally, the board's BLE MAC (`AA:BB:CC:DD:EE:FF`) via the
watch's keyboard/voice input. Both are persisted (DataStore) and take effect
immediately — nothing is compiled in. Setting a MAC makes the app connect to
**that specific board** instead of the first `ow…` it sees; leaving it blank
matches any Onewheel by name. Clearing the unlock bytes reverts to the app's
"Unlock bytes not configured" state.

### Option B — bake a default in at build time (optional)

```bash
cp unlock.properties.example unlock.properties   # gitignored — never commit it
# edit unlock.properties: onewheel.unlockBytesHex=<your 40 hex chars>
```

The build reads it into `BuildConfig.UNLOCK_BYTES_HEX` and uses it as the
**default** until you override it on the watch. Without it (clean checkout / CI),
the build falls back to the all-zero placeholder and the app shows **"Unlock
bytes not configured"** until you set the bytes at runtime (Option A).

### Option C — push from the companion phone app (Data Layer)

Typing a 40-hex string on a watch keyboard is miserable. Install the companion
phone app (`:phone`), type the unlock bytes and optional MAC on a real keyboard,
tap **Send to watch**, and they arrive over the Wear Data Layer and apply
immediately. See [Companion phone app](#companion-phone-app) below.

## Companion phone app

The `:phone` module is a small, **optional** provisioning helper — the watch app
is fully standalone and never needs it at ride time. It does two things:

1. **Push config to the watch.** Enter the unlock bytes / BLE MAC on the phone,
   validated against the same `:core` rules as the watch, and push them at
   `ConfigSync.PATH` over `DataClient`. The watch's `BoardConfigListenerService`
   validates and applies them (consume-once: it deletes the Data Layer item after
   applying, and every push carries a nonce so repeats still register).
2. **Detect / help install the watch app.** The watch app advertises a static
   Data Layer capability (`res/values/wear.xml` → `floatface_watch_app`). The
   phone queries `CapabilityClient` to show whether the watch app is installed on
   the connected watch, and offers **Install watch app**, which opens the watch
   app's Play Store listing *on the watch* (`RemoteActivityHelper`) so you install
   it with one tap.

### Can the phone install the watch app directly?

Not by transferring an APK. **Wear OS 3+ (Pixel Watch) has no API for a phone app
to sideload an arbitrary APK onto the watch** — the legacy embedded-app
(`wearApp`) mechanism was removed after Android Wear 1.x, and a watch can't
bootstrap-install itself over the Data Layer (something must already be listening
there). The two real paths are:

- **Google Play** — publish both apps under the same applicationId; the watch app
  then installs from the watch's Play Store (the **Install watch app** button
  deep-links straight to that listing). Works only once published.
- **ADB** — for a personal/sideloaded build (the common case here, since the
  unlock bytes are your own board's), install directly:
  ```bash
  ./gradlew :app:assembleDebug
  adb -s <watch-serial> install app/build/outputs/apk/debug/app-debug.apk
  ```
  Pair the watch for wireless debugging (Watch → Settings → Developer options →
  Wireless debugging), `adb pair`, then `adb connect`. Use `adb devices -l` to
  find `<watch-serial>`.

### Pairing requirement (important)

The Data Layer only delivers items between a phone app and watch app that share
**both** the same `applicationId` (`app.floatface.wear`) **and the same signing
key**. Debug builds share Android's default debug key, so a debug phone + debug
watch build pair out of the box. For release, sign **both** with the *same*
keystore or the push silently never arrives.

### Build / install the phone app

```bash
./gradlew :phone:assembleDebug      # -> phone/build/outputs/apk/debug/phone-debug.apk
adb install phone/build/outputs/apk/debug/phone-debug.apk   # on the phone
```

## Build, test, run

```bash
./gradlew :core:test                # pure-domain unit tests (no Android SDK needed)
./gradlew :ble:testDebugUnitTest :app:testDebugUnitTest   # adapter + UI tests (Robolectric)
./gradlew test                      # everything
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

Install on a Pixel Watch (developer options + ADB over Wi-Fi/USB):

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Using the watch and the official Onewheel app at the same time won't work** —
the board only allows one BLE connection and stops advertising once something is
connected. Turn off your phone's Bluetooth (or force-quit the Onewheel app)
before launching Floatface. If it can't find the board within 30 s, the Ride page
says so.

## Releases

CI (`.github/workflows/wear-ci.yml`) builds + unit-tests the app on every push/PR
touching `wear-app/`. To cut a release, push a tag:

```bash
git tag wear-v0.1.0 && git push origin wear-v0.1.0
```

`.github/workflows/wear-release.yml` then assembles the Wear OS release APK and
attaches it to a GitHub Release. Optional repository secrets:

- `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
  `SIGNING_KEY_PASSWORD` — sign the APK (without them it builds unsigned).
- `UNLOCK_BYTES_HEX` — pre-provision the published APK with your unlock bytes
  (otherwise ship it unprovisioned and configure on the watch, Option A above).

The release build ships with minification **off**; R8 keeps for Health Services /
Compose are staged in `app/proguard-rules.pro` but not enabled until a signed
build is validated on real hardware.

## Pages

Paged with the rotary crown (or swipe): **Ride** (speed / battery / record),
**Stats** (trip / range, both estimated), **Board** (mode / temps / odometer /
raw `safety_headroom`), **Diagnostics** (amp-hours raw / status / firmware).

## Status & safety

Early and personal, ported from a from-scratch reverse-engineering project.
Speed/distance are RPM-estimates (GT tire diameter, GPS-cross-checked to ~2–3%);
a few values are shown raw because their real units aren't confirmed. A Onewheel
is a self-balancing transporter — don't let a watch screen distract you from
riding safely. Not affiliated with Future Motion or Google.
