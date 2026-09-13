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

`:core` defines interfaces (ports); `:ble`/`:app` provide adapters. The domain is
single-threaded by construction: all inputs (BLE events, timer ticks, user
intents, recorder state) are merged into one channel consumed on one dispatcher,
which feeds the pure `reduce(state, event)` and applies the returned commands.

## Prerequisites

- JDK 17+ (built and tested here on JDK 21) and the Android SDK
  (`ANDROID_HOME` / `local.properties` `sdk.dir`), platform 35 + build-tools 35.
- The Gradle wrapper is pinned (8.14.3); use `./gradlew`.

## Your board's unlock bytes (one-time setup)

GT-class boards compute their unlock server-side (tied to your FutureMotion
account), so it cannot be baked into the app — **you capture your own board's 20
unlock bytes once**. Follow the repository root
[README "Capturing your board's unlock bytes"](../README.md#capturing-your-boards-unlock-bytes)
(the same procedure the Garmin app uses).

Then:

```bash
cp unlock.properties.example unlock.properties   # gitignored — never commit it
# edit unlock.properties: onewheel.unlockBytesHex=<your 40 hex chars>
```

The build reads it into `BuildConfig.UNLOCK_BYTES_HEX`. Without it (clean
checkout / CI), the build falls back to the all-zero placeholder and the app
shows **"Unlock bytes not configured"** instead of attempting a doomed unlock.

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
