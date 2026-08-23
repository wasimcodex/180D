# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Fitbit Air Live Heart Rate (Android)

## Current implementation state

Package `com.example.a180d`, AGP 9.3.1. AGP's built-in Kotlin support compiles
`.kt` sources with no separate `org.jetbrains.kotlin.android` plugin applied —
only `org.jetbrains.kotlin.plugin.compose` (version-pinned to match, `2.2.10`)
is needed for Compose. Implemented so far:

- `MainActivity` — Compose UI (`Theme.MaterialComponents.DayNight.NoActionBar`
  window theme + a minimal Material3 `AppTheme`), permission request flow,
  binds to the service, renders BPM/connection state/staleness/error, and
  Start/End session buttons.
- `BleHeartRateService` — foreground service (`connectedDevice` type). Device
  discovery (bonded-device match, scan fallback), GATT cache `refresh()`
  reflection call before discovery, Service Changed (0x1801/0x2A05)
  resubscription, HR measurement flags-byte parsing, event-driven reconnect
  with the documented backoff schedule and 30s unrecoverable cutoff.
- `HeartRateZones` — Karvonen/Tanaka fractional zone calculation (pure,
  unit-tested). `UserSettings` persists age/resting HR via SharedPreferences;
  `MainActivity` shows a first-run setup screen that gates the main screen
  until both are set, plus an edit affordance.
- Notification: full 3-tier fallback implemented. Channel is
  `heart_rate_session_v2` at `IMPORTANCE_DEFAULT` (the original
  `heart_rate_session` channel was `IMPORTANCE_LOW`, which Android's newer
  notification UI buckets into a collapsed "Silent" section that doesn't
  reliably show on the lock screen — channel importance can't be changed
  retroactively for an existing channel ID, hence the `_v2` rename; the old
  channel is deleted on startup). `POST_PROMOTED_NOTIFICATIONS` is declared
  in the manifest (protectionLevel `normal|appop`, user-toggleable at
  Settings → Apps → [app] → Notifications → "Live updates", default-on once
  declared — see "Hard requirements for promotion" below for the settings
  intent). Both notification builders request promotion
  (`setRequestPromotedOngoing(true)`, needs `androidx.core` ≥1.17.0) and set
  `VISIBILITY_PUBLIC` — the whole point of this app is a glanceable number
  without unlocking, so redacted lock-screen content defeats the purpose.
  On `CINNAMON_BUN` (API 37 / Android 17) with
  `NotificationManager.canPostPromotedNotifications() == true`, a native
  `android.app.Notification.Builder` + `Notification.MetricStyle` renders
  BPM (critical metric), zone, and elapsed (`Metric.TimeDifference`
  chronometer, ticks natively without our involvement) directly on the lock
  screen and AOD — confirmed by the OS itself, not just requested:
  `flags` includes `PROMOTED_ONGOING` and `template` reads
  `android.app.Notification$MetricStyle` in `dumpsys notification`. Below
  that tier it falls back to a promoted `NotificationCompat` builder, and
  below that (promotion declined) a plain public/default-importance ongoing
  notification — all three are exercised by the same code path, gated on
  `Build.VERSION.SDK_INT` and the live capability check, not a device
  allowlist.
  `Notification.MetricStyle` throws if it has zero metrics — guard any
  future edit to the metric-adding logic (e.g. don't call
  `buildNotification()` before `sessionStartMs` is set, and keep the
  zero-metric fallback that adds a `FixedText` status metric).

Not yet implemented: session logging (Room/CSV) and the Glance widget.

**Verified on the Pixel 6a (2026-08-24):** connect, GATT `refresh()` cache
workaround, service discovery, HR notification subscription, live BPM in the
UI and the ongoing notification, and clean teardown on "End session" all
confirmed working against the real Air with zero disconnects over a multi-
minute session. **Not yet exercised:** the reconnect/backoff path (never
dropped during testing), the 30s unrecoverable-link message, and behavior
when `Always visible` is toggled off mid-session — those need deliberate
range-walking / setting toggles to trigger.

## What this app is

A native Android app that reads the **live BLE heart rate stream** from a Google
Fitbit Air and surfaces it on the **lock screen / Always-On Display** as an
Android Live Update, so the number is readable at a glance without unlocking the
phone.

Secondary surface: a home-screen widget fed by the same service.

The Air is screenless, so the tracker itself can never show a live reading. This
app is the display.

---

## READ THIS FIRST — things your training data will get wrong

These are the specific ways this project goes off the rails. All of them have
been verified against real hardware (2026-08-24, Pixel 6a, nRF Connect).

1. **Do NOT use the Fitbit Web API.** It is shut down as of September 2026. Any
   tutorial, Stack Overflow answer, or remembered endpoint under
   `api.fitbit.com/1/user/...` is dead. Do not import a Fitbit SDK.
2. **Do NOT use Health Connect for the live number.** Health Connect is a valid
   path for *historical* data, but the Air only syncs to the Google Health app
   roughly every 15 minutes. It cannot produce a live reading. This app does not
   touch Health Connect at all.
3. **Do NOT use the Fitbit device SDK / Fitbit Studio.** Discontinued, and the
   Air is screenless — there is no on-device app surface.
4. **The Air DOES expose a standard BLE Heart Rate Service.** Some published
   reviews claim it uses a proprietary Bluetooth implementation. That is wrong.
   `0x180D` is advertised and served. This was confirmed on hardware.
5. **Android will hand you a stale GATT service table.** See "GATT cache" below.
   This is the single most likely cause of "the heart rate service isn't there"
   during development. The service is there. The cache is lying.

---

## Verified hardware facts

Confirmed by direct inspection with nRF Connect. Treat as ground truth.

| Property | Value |
|---|---|
| Device name | `Google Fitbit Air` |
| Address | `C9:4E:95:6F:9B:04` |
| Address type | Random **static** (top two bits set) — stable, but do not hardcode |
| Device type | LE only, `BR/EDR Not Supported` |
| Advertising type | Legacy |
| Advertising interval | ~1284 ms |
| Advertised service UUID | `0x180D` (in both ADV packet and scan response) |
| Tx power | 6 dBm |

### GATT services present

```
0x1800  Generic Access
0x1801  Generic Attribute          <- Service Changed lives here
0x180A  Device Information
0x180D  Heart Rate                 <- ours
abbafd00-e56a-484c-b832-8b17cf6cbfe8   (Fitbit proprietary)
abbaff00-e56a-484c-b832-8b17cf6cbfe8   (Fitbit proprietary)
ac2f0045-8182-4be5-91e0-2992e6b40ebb   (Fitbit proprietary)
4eee1c00-4133-479b-8663-02c84bdc14be   (Fitbit proprietary)
```

Ignore the proprietary services entirely. Do not probe or reverse them.

### The characteristic

```
Service        0x180D  Heart Rate
Characteristic 0x2A37  Heart Rate Measurement   Properties: NOTIFY
Descriptor     0x2902  Client Characteristic Configuration (CCCD)
```

`0x2A38` (Body Sensor Location) and `0x2A39` (Heart Rate Control Point) are
**not** present. Do not attempt to read them.

### Observed payload

Flags byte is `0x00`. Parsed as: 8-bit BPM, sensor contact not supported, no
energy expended, **no RR intervals**.

**Consequence: HRV is not achievable from this device.** RR intervals are the
raw material for RMSSD and they are not transmitted. If the user asks for HRV,
say it requires a chest strap. Do not compute HRV from 1 Hz BPM samples — that
is not a valid derivation and must not be silently attempted.

---

## Payload parsing

Parse the flags byte properly even though it is currently `0x00`. Firmware
updates change formats.

```
byte 0 — flags
  bit 0    0 = BPM is uint8 (byte 1)
           1 = BPM is uint16 LE (bytes 1..2)
  bit 1-2  sensor contact (00/01 = not supported, 10 = no contact, 11 = contact)
  bit 3    energy expended present (uint16, follows BPM)
  bit 4    RR intervals present (uint16 each, units of 1/1024 s, to end of packet)
  bit 5-7  reserved
```

Parse in that order — field offsets are cumulative and depend on the preceding
flags. Never index a fixed offset for BPM.

Sanity-clamp implausible values (below 25 or above 230) and treat them as
dropped samples rather than displaying them.

---

## GATT cache — the critical gotcha

The Air is **bonded** to this phone because it is also the Google Health
companion device. Android persistently caches the GATT service table for bonded
devices. That cached table predates Share Heart Rate being enabled, so
`discoverServices()` returns a list **without `0x180D`**, even though the device
is actively advertising it.

Observed exactly this: services listed, no Heart Rate. After a forced refresh,
Heart Rate appeared with no other change.

Handle it two ways, both required:

**1. Force a refresh before discovery.** Android has no public API. Use the
hidden `refresh()` method via reflection. This is widely used and unsupported —
wrap it defensively and continue if it fails.

```kotlin
private fun BluetoothGatt.forceRefresh(): Boolean = runCatching {
    javaClass.getMethod("refresh").invoke(this) as Boolean
}.getOrDefault(false)
```

Call it in `onConnectionStateChange` on CONNECTED, *before* `discoverServices()`.

**2. Subscribe to Service Changed on `0x1801`** and re-run discovery when it
indicates. This is the standards-compliant path and catches later changes.

If `getService(HR_SERVICE_UUID)` returns null after both, surface a clear error
telling the user to toggle Bluetooth or reboot — do not fail silently, and do
not conclude the device lacks the service.

**Never call `removeBond()` or expose an "unpair" action.** That bond is what
Google Health uses to sync. Breaking it forces a full re-pair of the tracker.

---

## Connection constraints

- The Air serves **one** heart-rate consumer at a time. While this app holds it,
  a treadmill or Zwift cannot connect. Surface this in the UI.
- **`Always visible` gates advertising, not the connection.** It is required to
  *find and connect to* the Air (Google Health → Connections → Fitbit Air →
  Share heart rate). Once connected and subscribed to the CCCD, notifications
  keep flowing even if the user turns it back off. Verified on hardware.
    - Consequence: it is required at session start, but **not** to sustain a live
      session. Do not treat "toggle off" as a fault condition mid-session.
    - Consequence: with it off, a dropped link may not be recoverable, since
      `autoConnect=true` relies on the peripheral advertising again. See
      "Reconnection" below.
    - UNVERIFIED: whether the Air still advertises to already-bonded peers with
      the toggle off. If it does, reconnect works either way. Test before relying
      on either behaviour.
- The Air appears in Google Health as connected equipment once we subscribe to
  the CCCD — not on connect alone.
- Normal Google Health syncing continues while we are connected. Verified.
- Scanning: allow **at least 5 seconds** before declaring the device absent.
  Advertising interval is ~1284 ms.

### Reconnection

Distinguish the two failure modes and report them differently:

1. **Link dropped, Air still advertising** — reconnect with backoff, silently.
2. **Link dropped, Air not advertising** — no amount of retrying will help.
   After ~30s of failed attempts, surface an actionable message telling the user
   to re-enable `Always visible`, with a deep link to Google Health. Do not spin
   forever showing a generic "reconnecting" state.

Since Always visible costs tracker battery, the intended flow is: user enables
it → app connects → app tells them they may turn it off → app warns clearly if
the link drops and cannot be re-established.

---

## Device discovery

Do not hardcode the MAC. Resolve in this order:

1. `BluetoothAdapter.getBondedDevices()`, match on name `Google Fitbit Air`
2. Fall back to a `ScanFilter` on service UUID `0x180D`

---

## Architecture

```
BleHeartRateService (foreground service)
  └─ owns BluetoothGatt connection + reconnect loop
  └─ emits HeartRateSample(bpm, timestampMs) on a StateFlow
       ├─> LiveUpdateNotifier   (primary surface)
       ├─> GlanceWidget         (secondary surface)
       └─> SessionRepository    (Room, 1 Hz)
```

Foreground service is **mandatory** — Android kills background BLE connections.

### Manifest

```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
```

Service type: `connectedDevice`.

Do **not** request location permission. `neverForLocation` on the scan
permission avoids it, and we never need position.

### Session lifecycle

The connection is **user-triggered**, not always-on. `Always visible` costs
tracker battery and the connection occupies the Air's only sharing slot. Start
on an explicit "Start session" action; stop on "End session".

Reconnect with exponential backoff (1s, 2s, 4s, 8s, capped at 30s) while a
session is active. Never give up silently mid-session — see "Reconnection" under
Connection constraints for how to distinguish a recoverable drop from one that
needs user action.

---

## Staleness — non-negotiable

A frozen number that looks live is worse than no number. The user will act on it
believing it is current, and it will be wrong.

- Timestamp every sample on arrival.
- If the newest sample is **older than 5 seconds**, the UI must visibly degrade:
  dim the value, show the age, or show a disconnected state.
- Never render a stale BPM as though it were current.
- Never interpolate or hold the last value forward to smooth the display.

---

## Zone calculation

Zones come from **heart rate reserve (Karvonen)**, not raw max-HR percentage.
The user's resting HR is known from the tracker, so use it — it is materially
more accurate.

```
HRmax  = 208 - (0.7 * age)          // Tanaka; better validated than 220 - age
HRR    = HRmax - HRrest
target(intensity) = (HRR * intensity) + HRrest
```

Do not use `220 - age`. It has roughly ±11 bpm standard deviation.

Zone boundaries as fraction of HRR: Z1 50%, Z2 60%, Z3 70%, Z4 80%, Z5 90%, top
100%.

### Fractional zone display

The headline metric is a **decimal zone**, not an integer. `1.3` means early
Zone 1; `1.7` means about to tip into Zone 2. This is deliberate — a whole
number sits unchanging for minutes and tells you nothing about direction.

```
zone = zoneIndex + (bpm - bandLower) / (bandUpper - bandLower)
```

Below the Z1 floor, scale from resting HR to the Z1 boundary and report a value
under 1.0. Clamp the top at 5.0. Display to one decimal place.

`age` and `restingHr` are user-configurable settings. Prompt for them on first
run; do not guess or hardcode.

---

## Live Update notification (primary surface)

Android 17 added a **Metric Style** template for Live Updates aimed at health
and fitness apps: up to three metrics across AOD, lock screen, and a status bar
chip. That is the ideal target. Availability across stable vs QPR releases is
uncertain — capability-check at runtime, do not assume.

Three metrics: **BPM**, **zone (decimal)**, **elapsed**.

Fallback tiers, degrade gracefully:

1. Metric Style Live Update (Android 17+, if available)
2. Promoted ongoing notification (Android 16+)
3. Plain ongoing notification (Android 8+) — always works

### Hard requirements for promotion

A notification will not be promoted unless all of these hold:

- `FLAG_ONGOING_EVENT` set
- `contentTitle` set
- **no** `customContentView` / RemoteViews
- not a group summary (`setGroupSummary(false)`)
- not colorized
- channel importance is not `IMPORTANCE_MIN`
- `android.permission.POST_PROMOTED_NOTIFICATIONS` declared in the manifest
  (protectionLevel `normal|appop`; verified on-device — without it,
  `canPostPromotedNotifications()` is permanently false and no per-app
  toggle appears in system settings at all)

Capability checks: `Notification.hasPromotableCharacteristics()`,
`NotificationManager.canPostPromotedNotifications()`. To send the user to
settings: `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` (with
`Settings.EXTRA_APP_PACKAGE`) — verified on-device; this app's own doc draft
had guessed the wrong constant name (`ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`,
which doesn't exist) before checking the actual `android.jar`. The user-facing
toggle is labeled "Live updates" and defaults to on once the permission is
declared.

Request promotion via `NotificationCompat.Builder.setRequestPromotedOngoing(true)`
(requires `androidx.core` 1.17.0+).

Update in place with the same notification ID at ~1 Hz. Do not re-post.

---

## Widget (secondary surface)

Jetpack Glance, updated from the same service.

The 30-minute floor applies only to system-driven `updatePeriodMillis`
refreshes. Our service can call `AppWidgetManager.updateAppWidget` as often as
we like. Throttle to 1 Hz **and only on change** — each update is cross-process
IPC to the launcher and some third-party launchers get janky.

The widget must honour the same staleness rules.

---

## Session logging

Room database, one row per sample: `timestampMs`, `bpm`, `sessionId`.

At 1 Hz a 60-minute session is ~3,600 rows. Trivial. This is data the cloud API
will never provide at this resolution, so keep all of it. Batch inserts every
~10 samples rather than writing per-notification.

Include a CSV export. Do not add cloud sync, accounts, or analytics — this app
is local-only by design and handles health data.

---

## Out of scope

Do not add these unless explicitly asked:

- HRV of any kind (see payload section — not derivable)
- Cloud sync, user accounts, backend services
- Health Connect read or write
- Training recommendations, readiness scores, or coaching output
- Any Google/Fitbit account authentication

---

## Build & tooling

### Environment

- **JDK 17+**, Gradle with **Kotlin DSL** (`.kts`), Android Gradle Plugin current
- Android Studio is used to scaffold the project and read logcat. Everything
  else runs from the CLI.
- **`minSdk = 31`.** Deliberate. `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` were
  introduced in Android 12. Below 31 the app would need the legacy
  `BLUETOOTH` / `BLUETOOTH_ADMIN` permissions *plus* `ACCESS_FINE_LOCATION`
  merely to scan. This is a personal-use app on a known device, so drop the
  older branch entirely. **Do not add legacy Bluetooth permission handling.**
- `targetSdk` / `compileSdk`: latest stable. Live Update promotion APIs require
  a recent compileSdk — check availability rather than assuming.

### A physical device is mandatory

**The Android emulator has no Bluetooth radio.** Nothing in this project can be
tested on it. All verification happens on real hardware (Pixel 6a).

Set up wireless debugging early — reconnection testing requires walking out of
BLE range, which is impossible on a cable:

```bash
adb pair <phone-ip>:<pair-port>      # one time, code from phone
adb connect <phone-ip>:<port>
```

### Build, install, test, lint

```bash
./gradlew assembleDebug              # build the debug APK
./gradlew installDebug               # install to a connected device
adb logcat -s BleHeartRate:* -v time # tail app logs (use one consistent tag)

./gradlew test                       # all local JVM unit tests (app/src/test)
./gradlew testDebugUnitTest --tests "com.example.a180d.SomeClassTest"      # single test class
./gradlew testDebugUnitTest --tests "com.example.a180d.SomeClassTest.someMethod"  # single test method

./gradlew connectedDebugAndroidTest  # instrumented tests (app/src/androidTest) — requires the physical device, see below
./gradlew lint                       # Android Lint
```

Use a single consistent log tag so logcat can be filtered to just this app.
BLE failures are asynchronous and easy to miss in the full stream.

Gradle's daemon toolchain is pinned to JDK 25 (`gradle/gradle-daemon-jvm.properties`);
app source/target compatibility is Java 11 (`app/build.gradle.kts`).

### Dependencies

```
androidx.core:core-ktx            >= 1.17.0   // setRequestPromotedOngoing
androidx.compose (BOM)                        // in-app UI
androidx.glance:glance-appwidget              // home-screen widget
androidx.room:room-runtime + room-ktx         // session storage
kotlinx-coroutines-android
```

Use the platform `BluetoothGatt` APIs directly. Do **not** add a BLE wrapper
library by default — the surface area is one characteristic on one device, and
the reflection-based cache refresh needs direct access to the `BluetoothGatt`
instance.

If the connection state machine becomes unmanageable (operation serialization,
vendor-specific error codes), Nordic's Kotlin BLE Library is the sanctioned
escape hatch. Raise it as a suggestion first; do not swap it in unprompted.

### Not cross-platform

Do not propose React Native, Flutter, or KMP. Live Updates and Glance widgets
have no cross-platform equivalent — the most interesting parts of this app would
be native Kotlin anyway, plus a bridge. Native-only is strictly less work here.

### Distribution

Debug APK, sideloaded, single user. There is **no** Play Store release, so:

- no Developer Declaration Form
- no health-permissions review
- no privacy policy hosting requirement
- no signing config beyond the debug keystore

Do not add release signing, Play Console metadata, crash reporting, or analytics.

---

## Style

- Kotlin, Jetpack Compose for in-app UI, Glance for the widget
- Coroutines + Flow; no RxJava, no callbacks past the GATT boundary
- The GATT callback thread is not the main thread — dispatch accordingly
- Keep BLE logic in one class with no Android UI imports so it is unit-testable
  against a fake sample stream

---

## Definition of done for v1

- [ ] Discovers the Air without a hardcoded MAC
- [ ] Recovers from the stale GATT cache automatically
- [ ] Subscribes to `0x2A37` and parses the flags byte correctly
- [ ] Foreground service survives screen-off for a 60-minute session
- [ ] Live Update readable on the lock screen without unlocking
- [ ] Stale readings visibly degrade within 5 seconds
- [ ] Reconnects automatically after walking out of range and back
- [ ] Google Health still syncs normally throughout