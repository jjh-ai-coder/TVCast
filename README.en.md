# TVCast — Android TV DLNA Casting Receiver

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/jjh-ai-coder/TVCast)](https://github.com/jjh-ai-coder/TVCast/releases)

[中文说明](README.md)

TVCast turns an Android TV / set-top box into a DLNA/UPnP MediaRenderer (DMR). Any phone app that supports DLNA casting — BubbleUPnP, Bilibili, iQIYI, Tencent Video, the system gallery, and more — can discover the TV on the same LAN and push video, music or photos to the big screen.

## Features

- **Self-built UPnP stack** (SSDP announcement + M-SEARCH, SOAP control, GENA eventing, mini HTTP server) — zero third-party protocol dependencies, ~4k lines of pure Kotlin/JVM, fully unit-testable
- **Implicit play**: `SetAVTransportURI` alone starts playback (matches how most Chinese caster apps behave)
- **GENA eventing**: SEQ:0 initial snapshot + incremental LastChange notifications keep the phone-side progress bar live
- **Media3 ExoPlayer** playback engine with **FFmpeg software-decode fallback** (HEVC / AC3 / EAC3 / DTS / TrueHD / MPEG-1/2 / VP8 / VP9 / Opus / FLAC — hardware first, software fallback, a lifeline for old boxes), audio-focus handling (incl. ducking), clear error UI, and screen-resolution downsampling for photos
- **Resident foreground service** with MulticastLock, network watchdog (IP change → re-announce with persistent UDN), and boot autostart
- **3-screen TV UI**: standby / playing / settings (rename re-announces immediately)
- **Fault-tolerant**: unknown actions (e.g. `X_SetAVTransportURI`) get a standard 401 fault instead of a crash

## Verified on real hardware

- FFALCON (雷鸟) 65" TV, Android TV: casting from **Bilibili** and the **system gallery** works end to end (discovery, autoplay, playback)
- Android 15 emulator: full control-point E2E flow validated (see [中文 README](README.md) for details and the three Android-only bugs found & fixed)

## Build

```bash
git clone https://github.com/jjh-ai-coder/TVCast.git
cd TVCast
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # 75 unit tests incl. fake-control-point E2E regression
```

A `local.properties` pointing at your Android SDK is required. Release signing additionally reads `keystore.properties` (not committed): `storeFile` / `storePassword` / `keyAlias` / `keyPassword`; without it `assembleRelease` falls back to the debug signature.

Requirements: AGP 8.13.2, Kotlin 2.2.21, Gradle 9.3.1, compileSdk 34, minSdk 21 (Android 5.0+), Media3 1.4.1, Glide 4.16.0.

## Install & use

1. Grab `TVCast-vX.Y.Z.apk` from [Releases](https://github.com/jjh-ai-coder/TVCast/releases) and sideload it onto the TV
2. Open TVCast on the TV (standby screen shows the device name and `http://IP:port`)
3. On the phone (same Wi-Fi), tap the cast/DLNA button in any supported app and pick the TVCast device
4. If discovery fails: allow background running / autostart for TVCast in system settings, and check AP isolation on the router

## Architecture

```
com.tvcast/
├─ core/       CastService (foreground + MulticastLock), NetworkWatchdog, Settings, BootReceiver
├─ dlna/       Self-built UPnP stack, pure Kotlin/JVM (no Android APIs, directly unit-testable)
│   ├─ ssdp/   NOTIFY alive(30s)/byebye (6 NT/USN targets), M-SEARCH responses with randomized MX delay
│   ├─ http/   Mini HTTP server (port 80 → 49152+ fallback), description/SCPD/control/event routing
│   ├─ soap/   Dispatcher with standard faults; AVTransport (implicit play + queries), RenderingControl, ConnectionManager
│   ├─ gena/   EventBus (subscribe/renew/expire, SEQ ordering, SEQ:0 snapshot), LastChange XML builders
│   ├─ didl/   Lenient DIDL-Lite parser (multi-escape, missing namespaces, XXE-hardened, fail-soft)
│   └─ DmrStack  Orchestration: IP change → byebye → rebuild description → re-announce
├─ playback/   PlayerController (ExoPlayer state machine, cross-thread progress cache), volume, image display
└─ ui/         Single Activity, three Fragments: Standby / Playing / Settings
```

## Known limitations

- On Android 10+ some ROMs block background activity launches; if the playing screen doesn't pop up, open the app manually (playback is not interrupted)
- Exotic codecs unsupported by hardware decoding fall back to the bundled FFmpeg software decoder (v1.2.0+, LGPL v2.1; rebuild via `./tools/build_ffmpeg_ext.sh`, see the Chinese README for details)

## License

[MIT](LICENSE)
