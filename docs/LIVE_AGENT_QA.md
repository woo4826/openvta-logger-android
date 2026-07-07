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
NEW_VTA_WAIT_SECONDS=90 ./scripts/live_mock_gps_60s_verify.sh
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

## Current Live Coverage

- Opt-in OpenVTA Live pairing uses the six-digit registration flow. QR scan and
  gallery QR import are convenience paths; direct code entry remains required
  for QA.
- HTTP is the primary upstream path for device status, recording metadata,
  telemetry, and VTA bytes.
- MQTT fallback subscribes to the per-device server ack topic and only clears
  ranged telemetry outbox entries after a matching server ack is observed.
- WSS command handling supports owner `recording.start` and `recording.stop`.
  Foreground start/stop returns terminal success when permissions and state
  allow it. Background remote start returns a foreground-required failure
  instead of attempting an unsafe foreground-service launch.
- Idle remote stop returns an explicit no-op success with idle state details.
- Android CI run `28823163221` passed for commit `14b543c` with both
  `build-and-unit-test` and `connected-emulator-test`. The connected job ran 13
  instrumentation tests and `scripts/emulator_verify.sh`, producing 1 VTA file,
  1 ZIP file, and 107 GPS rows.

## Latest Local QA Evidence

2026-07-07 KST, commit `0579d4d`:

- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
  --console=plain --stacktrace` passed.
- `Galaxy_A16_API34` booted as `emulator-5554` with
  `ANDROID_HOME` and `ANDROID_SDK_ROOT` set to the local SDK.
- `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
  --console=plain --stacktrace` passed 13 connected tests on
  `Galaxy_A16_API34(AVD) - 14`.
- `ANDROID_SERIAL=emulator-5554
  SCREENSHOT_DIR=/tmp/openvta-android-emulator-qa-20260707-current
  ./scripts/emulator_verify.sh` passed with 1 VTA file, 1 ZIP file, 94 GPS
  rows, and screen-off growth from 288481 to 387011 bytes. Screenshots were
  written outside Git under
  `/tmp/openvta-android-emulator-qa-20260707-current/`.
- `ANDROID_SERIAL=emulator-5554 RESET_APP_DATA=1
  OUT_DIR=/tmp/openvta-live-agent-qa-20260707-current
  ./scripts/live_mock_gps_60s_verify.sh` passed. Summary:
  `gpsRows=60`, `uniqueGpsPoints=60`, `routeSeconds=60`,
  `liveEnabled=0`, `liveOfflineBacklog=0`, and empty
  `crash_anr_markers.txt`. Artifacts were written outside Git under
  `/tmp/openvta-live-agent-qa-20260707-current/20260707T004651Z/`.
- The emulator was stopped with `adb -s emulator-5554 emu kill`; `adb devices`
  was empty after cleanup.

2026-07-07 KST, commit `41d6e51`:

- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
  --console=plain --stacktrace` passed.
- No adb device was initially attached. Available AVDs were
  `Galaxy_A16_API34`, `Galaxy_A16_API36`, and
  `Resizable_Experimental_API_33`.
- `Resizable_Experimental_API_33` did not boot because its AVD config points to
  `system-images/android-33/google_apis/arm64-v8a/`, which is not installed in
  the local SDK. The installed system images are API 34 and API 36 Google Play
  ARM64 images.
- `Galaxy_A16_API34` booted as `emulator-5554` after running the emulator with
  `ANDROID_SDK_ROOT` and `ANDROID_HOME` set to the local SDK. Verbose emulator
  output reported `Boot completed in 12049 ms`.
- First `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
  --console=plain --stacktrace` failed with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` because the emulator had a stale
  differently signed `dev.openvta.logger` package state. A rerun after package
  manager cleanup passed 13 connected tests.
- `ANDROID_SERIAL=emulator-5554
  SCREENSHOT_DIR=/tmp/openvta-android-emulator-qa-20260707
  ./scripts/emulator_verify.sh` passed with 1 VTA file, 1 ZIP file, 95 GPS
  rows, and screen-off growth from 290555 to 390182 bytes. Screenshots were
  written outside Git under `/tmp/openvta-android-emulator-qa-20260707/`.
- `ANDROID_SERIAL=emulator-5554 RESET_APP_DATA=1
  OUT_DIR=/tmp/openvta-live-agent-qa-20260707
  ./scripts/live_mock_gps_60s_verify.sh` passed. Summary:
  `gpsRows=60`, `uniqueGpsPoints=60`, `routeSeconds=60`,
  `liveEnabled=0`, `liveOfflineBacklog=0`, and empty
  `crash_anr_markers.txt`. Artifacts were written outside Git under
  `/tmp/openvta-live-agent-qa-20260707/20260706T230753Z/`.
- The emulator was stopped with `adb -s emulator-5554 emu kill`; `adb devices`
  was empty after cleanup.

2026-07-07 KST, CI focus-fix worktree:

- Android CI run `28829542314` failed only in `connected-emulator-test`; the
  `build-and-unit-test` job passed. The failure artifact showed seven
  `LivePairingInstrumentedTest` failures in the shared `setUp()` path:
  `ComposeTimeoutException` at `waitForActivityFocus()`, where the test waited
  for `decorView.hasWindowFocus()` on the API 35 headless emulator.
- `LivePairingInstrumentedTest` now verifies QR scanner and gallery handoff
  intents from `Intents.getIntents()` instead of using `Intents.intended()`,
  avoiding Espresso root-focus checks after intercepted external intents.
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
  --console=plain --stacktrace` passed locally.
- `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
  --console=plain --stacktrace` passed locally with 13 tests on
  `Galaxy_A16_API34(AVD) - 14`.
- `ANDROID_SERIAL=emulator-5554
  SCREENSHOT_DIR=/tmp/openvta-android-ci-focus-fix-qa
  ./scripts/emulator_verify.sh` passed with 1 VTA file, 1 ZIP file, 127 GPS
  rows, and screen-off growth from 291589 to 390823 bytes. Screenshots were
  written outside Git under `/tmp/openvta-android-ci-focus-fix-qa/`.
- The 60-second QA script's new-VTA wait now defaults to 60 seconds and can be
  overridden with `NEW_VTA_WAIT_SECONDS`. This covers slow cold starts after
  `pm clear`.
- `ANDROID_SERIAL=emulator-5554 RESET_APP_DATA=1
  OUT_DIR=/tmp/openvta-live-agent-qa-focus-fix-cold
  ./scripts/live_mock_gps_60s_verify.sh` passed after that timeout hardening.
  Summary: `gpsRows=60`, `uniqueGpsPoints=60`, `routeSeconds=60`,
  `liveEnabled=0`, and empty `crash_anr_markers.txt`. Artifacts were written
  outside Git under
  `/tmp/openvta-live-agent-qa-focus-fix-cold/20260706T232844Z/`.

## Remaining Live QA Gaps

- Physical production test-device credential rotation still needs to be
  repeated before rotating real user devices.
- Broad public distribution still needs release-owner sign-off, signed release
  artifact hash evidence outside Git, clean-install smoke, and existing-user
  upgrade QA for the final release candidate.
- Production-like server tests remain out of scope unless the OpenVTA Live
  production test policy explicitly allows them. Keep ordinary Android QA
  local, emulator-based, or GitHub Actions-based by default.
