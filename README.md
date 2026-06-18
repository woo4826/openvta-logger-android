# OpenVTA Logger Android

Open-source Kotlin/Jetpack Compose Android logger for GPS and phone sensor data. The app writes VTA-compatible session files, can compress sessions to ZIP, visualizes live and saved sessions, and optionally uploads ZIP files to a user-configured FTP server.

Current app version: `0.0.3`.

This repository is prepared for public collaboration under the Apache License 2.0. It does not intentionally track signing keys, original APK reverse-engineering artifacts, generated release APKs, local FTP uploads, or user credentials.

## Screenshots

| Dashboard | Live Map |
| --- | --- |
| ![Dashboard](docs/assets/dashboard.png) | ![Live map](docs/assets/live.png) |

| Sessions | Settings |
| --- | --- |
| ![Sessions](docs/assets/sessions.png) | ![Settings](docs/assets/settings-basic.png) |

## Features

- Foreground GPS/sensor recording for Android 10+.
- Global recording control through the dashboard buttons, bottom navigation, foreground-service notification actions, and app-wide floating action button.
- VTA-style `.Vta` session files with legacy GPS/sensor record prefixes and extended metadata fields.
- IMU/GPS enhancement presets: Raw GPS, Linear 5Hz, Hermite 10Hz, IMU heading 10Hz, and bounded IMU dead reckoning 10Hz.
- Export separation: `$` rows are raw GPS, `@` rows are enhanced GPS-like estimates, and `#` rows are sensor samples.
- MapLibre + OpenStreetMap live map with current position, route path, and separate raw/enhanced point markers.
- Live and saved-session charts/details for speed, altitude, accuracy, provider, satellites, elapsed realtime, source, and confidence.
- Session management with ZIP creation, Android sharesheet export, and optional FTP upload.
- FTP settings stored through encrypted Android storage. Credentials are user-supplied and are not hardcoded.
- GitHub Actions CI for script validation, debug build, unit tests, lint, connected emulator checks, and signed release APK artifact generation.

## VTA Data Format

The app keeps raw and derived data separate because exported data is the important compatibility surface:

- `$` records are raw Android GPS fixes only.
- `@` records are derived/enhanced GPS-like estimates. They include source, confidence, selected IMU preset, and the raw GPS interval they were derived from.
- `#` records are sensor samples with accelerometer, orientation, gyroscope, rotation-vector, timestamp, and accuracy fields.
- `%% ImuPresetId` records the selected preset for that session.

Legacy consumers can continue reading `$` and `#` records. New tools should treat `@` records as estimates, not as hardware GPS measurements.

## Android Support

- `applicationId`: `dev.openvta.logger`
- `minSdk`: 29, Android 10
- `targetSdk`: 35
- Primary permissions: location, foreground service location, notifications on Android 13+, internet, network state, and wake lock.
- WorkManager/AndroidX may merge boot/network receiver permissions for upload retry scheduling. The app does not include a custom boot flow that starts recording automatically.
- Background location is not requested in v1. Recording is started by the user and runs through a foreground service notification.

## Install Notes

Version `0.0.3` changed the package from the earlier Kotlin test package `com.temporal.vtalogger` to `dev.openvta.logger`.

- Android treats `dev.openvta.logger` as a separate app, so `0.0.3` can coexist with earlier `com.temporal.vtalogger` test builds.
- Users who installed `0.0.2` should uninstall the older test app after exporting any needed sessions, then use `0.0.3` as the new OpenVTA Logger line.
- Future `dev.openvta.logger` releases must use the same release signing key to support normal APK updates.

## Build And Test

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Connected emulator tests:

```bash
./gradlew connectedDebugAndroidTest
./scripts/emulator_verify.sh
```

Local FTP smoke test helper:

```bash
./scripts/local_ftp_upload_verify.sh
```

The FTP helper is for local verification only. Do not point tests at production servers.

Signed release builds:

```bash
./gradlew assembleRelease
```

Release signing requires ignored local files under `release-signing/` or the GitHub Actions `release` environment secrets documented in `docs/release_signing.md`.

## Release APK

Release signing material is intentionally excluded from this repository. Build release artifacts only with a private keystore stored outside git.

The locally produced delivery artifacts are kept under `output/apk/` and are ignored by git:

- `OpenVTA-Logger-0.0.3-release.apk`
- `OpenVTA_Logger_User_Guide.pdf`
- `OpenVTA_Logger_v0.0.3.zip`

For GitHub Actions releases, configure the protected `release` environment with the secrets documented in `docs/release_signing.md`. Normal pull request and push CI builds debug artifacts only and does not need release secrets.

## Privacy And Security Notes

VTA sessions can contain precise location, speed, heading, altitude, device sensor values, timestamps, and driver/session identifiers. Treat exported `.Vta` and `.Zip` files as sensitive.

FTP is supported for compatibility, but plain FTP does not encrypt traffic. Prefer local export or a trusted private network unless a secure transport replacement such as FTPS/SFTP is added.

## Public Repository Policy

Current public-readiness status from the latest local audit:

- Tracked source does not include the local release keystore, `release-signing/`, generated APK/AAB files, original APK analysis files, decoded APK output, or user logs.
- Git history is short and does not show tracked keystore/APK/reverse-engineering artifacts.
- Release signing is isolated to GitHub Actions secrets and local ignored files.
- CI on `main` currently covers script validation, debug build, unit tests, lint, connected emulator tests, and signed release APK verification.
- Branch protection and secret scanning/push protection should remain enabled on GitHub.

Rules for future changes:

- Keep `release` environment secrets restricted to maintainers; do not expose them to pull requests.
- Keep original APKs, reverse-engineering outputs, local FTP test roots, and generated release artifacts out of git.
- Review screenshots and docs for personal data before publishing future updates.

## Project Layout

- `app/src/main/java/dev/openvta/logger/recording/`: foreground recording service.
- `app/src/main/java/dev/openvta/logger/data/`: repositories and encrypted settings.
- `app/src/main/java/dev/openvta/logger/domain/`: VTA formatting, parsing, distance, filenames, ZIP helpers.
- `app/src/main/java/dev/openvta/logger/upload/`: FTP upload worker/client.
- `app/src/main/java/dev/openvta/logger/ui/`: Compose UI and visualization views.
- `.github/workflows/android-ci.yml`: CI build/test/lint and emulator verification.
- `docs/imu_gps_fusion_plan.md`: GPS 1 Hz limitation, v0.0.2+ enhancement presets, and IMU fusion roadmap.
- `docs/release_signing.md`: release keystore and GitHub Actions secret strategy.

## Roadmap

- Real-time ESKF/EKF fusion for short GPS gaps and low-latency path estimation.
- Offline smoothing for saved sessions.
- Optional map matching for road-only workflows.
- Secure transport support beyond plain FTP.

## License

This project is licensed under the Apache License 2.0. See `LICENSE`.
