# OpenVTA Logger iOS Development Plan

## Goal

Build an iOS counterpart only where iOS platform capabilities support the OpenVTA workflow cleanly. The iOS app should preserve the zero-backend model, record VTA-compatible sessions, and avoid promising Android-only behaviors such as drawing arbitrary controls over other apps.

## Distribution Direction

- Primary path: App Store release after privacy, background location, Bluetooth, and file export behavior are review-ready.
- Beta path: TestFlight for controlled rider and tester validation.
- Limited install path: Apple-supported ad hoc, enterprise, or developer distribution only when appropriate for the tester group.
- Avoid unsupported sideloading instructions, private APIs, jailbroken-device workflows, or installation paths that would block future App Store review.

## Feasible iOS Scope

- Native Swift/SwiftUI app with a focused recording dashboard, live trace view, session list, settings, and share/export flow.
- Core Location-based GPS recording with explicit user permission and visible recording state.
- Core Motion and available device sensor capture for phone-based IMU samples.
- Core Bluetooth support for compatible BLE GPS and IMU devices.
- External Accessory support only for MFi/protocol-compatible hardware that can be validated.
- VTA-compatible `.Vta` and ZIP session export through Files and the iOS share sheet.
- Optional secure upload transport after Android secure-transport work is settled.

## OpenVTA Live Parity Path

- Pairing should match the Android and web model: a short six-digit registration code is the primary path, with QR scan or QR image import as convenience inputs.
- Live credentials belong in Keychain, with explicit disconnect/rotate actions and no credentials stored in exported VTA artifacts.
- Upstream upload should reuse the backend device contract for recording status, telemetry batches, session manifests, and downloadable VTA assets.
- Remote commands should use `URLSessionWebSocketTask` when the app is foregrounded or otherwise allowed by iOS background execution. Unsupported background start/stop requests should return a failed command result instead of pretending recording changed state.
- iOS background behavior must be validated on physical devices before treating Live upstream parity as production-ready.
- Object storage remains a backend concern. The iOS app should upload through the OpenVTA Live API, not directly to the private object-storage bucket.
- Minimum parity QA should cover simulator route replay, physical-device background permission behavior, local API manifest/download verification, and a TestFlight smoke pass before external users depend on the feature.

## Platform Constraints

- iOS does not support arbitrary always-on-top app overlays above other apps in the Android `draw over other apps` sense.
- Picture in Picture is primarily for eligible media/video experiences and should not be treated as a general floating logger widget.
- Background recording must use approved iOS background modes and clear user-facing location behavior.
- Bluetooth background behavior must be scoped to supported Core Bluetooth use cases and tested on real hardware.
- Any EDR-style incident feature must be framed as an assistive logging/event marker, not a certified safety, emergency, or crash-investigation system.

## Implementation Phases

### Phase 1: Feasibility Prototype

- Create a minimal SwiftUI app that records GPS fixes and Core Motion samples into an in-memory trace.
- Export a VTA-compatible `.Vta` file through the iOS share sheet.
- Verify timestamp alignment between GPS fixes and motion samples.
- Validate permission prompts and background behavior on physical devices.

### Phase 2: Android Format Parity

- Match Android VTA format version and row semantics:
  - `$` rows for raw GPS fixes
  - `#` rows for sensor samples
  - `@` rows only for derived estimates
- Add session metadata, ZIP export, session list, and saved-session preview.
- Keep raw GPS and derived estimates separated in all UI and exports.

### Phase 3: External Device Logging

- Add a device abstraction for GPS and IMU sources.
- Support internal phone sensors first, then BLE GPS/IMU devices, then MFi External Accessory hardware only if the target hardware requires it.
- Store source metadata per sample so downstream analysis can distinguish phone GPS, external GPS, phone IMU, and external IMU.
- Add connection status, drop-out markers, and device-specific diagnostics to the session log.

### Phase 4: Rider Incident Event Logging

- Add an explicit rider initialization action before incident detection is enabled.
- Record an initialization snapshot containing sensor baseline, mount/orientation assumptions, GPS state, and selected device sources.
- Detect candidate fall/crash events from acceleration, rotation, speed drop, and GPS continuity signals.
- Save event markers with timestamp, location, speed, sensor summary, confidence, and the surrounding pre/post sample window.
- Keep events reviewable and exportable without claiming legal EDR certification.

### Phase 5: Release Readiness

- Prepare App Store privacy disclosures for location, motion, Bluetooth, files, and optional network upload.
- Run TestFlight sessions with real riding and controlled fall-simulation data that does not endanger testers.
- Add device matrix coverage for iPhone models, iOS versions, internal GPS, BLE GPS, BLE IMU, and battery impact.
- Decide App Store versus controlled distribution based on review risk, tester needs, and hardware dependency.

## References

- Apple Core Location: https://developer.apple.com/documentation/corelocation
- Apple Core Motion: https://developer.apple.com/documentation/coremotion
- Apple Core Bluetooth: https://developer.apple.com/documentation/corebluetooth
- Apple External Accessory: https://developer.apple.com/documentation/externalaccessory
- Apple TestFlight: https://developer.apple.com/testflight/
