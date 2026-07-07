# OpenVTA Logger Architecture

## High-Level Runtime Flow

```text
MainActivity / Compose UI
        |
        v
OpenVtaLoggerApp / AppContainer
        |
        +-- SecureSettingsRepository
        +-- RecordingRepository
        +-- LiveTraceStore
        +-- RecordingStatus StateFlow
        |
        v
RecordingForegroundService
        |
        +-- Android Location callbacks
        +-- Android Sensor callbacks
        +-- VtaTraceEnhancer
        +-- RecordingRepository append/write
        |
        v
files/vta/sessions/*.Vta, *.meta, *.Zip
```

## Main Packages

- `dev.openvta.logger`: app entrypoint and main activity.
- `dev.openvta.logger.recording`: foreground recording service.
- `dev.openvta.logger.data`: repositories, settings persistence, Live upstream
  state, and live trace state.
- `dev.openvta.logger.domain`: data models, VTA formatter/parser, filename and ZIP helpers, distance and enhancement logic.
- `dev.openvta.logger.live`: opt-in OpenVTA Live pairing, credential storage,
  HTTP upload, WSS command handling, MQTT fallback, and debug automation hooks.
- `dev.openvta.logger.ui`: reusable visualization components.
- `dev.openvta.logger.upload`: FTP client and WorkManager upload worker.

## App Container

`OpenVtaLoggerApp` owns `AppContainer`, which wires application-wide singletons:

- `SecureSettingsRepository`
- Live upstream settings and credential helpers
- `RecordingRepository`
- `LiveTraceStore`
- recording status `StateFlow`

This is intentionally simple and avoids a DI framework. If the app grows, Hilt/Koin can be considered, but current scope does not require it.

## UI Structure

`MainActivity` hosts the Compose application. The first-level navigation is bottom-tab based:

- Dashboard: high-level status, Start/Stop, distance, speed, GPS/sensor count.
- Live: map, path, raw/enhanced points, latest GPS fields, recent fixes.
- Sessions: session cards and actions.
- Settings: driver ID, FTP, mode toggles, IMU/GPS enhancement preset.
- Live settings are separated so server URL, six-digit pairing, QR scan/gallery
  import, credential rotation payloads, and disconnect actions render near their
  own validation states.

Global recording control is also exposed through a floating action button and service notification actions.

## Recording Service

`RecordingForegroundService` is the recording owner. It:

- starts as a foreground service
- requests location updates
- registers sensor listeners
- tracks current recording state
- writes GPS/sensor rows through `RecordingRepository`
- updates live trace state
- emits foreground notification actions for Stop/Pause/Resume

The app does not request background location. Recording is user-initiated and represented by a visible foreground-service notification.

OpenVTA Live remote start is intentionally constrained by Android foreground
service rules. If background start is unsafe, the app reports a terminal
foreground-required command result instead of attempting a hidden start.

## Repositories

### RecordingRepository

Responsible for session lifecycle and files:

- create session metadata
- create `.Vta` file
- append GPS rows
- append enhanced rows
- append sensor rows
- close session with `%% End`
- list sessions
- create ZIP
- update upload state

Default session directory:

```text
context.filesDir/vta/sessions/
```

### SecureSettingsRepository

Stores app settings and FTP credentials using encrypted Android storage.

Do not replace this with plain DataStore/SharedPreferences for secrets unless credentials are removed or separately encrypted.

### LiveTraceStore

In-memory trace state used by the live UI. It separates:

- raw GPS points
- enhanced GPS-like points
- sensor trace samples

## Domain Layer

Key responsibilities:

- `VtaFormatter`: writes headers and `$`, `@`, `#` records.
- `VtaLogParser`: parses saved sessions for visualization.
- `VtaTraceEnhancer`: creates derived/enhanced points from GPS and sensor inputs.
- `ImuEnhancementPresets`: defines selectable enhancement presets.
- `DistanceCalculator`: cumulative distance calculation.
- `FileNames`: session filename creation and driver ID sanitizing.
- `ZipFiles`: single-session ZIP creation.

## Upload Flow

Upload is optional and compatibility-oriented.

```text
Sessions screen Upload button
        |
        v
UploadWorker enqueue
        |
        v
ensure ZIP exists
        |
        v
FtpUploadClient upload
        |
        v
session uploadState update
```

FTP settings are user-configured. No production server should be hardcoded.

## OpenVTA Live Upstream Flow

Live upstream is disabled until the user pairs the app.

```text
Settings > Live six-digit pairing
        |
        v
OpenVTA Live registration consume API
        |
        +-- API credential for HTTP status, recording, telemetry, and VTA chunks
        +-- WSS credential for owner command channel
        +-- MQTT credential for fallback upload/ack path
        |
        v
Encrypted local credential storage
```

HTTP is the primary upstream path. MQTT fallback subscribes to the server ack
topic and only clears ranged telemetry outbox entries after matching server ack
payloads. VTA bytes are retained locally until server-side manifest/download
verification succeeds.

## Permissions

Primary permissions:

- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_LOCATION`
- `POST_NOTIFICATIONS` on Android 13+
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `WAKE_LOCK`

WorkManager/AndroidX can merge additional receivers/permissions such as boot/network state. Do not interpret that as a custom auto-record boot feature unless explicit app code is added.

## Threading And State

- Compose observes state from repositories and application `StateFlow`.
- Recording callbacks run in service context.
- File work is scoped through repositories.
- Upload work runs through WorkManager.

Future changes should avoid making `MainActivity` responsible for direct file/network logic.

## Extension Points

- Add secure upload transports under `upload/`, keeping FTP as one client implementation.
- Add additional export formats under `domain/` or a dedicated export package.
- Add advanced IMU fusion under `domain/` first, then expose preset selection in Settings.
- Add more UI components under `ui/` to reduce `MainActivity` size.

## Known Architecture Debt

- `MainActivity` is large and owns many UI screens in one file. Splitting into screen files would improve maintainability.
- Upload state and session list refresh are functional but can be made more reactive.
- More automated coverage is needed for share intents and failure UI states.
- Advanced IMU fusion should be isolated behind a stable interface before it grows.
