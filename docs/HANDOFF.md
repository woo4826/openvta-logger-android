# OpenVTA Logger Developer Handoff

Last updated: 2026-07-08.

This document is the starting point for another agent or developer continuing the Android app.

## Current State

- Project name: OpenVTA Logger Android.
- Repository: `woo4826/openvta-logger-android`.
- Public URL: `https://github.com/woo4826/openvta-logger-android`.
- App display name: `OpenVTA Logger`.
- Android package/applicationId: `dev.openvta.logger`.
- Current code version: `0.0.4`, `versionCode = 4`.
- Min SDK: 29, Android 10.
- Target SDK: 35.
- UI stack: Kotlin, Jetpack Compose, Material 3.
- Map stack: MapLibre Android SDK with OpenStreetMap tiles.
- Recording model: user-started foreground service, no custom boot auto-record flow.
- Main delivery artifact: direct signed APK distribution, not Play Store.
- OpenVTA Live upstream is opt-in. No telemetry, status, command channel, or
  VTA upload runs until the user pairs the app with a six-digit registration
  code or equivalent QR/image handoff.

## Release State

- Public GitHub Release page:
  `https://github.com/woo4826/openvta-logger-android/releases/tag/v0.0.3`
- Public APK download:
  `https://github.com/woo4826/openvta-logger-android/releases/download/v0.0.3/OpenVTA-Logger-0.0.3-release.apk`
- APK SHA-256: `52219321f961bdba755011432950f73448a3bcddf2604b24d167eb19479f2fd7`
- Package: `dev.openvta.logger`
- Public release version: `0.0.3`
- Release signing certificate SHA-256:
  `9b847b690875bc1fbb701f64e881ce1d718d04349a8aa044f6a7107d9d5a4325`
- Internal `0.0.4` release APK workflow succeeded for tag `v0.0.4` in GitHub
  Actions run `28749474216`, but no public GitHub Release page exists for
  `v0.0.4` yet. Keep downloaded APK artifacts and hash evidence outside Git.

Important install note: `v0.0.3` is a new package line. Android treats it as a separate app from the earlier Kotlin test package `com.temporal.vtalogger`. Users should export any needed old sessions before uninstalling old test builds.

## Verified Work

The following checks were completed for the public `v0.0.3` release
preparation:

- Local `testDebugUnitTest lintDebug assembleDebug` passed.
- Local `assembleRelease` passed.
- Local release APK signature verification passed.
- Emulator instrumentation tests passed.
- `scripts/emulator_verify.sh` passed on debug build:
  - app install and launch
  - mock GPS route injection
  - live map/session recording
  - screen-off recording growth
  - VTA and ZIP creation
- Final release APK was installed on emulator and manually checked:
  - launch to Dashboard
  - Start from Dashboard
  - Live map receives mock GPS
  - Sessions list shows generated VTA
  - Stop via global FAB
  - Zip button creates ZIP without crash
  - Upload without FTP host shows `FTP host is not configured` instead of crashing
  - crash buffer stayed empty during the checked flow
- GitHub Actions:
  - Android CI success: run `27780826017`
  - Release APK success: run `27781347850`

Current `0.0.4` / Live upstream verification evidence:

- Android CI run `28891705229` passed on `main` after Live pairing error
  placement fixes.
- Release APK workflow run `28749474216` passed for tag `v0.0.4`.
- Local emulator QA evidence is recorded in `docs/LIVE_AGENT_QA.md`; connected
  emulator and physical-device QA remain outside default GitHub Actions CI.
- OpenVTA Live pairing uses six-digit code entry, QR scan, and gallery QR
  import. Live credential rotation payloads are accepted only for the currently
  paired device.
- HTTP upload is the first production upstream path; MQTT fallback subscribes
  to the server ack topic before clearing ranged telemetry outbox entries.
- WSS command handling returns explicit terminal results, including no-op
  success for idle remote stop and foreground-required failure for unsafe
  background remote start.

## Important Decisions

- The app is a new native Kotlin implementation, not a patched or re-signed version of the original B4A APK.
- The package was changed from `com.temporal.vtalogger` to `dev.openvta.logger` for the OpenVTA project line.
- `.Vta` compatibility remains a core feature, but OpenVTA separates raw GPS and enhanced estimates.
- `$` rows are raw Android GPS fixes.
- `@` rows are derived/enhanced GPS-like estimates.
- `#` rows are sensor samples.
- FTP remains optional for compatibility. Plain FTP is explicitly treated as a security risk.
- `ACCESS_BACKGROUND_LOCATION` is not requested. Recording is driven by a foreground service started by the user.
- Boot auto-recording is out of scope for the current version.
- Signing keys and generated APKs must stay out of git.

## Quick Start For Developers

1. Clone/open the repository.
2. Confirm Android SDK and JDK 17 are available.
3. Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

4. Start an Android emulator and run:

```bash
./gradlew connectedDebugAndroidTest
./scripts/emulator_verify.sh
```

5. For release builds, configure ignored local files under `release-signing/` or use GitHub `release` environment secrets. See `docs/release_signing.md` and `docs/QA_AND_RELEASE.md`.

## Documentation Map

- `docs/PRODUCT_AND_PLANNING.md`: product goals, completed scope, constraints, and roadmap.
- `docs/ARCHITECTURE.md`: code structure, service/repository/UI flow, and module responsibilities.
- `docs/DATA_FORMAT.md`: `.Vta`, ZIP, raw/enhanced export model, and storage.
- `docs/QA_AND_RELEASE.md`: test matrix, emulator QA, release signing, CI, and publishing.
- `docs/USER_SHARE_GUIDE.md`: install and IMU preset explanation for non-developer testers.
- `docs/LIVE_AGENT_QA.md`: current Live upstream local/emulator QA evidence and
  dedicated mobile-thread scope.
- `docs/IOS_DEVELOPMENT_PLAN.md`: iOS parity plan and platform-specific
  constraints.
- `docs/imu_gps_fusion_plan.md`: IMU/GPS enhancement roadmap.
- `docs/release_signing.md`: keystore and secret management strategy.

## Known Risks And Gotchas

- Direct APK distribution requires future releases to keep using the same `dev.openvta.logger` release key, otherwise Android updates will fail.
- The release key is not in git. Losing it means existing users cannot update via direct APK.
- `release` environment required reviewers could not be enforced previously because the GitHub plan rejected the environment protection rule. Keep write/release privileges restricted to trusted maintainers.
- WorkManager dependencies may merge `RECEIVE_BOOT_COMPLETED` and network receivers. The app does not intentionally start recording on boot.
- IMU-enhanced points are estimates, not hardware GPS measurements. Any downstream analysis must keep `$` and `@` separated.
- MapLibre/OSM rendering depends on network access and public tile availability.
- Live upstream production QA must use disposable test accounts/devices and the
  OpenVTA Live production QA policy. Do not run production Live mutation tests
  from default Android CI.

## Suggested Next Work

- Add a visible in-app About/Version screen with package, version, data format version, and release link.
- Add an explicit export format help screen explaining `$`, `@`, and `#`.
- Add a secure transport option, preferably SFTP or HTTPS upload.
- Add richer IMU calibration guidance and optional fixed-device-orientation setup.
- Plan external Bluetooth GPS and external IMU support without making external hardware mandatory.
- Add rider-initialized EDR-style event logging for fall/crash candidates, with clear non-certified safety wording.
- Expand notification/compact recording controls, including Android notification actions, Quick Settings, and overlay/PiP feasibility checks.
- Use `docs/IOS_DEVELOPMENT_PLAN.md` for iOS feasibility, App Store/TestFlight distribution, and platform-limited implementation scope.
- Add integration tests around release-like ZIP/share/upload failure states.
- Consider Play Store App Signing only if Play Store distribution becomes relevant.
