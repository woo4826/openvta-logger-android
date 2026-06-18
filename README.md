# VTA Logger Android

Kotlin/Jetpack Compose rewrite of the VTA Logger Android app. The app records GPS and phone sensor data, writes VTA-compatible session files, can compress sessions to ZIP, visualizes live and saved sessions, and optionally uploads ZIP files to a user-configured FTP server.

Current app version: `0.0.2`.

This repository is prepared for public collaboration. It does not intentionally track signing keys, original APK reverse-engineering artifacts, generated release APKs, local FTP uploads, or user credentials.

## Screenshots

| Dashboard | Live Map |
| --- | --- |
| ![Dashboard](docs/assets/dashboard.png) | ![Live map](docs/assets/live.png) |

| Sessions | Settings |
| --- | --- |
| ![Sessions](docs/assets/sessions.png) | ![Settings](docs/assets/settings-basic.png) |

## Features

- Foreground GPS/sensor recording for Android 10+.
- VTA-style `.Vta` session files with legacy GPS/sensor record prefixes and extended metadata fields.
- IMU/GPS enhancement presets: Raw GPS, Linear 5Hz, Hermite 10Hz, IMU heading 10Hz, and bounded IMU dead reckoning 10Hz.
- Export separation: `$` rows are raw GPS, `@` rows are enhanced GPS-like estimates, and `#` rows are sensor samples.
- Live map with current position, path trace, speed, altitude, accuracy, provider, and recent fixes.
- Saved session visualization from existing `.Vta` data.
- Session management with ZIP creation, Android sharesheet export, and optional FTP upload.
- FTP settings stored through encrypted Android storage. Credentials are user-supplied and are not hardcoded.
- GitHub Actions CI for build, unit tests, lint, and emulator-based instrumentation checks.

## VTA Data Format

The app keeps raw and derived data separate because exported data is the important compatibility surface:

- `$` records are raw Android GPS fixes only.
- `@` records are derived/enhanced GPS-like estimates. They include source, confidence, selected IMU preset, and the raw GPS interval they were derived from.
- `#` records are sensor samples with accelerometer, orientation, gyroscope, rotation-vector, timestamp, and accuracy fields.

Legacy consumers can continue reading `$` and `#` records. New tools should treat `@` records as estimates, not as hardware GPS measurements.

## Android Support

- `applicationId`: `com.temporal.vtalogger`
- `minSdk`: 29, Android 10
- `targetSdk`: 35
- Primary permissions: location, foreground service location, notifications on Android 13+, internet, network state, and wake lock.
- WorkManager/AndroidX may merge boot/network receiver permissions for upload retry scheduling. The app does not include a custom boot flow that starts recording automatically.
- Background location is not requested in v1. Recording is started by the user and runs through a foreground service notification.

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

## Release APK

Release signing material is intentionally excluded from this repository. Build release artifacts only with a private keystore stored outside git.

The locally produced delivery artifacts are kept under `output/apk/` and are ignored by git:

- `VTA-Logger-0.0.2-release.apk`
- `VTA_Logger_User_Guide.pdf`
- `Logger_jinwoo_v0.0.2.zip`

For GitHub Actions releases, configure the protected `release` environment with the secrets documented in `docs/release_signing.md`. Normal pull request and push CI builds debug artifacts only and does not need release secrets.

## Privacy And Security Notes

VTA sessions can contain precise location, speed, heading, altitude, device sensor values, timestamps, and driver/session identifiers. Treat exported `.Vta` and `.Zip` files as sensitive.

FTP is supported for compatibility, but plain FTP does not encrypt traffic. Prefer local export or a trusted private network unless a secure transport replacement such as FTPS/SFTP is added.

## Public Repository Readiness

Public conversion status from the latest local audit:

- Tracked source does not include the local release keystore, `release-signing/`, generated APK/AAB files, original APK analysis files, decoded APK output, or user logs.
- Git history is short and does not show tracked keystore/APK/reverse-engineering artifacts.
- Release signing is isolated to GitHub Actions secrets and local ignored files.
- CI on `main` currently covers script validation, debug build, unit tests, lint, connected emulator tests, and signed release APK verification.

Before switching GitHub visibility to public:

- Choose and add a `LICENSE`. Without a license, the source is visible but not clearly open source for external reuse.
- Enable GitHub secret scanning/push protection and keep branch protection on `main`.
- Keep `release` environment secrets restricted to maintainers; do not expose them to pull requests.
- Keep original APKs, reverse-engineering outputs, local FTP test roots, and generated release artifacts out of git.
- Review screenshots and docs for personal data before publishing future updates.

## Project Layout

- `app/src/main/java/com/temporal/vtalogger/recording/`: foreground recording service.
- `app/src/main/java/com/temporal/vtalogger/data/`: repositories and encrypted settings.
- `app/src/main/java/com/temporal/vtalogger/domain/`: VTA formatting, parsing, distance, filenames, ZIP helpers.
- `app/src/main/java/com/temporal/vtalogger/upload/`: FTP upload worker/client.
- `app/src/main/java/com/temporal/vtalogger/ui/`: Compose UI and visualization views.
- `.github/workflows/android-ci.yml`: CI build/test/lint and emulator verification.
- `docs/imu_gps_fusion_plan.md`: GPS 1 Hz limitation, implemented v0.0.2 enhancement presets, and IMU fusion roadmap.
- `docs/release_signing.md`: release keystore and GitHub Actions secret strategy.

## Roadmap

- Real-time ESKF/EKF fusion for short GPS gaps and low-latency path estimation.
- Offline smoothing for saved sessions.
- Optional map matching for road-only workflows.
- Secure transport support beyond plain FTP.

## License

License is not selected yet. Add a license before presenting the repository as open source.
