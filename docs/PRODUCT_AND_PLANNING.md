# OpenVTA Logger Product And Planning

## Product Intent

OpenVTA Logger is an Android GPS and phone-sensor logging app. It records live location and motion data, stores VTA-compatible session files, visualizes live and saved sessions, and lets users export VTA/ZIP files for external analysis.

The project began as a native Kotlin redevelopment of a legacy B4A-based VTA Logger workflow. The goal is compatibility plus modernization:

- keep `.Vta`, ZIP, and optional FTP concepts familiar to existing users
- use modern Android foreground-service policies
- store app settings safely
- provide usable Compose screens instead of one crowded screen
- expose raw and enhanced data separately so analysis remains honest

## Current Implemented Scope

- Native Kotlin Android app.
- App ID: `dev.openvta.logger`.
- Compose + Material 3 UI.
- Dashboard screen with Start/Stop and current status.
- Live screen with MapLibre/OpenStreetMap map, current location, path, raw/enhanced marker distinction, GPS details, and recent fixes.
- Sessions screen with recorded sessions, VTA/ZIP status, View, Zip, Upload, Share VTA, and Share ZIP actions.
- Settings screen with driver ID, FTP settings, passive mode, keep-local-files, dark mode, and IMU/GPS enhancement preset selection.
- Foreground service recording with notification controls.
- GPS collection through Android location APIs.
- Sensor collection using Android sensor APIs.
- VTA-compatible file writing.
- ZIP creation.
- Android Sharesheet export.
- Optional FTP upload through Apache Commons Net.
- Encrypted settings storage for credentials and recording options.
- Optional OpenVTA Live upstream with six-digit pairing, QR scan/gallery
  handoff, HTTP-first upload, WSS remote commands, and MQTT ack fallback.
- Local emulator QA scripts.
- GitHub Actions CI and release APK workflow.

## Non-Goals For Current Version

- Patching, cracking, re-signing, or modifying the original B4A APK.
- Background location permission.
- Automatic recording on boot.
- Play Store release process.
- Production server traffic during tests.
- OpenVTA Live upload before explicit user pairing.
- Hardcoded FTP host/user/password.
- Claiming IMU-derived positions are true GPS measurements.

## Branding And Naming Decisions

- App name: OpenVTA Logger.
- Package: `dev.openvta.logger`.
- Repo: `openvta-logger-android`.
- VTA remains the compatibility/data-format concept.
- OpenVTA is the open-source project identity.

The earlier temporary package `com.temporal.vtalogger` should be treated as a test line only. Do not use it for future public builds.

## Data Design Principles

The exported data is the most important compatibility surface.

- Raw GPS and derived estimates are separated.
- Raw GPS rows use `$`.
- Enhanced GPS-like estimate rows use `@`.
- Sensor rows use `#`.
- Legacy readers can keep consuming `$` and `#`.
- New tools should explicitly handle `@` as estimates.

## IMU/GPS Enhancement Presets

The app currently includes these modes:

- Raw GPS: raw Android GPS fixes only.
- Linear 5Hz: straight-line interpolation between raw GPS fixes.
- Hermite 10Hz: smoother curve interpolation using position/speed flow.
- IMU heading 10Hz: enhanced trace that considers phone heading.
- Bounded IMU dead reckoning 10Hz: short bounded IMU-based estimates between GPS fixes.

These modes are intended for visualization and analysis experiments. They do not improve the physical accuracy of the GPS hardware.

## Privacy And Security Model

OpenVTA sessions can contain precise location, time, speed, heading, altitude, driver/session ID, and device sensor data. Treat all exported `.Vta` and `.Zip` files as sensitive.

Current security posture:

- settings credentials are not hardcoded
- FTP password is stored through encrypted Android storage
- release signing material is outside git
- public repo has secret scanning and push protection enabled
- plain FTP is still supported only for compatibility and is documented as insecure
- OpenVTA Live credentials are opt-in and must be stored through encrypted
  Android storage; pairing codes, credential payloads, VTA files, and upload
  logs are sensitive local artifacts

## Roadmap

### Near-Term

- Add About/Version screen.
- Add in-app export format help.
- Add clearer first-run permission and recording guidance.
- Add tests for Share and Upload error branches.
- Add a tester checklist in the app or docs.

### Data And Analysis

- Improve offline saved-session visualization.
- Add CSV/JSON export for downstream analysis.
- Add source/confidence filters to visualization.
- Add raw-vs-enhanced compare screen.
- Add session summary statistics.

### IMU/GPS Fusion

- Add calibration guidance for phone mount orientation.
- Add optional fixed-orientation setting.
- Add offline smoothing for saved sessions.
- Evaluate ESKF/EKF for short GPS gaps.
- Keep raw and fused output separated in export.
- Add higher-grade source support for external Bluetooth GPS and external IMU devices.
- Preserve per-sample source metadata so logs can distinguish phone GPS/IMU from external devices.

### External Device Logging

- Keep the current app usable as a simple phone-based logger.
- Add a source abstraction for internal phone GPS, internal phone IMU, external Bluetooth GPS, and external IMU devices.
- Treat external devices as optional upgrades, not required dependencies for the zero-backend workflow.
- Log connection state, dropouts, sample source, and device metadata so analysis can judge data quality.
- Validate target hardware before adding device-specific parsers or protocols.

### Rider Incident And EDR-Style Events

- Add an explicit rider initialization control before incident detection starts.
- Capture initialization context such as phone mount/orientation assumptions, GPS state, IMU baseline, and selected data sources.
- Detect candidate fall/crash events from acceleration, rotation, speed change, GPS continuity, and post-event stillness signals.
- Save event records with timestamp, location, speed, confidence, sensor summary, and pre/post-event sample windows.
- Present this as event logging for rider review and analysis, not as a certified legal EDR or emergency-response system.

### Recording Controls And Compact UI

- Expand notification-center controls so recording can be started, stopped, paused, or resumed without reopening the main app where Android allows it.
- Consider a Quick Settings tile for start/stop if notification-only start controls are not the right UX.
- Explore a compact floating or picture-in-picture style status surface for active recording.
- Treat Android overlay permission and Play policy risk as design constraints before implementing any "draw over other apps" behavior.
- Keep the visible foreground-service notification as the primary user-facing recording indicator.

### iOS Development

- Track iOS work in `docs/IOS_DEVELOPMENT_PLAN.md`.
- Build only platform-supported behavior: Core Location GPS, Core Motion sensors, Core Bluetooth devices, Files/share export, and approved background modes.
- Do not promise Android-only overlay behavior on iOS. Picture in Picture is not a general-purpose floating control surface.
- Prefer App Store and TestFlight distribution; use Apple-supported limited distribution only when the tester group requires it.
- Keep VTA row semantics and raw-vs-derived separation aligned with Android.

### Network And Storage

- Keep OpenVTA Live upstream opt-in and recoverable: direct code entry must
  remain available even if QR scan/gallery import fails.
- Keep HTTP upload as the primary production path until MQTT fallback has equal
  operational evidence across backend and mobile QA.
- Add SFTP or HTTPS upload.
- Keep FTP as optional compatibility only.
- Consider upload queue observability and retry history.
- Consider SAF directory export/import for long-term backups.

### Distribution

- Keep direct APK releases signed by the same release key.
- Consider Play Store App Signing only if distribution expands.
- If Play Store is used later, separate app signing key and upload key management.
