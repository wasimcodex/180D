# 180D — Fitbit Air Live Heart Rate

A native Android app that reads the live BLE heart rate stream from a Google
Fitbit Air and surfaces it on the lock screen / Always-On Display, so the
number is readable at a glance without unlocking the phone.

The Air is screenless — it has no on-device display of its own. This app is
the display.

## Download / Install

This app isn't on the Play Store — it's distributed as a signed APK via
[GitHub Releases](https://github.com/wasimcodex/180D/releases).

1. Download the latest `app-release.apk` from the
   [Releases page](https://github.com/wasimcodex/180D/releases/latest).
2. Android will prompt you to allow "install unknown apps" for your browser
   or file manager the first time — this is expected for any app installed
   outside the Play Store.
3. Install the APK and grant the Bluetooth/notification permissions on first
   launch.

**Optional — auto-updates:** install
[Obtainium](https://github.com/ImranR98/Obtainium) and add this repo as a
source. Obtainium checks GitHub Releases and lets you update in place without
manually re-downloading each new version.

## Why this exists

Fitbit Air only syncs to the Google Health app every ~5 minutes, which is
useless for a live number. This app connects directly over Bluetooth LE to
the Air's standard Heart Rate service (`0x180D`) and streams samples in real
time, computes heart-rate-reserve training zones, and pushes both to a
lock-screen/AOD Live Update.

## Features

- Live BPM over BLE, no cloud API, no Fitbit account
- Heart rate reserve (Karvonen/Tanaka) zone calculation, shown as a
  continuously-moving decimal (`1.7`, not a static `Zone 1`)
- Automatic reconnection with backoff if the connection drops mid-session
- Lock-screen / Always-On Display Live Update — on Android 17+ this renders
  as a native three-metric card (BPM, zone, elapsed); it degrades gracefully
  on older Android versions
- Staleness protection: a frozen reading is never shown as if it were
  current
- Local-only — no accounts, no cloud sync, no analytics

## Requirements

- A BLE heart rate tracker that exposes the standard Bluetooth Heart Rate
  service (`0x180D`) — most chest straps, watches, and fitness trackers do,
  including the Google Fitbit Air this was originally built for
- A physical Android device, API 31+ — **the emulator has no Bluetooth radio
  and cannot run any part of this app**
- Android Studio or the command line with JDK 17+ (only needed if building
  from source — see Download / Install above for a prebuilt APK)

## Building and running

```bash
./gradlew assembleDebug     # build the debug APK
./gradlew installDebug      # install to a connected device
./gradlew test              # unit tests
./gradlew lint              # Android Lint
```

Before starting a session, enable "Always visible" for the Air in Google
Health (Connections → Fitbit Air) so it advertises and the app can find and
connect to it. Once connected, the app keeps reading even if you turn that
back off.

## Project status

Core BLE connection, zone calculation, the lock-screen Live Update, and
session logging (local history + CSV export) are implemented and verified
against real hardware. See
[`CLAUDE.md`](CLAUDE.md) for full implementation details, verified hardware
facts, and remaining work.

## Out of scope

No HRV (the Air doesn't transmit RR intervals — not derivable from 1 Hz BPM
samples), no cloud sync or accounts, no Health Connect integration, no
training recommendations or coaching output.

## License

MIT — see [`LICENSE`](LICENSE).
