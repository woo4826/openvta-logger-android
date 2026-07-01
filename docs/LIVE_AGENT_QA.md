# Live Agent QA

This runbook is for parallel agents validating the Android logger on a local emulator. Use it from the `openvta-logger-android` repository root only. Keep emulator sessions, `.Vta` files, ZIPs, APKs, logs, and credentials out of Git.

## Baseline Checks

Run the ordinary JVM, lint, and debug build checks before connected-device QA:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For the existing broader connected smoke test:

```bash
./scripts/emulator_verify.sh
```

Do not point FTP or Live checks at production services. Use local fake servers or disposable emulator settings.

## 60-Second Live Recording QA

The standalone Live/recording script installs the debug APK, grants runtime permissions, configures the existing debug automation extras, injects a 60-second Seoul GPS route through `cmd location` using the `gps` test provider, stops recording, and verifies the newest session file.

```bash
./scripts/live_mock_gps_60s_verify.sh
```

Default inputs and overrides:

```bash
ANDROID_SDK=/path/to/android/sdk ./scripts/live_mock_gps_60s_verify.sh
ADB=/path/to/adb ./scripts/live_mock_gps_60s_verify.sh
ANDROID_SERIAL=emulator-5554 ./scripts/live_mock_gps_60s_verify.sh
APK=/path/to/app-debug.apk ./scripts/live_mock_gps_60s_verify.sh
OUT_DIR=/tmp/openvta-live-agent-qa ./scripts/live_mock_gps_60s_verify.sh
RESET_APP_DATA=1 ./scripts/live_mock_gps_60s_verify.sh
```

Artifacts are written under `/tmp/openvta-live-agent-qa/<UTC-run-id>/` by default:

- copied latest `.Vta`
- copied latest `.meta`
- `logcat.txt`
- `crash_anr_markers.txt`
- `summary.txt`

The script fails if the latest `.Vta` has fewer than 55 raw GPS rows, fewer than 50 unique raw GPS points, any raw GPS row outside central Seoul bounds, a missing `%% End` footer, missing `imu_heading_10hz` metadata, or crash/ANR markers in the captured logcat.

On Android API 36 emulator images, `adb emu geo fix` may not update the app-visible `gps` provider. This script uses `cmd location` and sets the shell mock-location app-op before adding the test provider.

`RESET_APP_DATA=0` is the default to avoid deleting existing emulator app data. For parallel-agent validation, prefer a disposable emulator profile or set `RESET_APP_DATA=1` so stale settings cannot affect the run. This matters because current debug extras do not disable a previously configured Live upstream.

## Current Live Gaps

- Remote command inbound is not implemented yet.
- MQTT client behavior is not implemented yet.
- HTTP upstream registration exists in the app, but it still needs fake-server and emulator validation before agents treat it as covered connected QA.
