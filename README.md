# 180D — Fitbit Air Live Heart Rate

A native Android app that reads the live BLE heart rate stream from a Google
Fitbit Air and surfaces it on the lock screen / Always-On Display, so the
number is readable at a glance without unlocking the phone.

The Air is screenless — it has no on-device display of its own. This app is
the display.

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

- A Google Fitbit Air, already paired and bonded to the phone via Google
  Health (Connections → Fitbit Air → Share heart rate)
- A physical Android device, API 31+ — **the emulator has no Bluetooth radio
  and cannot run any part of this app**
- Android Studio or the command line with JDK 17+

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

Core BLE connection, zone calculation, and the lock-screen Live Update are
implemented and verified against real hardware. Session logging (local
history + CSV export) is not yet implemented. See
[`CLAUDE.md`](CLAUDE.md) for full implementation details, verified hardware
facts, and remaining work.

## Out of scope

No HRV (the Air doesn't transmit RR intervals — not derivable from 1 Hz BPM
samples), no cloud sync or accounts, no Health Connect integration, no
training recommendations or coaching output.
