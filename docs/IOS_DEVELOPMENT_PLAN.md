# OpenVTA Logger iOS Development Plan

Date: 2026-07-07

This plan describes how to build an iOS counterpart to OpenVTA Logger without
copying Android-only assumptions or private OpenVTA Live secrets. Keep Apple
signing material, provisioning profiles, QA account credentials, TOTP secrets,
VTA exports, crash logs with user data, and production screenshots out of Git.

## Goal

Build a Swift/SwiftUI iOS logger that records VTA-compatible sessions, keeps
local export/share workflows intact, and optionally connects to OpenVTA Live
through the same backend contracts used by Android where iOS platform rules
allow it.

The first iOS release should be production-honest rather than Android
feature-for-feature. Required behavior is explicit recording permission, visible
recording state, VTA-compatible export, Live opt-in, six-digit pairing, secure
credential storage, HTTP upload, foreground command handling, and clear
foreground-required failures when iOS blocks background remote starts.

## Source Of Truth

- Live backend/API/web/admin:
  `/Users/hajin-u/Developer/openvta/openvta-live`
- Android reference implementation:
  `/Users/hajin-u/Developer/openvta/openvta-logger-android`
- Analyzer reference:
  `/Users/hajin-u/Developer/openvta/openvta-analyzer`
- Live API contract:
  `openvta-live/docs/api-spec.md`
- Realtime/MQTT protocol:
  `openvta-live/docs/mqtt-protocol.md`
- iOS handoff from Live:
  `openvta-live/docs/ios-live-implementation-handoff.md`
- Android release/live behavior handoff:
  `openvta-live/docs/android-live-release-handoff.md`

Do not connect an iOS client directly to OCI Object Storage. Buckets are private
and are accessed only by OpenVTA Live backend services through `StorageAdapter`.

## Distribution Direction

- Primary path: App Store release after location, motion, Bluetooth, file
  export, and optional network upload privacy disclosures are review-ready.
- Beta path: TestFlight for controlled rider and tester validation.
- Limited install path: Apple-supported ad hoc, enterprise, or developer
  distribution only when appropriate for the tester group.
- Avoid unsupported sideloading instructions, private APIs, jailbroken-device
  workflows, or installation paths that would block future App Store review.

## Required iOS Scope

### Local Recording

- SwiftUI app with a recording dashboard, permission state, session list,
  settings, and share/export flow.
- Core Location raw GPS capture with explicit permission and visible recording
  state.
- Core Motion sensor capture for phone IMU samples.
- VTA-compatible `.Vta` and ZIP session export through Files and the iOS share
  sheet.
- Local private session storage until the user deletes or exports the session.
- `$` rows for raw GPS fixes, `#` rows for sensor samples, and `@` rows only
  for derived estimates if later implemented.

### Live Opt-In

- Live upload is disabled by default.
- The user must explicitly pair before telemetry, status, VTA metadata, or VTA
  bytes are sent to OpenVTA Live.
- Pairing uses the existing six-digit code from user web. QR scan and QR image
  import are convenience inputs only; direct six-digit entry must remain
  available.
- Store API, WSS, and MQTT credentials in Keychain.
- Provide disconnect/revoke-local-state UX that clears local Live credentials
  without deleting local recordings.
- Never write Live credentials, pairing codes, object keys, or signed URLs into
  VTA/ZIP exports.

### Upload And Recovery

- Use HTTP as the first production upload path.
- Authenticate device calls with the API credential returned by pairing.
- Upload device status, recording lifecycle state, telemetry batches, VTA chunk
  metadata, and VTA bytes through OpenVTA Live API endpoints.
- Delete local upload outbox entries only after the server acknowledges the
  relevant sequence range, manifest, or chunk checksum.
- Preserve VTA byte upload entries until the server manifest/download path can
  verify the uploaded bytes.
- Implement missing-range recovery before treating offline backlog handling as
  release-ready.

### Commands

- Use `URLSessionWebSocketTask` for owner command/control when the app is
  foregrounded or iOS permits the connection.
- Implement `recording.start` and `recording.stop` command results.
- Foreground remote start/stop should reach terminal success when permissions
  and recording state allow it.
- Idle remote stop should return a clear no-op/idle result.
- Background remote start must return a terminal foreground-required failure
  when iOS does not allow starting location work from the background.
- MQTT upload can wait until HTTP upload and WSS command handling are stable.
  If MQTT fallback is added, subscribe to server ack topics before deleting
  outbox entries.

## Backend API Map

Use the OpenVTA Live API and never object-storage credentials.

| Purpose | API |
| --- | --- |
| Create pairing code in user web | `POST /api/devices/registration-token` |
| Pair iOS device | `POST /api/devices/registration/consume` |
| Send device status | `POST /api/devices/:deviceId/status` |
| Create/update recording | `POST /api/devices/:deviceId/recordings` and status ingest |
| Upload VTA chunk | `POST /api/devices/:deviceId/recordings/:recordingId/chunks/:chunkId` |
| Retrieve missing ranges | `GET /api/recordings/:recordingId/missing-ranges` |
| Command channel | `/ws/device-control` with WSS credential |

The backend owns user/admin recording history, VTA Cloud Library assets, object
storage, retention deletion, sample publishing, admin approvals, and audit
history. The iOS client should not implement admin workflows.

## Platform Constraints

- iOS does not support Android-style draw-over-other-app overlays.
- Background location requires clear user-facing behavior and App Store privacy
  disclosure.
- Background Bluetooth behavior must stay within Core Bluetooth background mode
  rules and needs physical-device validation.
- Remote background recording commands are best-effort and may need to fail
  with a foreground-required result.
- Picture in Picture is primarily for eligible media/video experiences and
  should not be treated as a generic floating logger widget.
- Any incident-marker feature must be framed as assistive logging, not a
  certified safety, emergency, or crash-investigation system.

## Implementation Phases

### Phase 1: Local Recorder

- Create a minimal SwiftUI app shell.
- Request location and motion permissions.
- Record GPS fixes and Core Motion samples into an in-memory trace.
- Serialize VTA-compatible `$` and `#` rows.
- Export `.Vta` through the share sheet.
- Unit test timestamp ordering and VTA row formatting.

### Phase 2: Saved Sessions And Export Parity

- Persist local sessions privately on device.
- Add session list, details, delete, and ZIP export.
- Add source metadata so analyzer output can distinguish phone GPS, phone IMU,
  external GPS, and external IMU if external sensors are added later.
- Keep raw GPS and derived estimates separated in UI and exports.

### Phase 3: Live Pairing And Secure State

- Add six-digit pairing code entry.
- Add QR scanner and QR image import as optional convenience paths.
- Consume pairing through OpenVTA Live API.
- Store credentials in Keychain.
- Add disconnect and credential-rotation payload handling.
- Add local integration tests against a local OpenVTA Live API.

### Phase 4: HTTP Upload And History Proof

- Send device status and recording lifecycle updates.
- Upload telemetry and VTA chunks with checksum verification.
- Implement server ack and missing-range recovery before deleting local outbox
  rows.
- Verify user/admin web history, manifest, and download against local
  OpenVTA Live.

### Phase 5: Commands And Recovery

- Add WSS command channel.
- Implement foreground start/stop terminal command results.
- Return no-op/idle for idle remote stop.
- Return foreground-required failure for blocked background remote start.
- Add reconnect and credential-rotation recovery tests.

### Phase 6: Release Readiness

- Run physical-device location, background, battery, and privacy checks.
- Add TestFlight signing and privacy text.
- Run TestFlight smoke.
- Run existing-user upgrade QA.
- Record release sign-off outside Git.

## Minimum QA Matrix

Run iOS QA in this order:

1. Unit tests for VTA row serialization and timestamp ordering.
2. Simulator route replay for UI state and local file export.
3. Physical-device Core Location recording for at least 60 seconds.
4. Physical-device background permission behavior.
5. Six-digit pairing against local OpenVTA Live.
6. HTTP upload against local OpenVTA Live with manifest/download verification.
7. WSS command start/stop in foreground.
8. Idle remote stop returns no-op/idle.
9. Background remote start returns foreground-required failure when iOS blocks
   it.
10. Credential rotation updates Keychain credentials and old credentials reject.
11. User/admin web history shows the uploaded recording.
12. TestFlight smoke after signing and privacy strings are in place.

Production QA must follow
`openvta-live/docs/production-qa-and-test-policy.md`. Unit, integration, E2E,
load, and fault tests remain local or GitHub Actions unless a separate
staging/server policy is approved.

## Open Product Decisions

- Whether iOS ships HTTP-only first or waits for MQTT ack parity.
- Whether external BLE GPS/IMU support is required for the first release.
- Whether iOS should browse VTA Cloud Library samples/assets in-app or rely on
  user web.
- Which physical iOS device and long-lived QA user/admin accounts will be used
  for acceptance.
- Whether incident-marker work belongs in the first iOS release or a later
  rider-assistive logging tranche.

## References

- Apple Core Location: https://developer.apple.com/documentation/corelocation
- Apple Core Motion: https://developer.apple.com/documentation/coremotion
- Apple Core Bluetooth: https://developer.apple.com/documentation/corebluetooth
- Apple External Accessory: https://developer.apple.com/documentation/externalaccessory
- Apple TestFlight: https://developer.apple.com/testflight/
