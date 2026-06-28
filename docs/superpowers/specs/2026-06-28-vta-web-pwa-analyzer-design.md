# VTA Web PWA Analyzer Design

## Goal

Build a zero-cost public Web/PWA analyzer for VTA trajectory files. The app should replace the unsupported legacy Windows VTA_Road workflow for the common analysis tasks: loading `.Vta` and zipped VTA sessions, visualizing GPS/sensor traces, inspecting charts, extracting path segments, applying calibration offsets, and optionally filtering noisy acceleration data.

The analyzer should be developed as a separate public repository from `gps-monitor`. This repository remains the Android logger source of truth. The analyzer repository should consume the VTA format contract and shared fixtures derived from this repository, then deploy automatically to GitHub Pages.

## Research Summary

The legacy public site exposes a Windows installer and documentation through `testcell3.com` and `testcell5.com`. The site is old, uses HTTP for downloads on `testcell5.com`, and has browser/TLS compatibility issues. The useful product requirements come from the public VTA Program page, the Road Condition pages, and the downloadable PDF manuals:

- `VTA_Program.pdf`: Windows VTA_Road v1.10 program operation.
- `VTALogger_V102a.pdf`: Android logger operation and VTA file examples.
- `DataFormat_Phone.pdf`: Android phone VTA record format.
- `DataFormat_IMU.pdf`: standalone IMU box VTA record format.
- `IMU_Box.pdf`: standalone 10 Hz GPS / 100 Hz IMU recorder behavior.

The legacy Windows tool includes many CAD functions. The Web/PWA should not clone the CAD editor. It should preserve the vehicle analysis workflow and make it easier to use.

## Scope

### Phase 1: Core VTA Analyzer

- Open one or more `.Vta` files.
- Open `.zip` files and auto-detect contained `.Vta` files.
- Parse modern OpenVTA Logger files, legacy Android phone files, and standalone IMU box files.
- Display a route map with speed-colored points and raw/enhanced point toggles.
- Display summary metrics: duration, distance, max speed, average moving speed, altitude range, GPS count, enhanced count, sensor count, average accuracy, start/end timestamps, and detected format.
- Display synchronized charts for velocity, altitude, accuracy, accelerations, pitch/roll/yaw when available, and velocity plus acceleration.
- Display a friction circle / GG diagram using longitudinal and lateral acceleration.
- Display GPS and sensor table views with search, sorting, and CSV export.
- Select a path segment by point index, chart brush, or map range and export it as a new `.Vta` file.
- Run fully client-side. No uploaded location data, accounts, or backend.
- Deploy to GitHub Pages from a public repository on every `main` push.

### Phase 2: Calibration And Filtering

- Load a `CAL*.Vta` calibration file.
- Estimate static offsets for longitudinal, lateral, and vertical acceleration from the calibration window.
- Allow manual offset entry with live preview.
- Persist calibration presets in browser local storage only.
- Apply calibration to charts, friction circle, summary metrics, and exports.
- Offer raw/calibrated comparison toggles.
- Add a 2nd-order low-pass Butterworth filter option for acceleration signals.
- Apply zero-phase forward/backward filtering for evenly sampled sensor data. If timestamps are irregular, estimate an effective sample rate, show a warning, and keep raw data available for comparison.
- Show filter parameters clearly: enabled state, cutoff frequency, sample rate source, and affected channels.
- Keep the original data immutable and make every transform reversible in the UI.

### Explicitly Out Of Scope For First Release

- Re-implementing all CAD menu features from VTA_Road.
- Importing RT-3000, Vericom, Smarty BX-1000, or proprietary ECW background formats.
- Rebuilding the Road Condition Monitoring calculations for PSD, resistance, power, fuel, and bumps.
- Offline map tile download or bulk tile caching.
- Server-side storage of user traces.

RCM analysis remains a future module after representative raw RCM files and formulas are available. The legacy RCM pages mainly expose static result images, not enough raw data to reproduce the calculations safely.

## Repository Strategy

Create a new public repository, recommended name `openvta-analyzer`.

Recommended structure:

```text
openvta-analyzer/
  .github/workflows/
    ci.yml
    pages.yml
  docs/
    format/
    user-guide/
  public/
    manifest.webmanifest
  src/
    app/
    components/
    domain/
      calibration/
      charts/
      export/
      filters/
      parser/
      statistics/
    fixtures/
    styles/
  tests/
```

Keep `gps-monitor` and `openvta-analyzer` linked through documented fixtures rather than a runtime dependency. The Android app can evolve independently, while the analyzer uses fixture tests to guarantee compatibility with `.Vta` output from the logger.

## Technology

- Vite + TypeScript for fast static builds.
- React for the UI.
- MapLibre GL JS for the map view with raster OSM-compatible tiles.
- Apache ECharts for time-series charts, scatter plots, brushing, and friction-circle rendering.
- JSZip for `.zip` import.
- Vitest for unit tests.
- Playwright for browser and visual smoke tests.
- GitHub Actions for CI and GitHub Pages deployment.

Map tile behavior must follow the official OpenStreetMap tile usage policy. The app should request only tiles needed for the visible interactive viewport, show attribution, avoid offline tile storage, avoid prefetching large areas, and expose an advanced setting for a user-provided tile URL if higher volume or private/offline use is needed.

## Data Model

Use immutable raw data plus derived views.

```text
VtaFile
  sourceName
  detectedFormat
  headers
  rawLines
  gpsPoints[]
  enhancedPoints[]
  sensorPoints[]
  parseWarnings[]

GpsPoint
  index
  date
  time
  epochMillis?
  latitude
  longitude
  altitudeMeters
  speedKmh
  bearingDegrees
  satelliteCount
  accuracyMeters?
  provider?
  elapsedRealtimeNanos?
  source
  confidence?
  derivedFromRawIndex?

SensorPoint
  index
  elapsedSeconds
  eventCode
  orientationXDegrees?
  orientationYDegrees?
  orientationZDegrees?
  accelX
  accelY
  accelZ
  timestampNanos?
  accuracy?
  gyroX?
  gyroY?
  gyroZ?
  rotationAzimuth?
  rotationPitch?
  rotationRoll?

TransformState
  calibrationPreset?
  filterSettings?
  selectedSegment?
  displayMode
```

The parser must support these input variants:

- Modern OpenVTA rows: `$`, `@`, `#`, `%% ImuPresetId`, extended accuracy/provider/timestamp fields.
- Legacy phone rows: `$date,time,lat,lon,alt,speed,bearing,numSat`; `#index,elapsed,event,roll,pitch,yaw,gx,gy,gz`.
- Legacy standalone IMU rows: scaled integer GPS values and `#elapsed,event,roll,pitch,yaw,gx,gy,gz`, where velocity is knots/100 and acceleration is in G.

When format detection is ambiguous, the UI should show a warning and use the safest parse path. It must never silently reinterpret suspicious coordinates or units.

## User Experience

The first screen is the tool, not a marketing landing page. It opens with a compact workspace:

- Top app bar: app name, file open button, sample selector, export button, settings button.
- Main split: map on the left, analysis panel on the right for desktop.
- Mobile layout: file controls, summary, tabs for Map, Charts, Tables, Calibration, Export.
- Drop zone is visible only when no file is loaded or when dragging a file over the app.

Primary workflow:

1. User drops a `.Vta` or `.zip`.
2. App parses locally and shows a privacy note that files stay in the browser.
3. App opens the Overview view with route map, summary metrics, and warnings.
4. User inspects charts. Hovering a chart moves the selected point on the map and updates point details.
5. User selects a segment by dragging over a chart, clicking start/end on the map, or entering point indexes.
6. User exports the segment as `.Vta`, `.csv`, or summary JSON.
7. User optionally opens Calibration, loads a `CAL*.Vta`, applies offsets, compares raw vs calibrated, and exports transformed data.

UX details:

- Use dense, utilitarian controls suited for repeated analysis work.
- Use tabs for major views: Overview, Charts, Tables, Calibration, Export.
- Use icon buttons with tooltips for zoom, fit route, reset selection, export, and settings.
- Keep map and chart selections synchronized.
- Warnings should be actionable and tied to the file: unknown row type, invalid coordinate, missing GPS, missing sensor data, unsupported format, low satellite count, or ambiguous unit scaling.
- Empty states should say what file type is expected and include a sample fixture.
- Large files should show parse progress, then render progressively.
- All destructive actions should be reversible or scoped to derived state. Original data remains available until the user removes the file.

## Views

### Overview

- Map with route points colored by speed.
- Raw/enhanced/source toggles.
- Summary metrics grid.
- File metadata and parse warnings.
- Current point details synchronized with chart hover.

### Charts

- Velocity over time.
- Altitude over time.
- GPS accuracy over time when available.
- Acceleration X/Y/Z over elapsed time.
- Velocity plus acceleration with linked crosshair.
- Pitch/roll/yaw when available.
- Friction circle with calibrated/raw toggle.

### Tables

- GPS table.
- Sensor table.
- Enhanced point table.
- Search and column sorting.
- CSV export for visible rows.

### Segment Export

- Segment selection by point index, map click, or chart brush.
- Preview segment summary before export.
- Export original rows when possible to preserve unknown fields.
- For calibrated or filtered export, write a clear generated header describing transformations.

### Calibration

- Load calibration file.
- Select static calibration window if the file has motion or noisy startup samples.
- Estimate offsets.
- Manual override controls.
- Compare raw vs calibrated acceleration charts.
- Save preset locally by name.

### Filtering

- Low-pass filter controls.
- Cutoff frequency input with sane bounds.
- Effective sample rate display.
- Per-channel enablement for acceleration axes.
- Raw vs filtered comparison.

## Error Handling

- Unsupported files show a recoverable error and keep the app ready for another drop.
- Zip files with no `.Vta` entries show a clear message and the zip entry list.
- Partial parse success is allowed. The app should render valid rows and list warnings.
- If a file has sensor rows but no GPS rows, charts and tables should still work; map shows a missing GPS state.
- If a file has GPS rows but no sensor rows, map and GPS charts work; acceleration and friction views show empty states.
- If map tiles fail, analysis remains usable with a plain coordinate plot fallback.
- If browser storage is unavailable, calibration presets are session-only.

## Privacy And Security

- All parsing and analysis happen in the browser.
- Do not upload traces by default.
- Do not include analytics in the first release.
- Make privacy behavior visible in the file-open empty state and settings.
- Do not fetch tiles for hidden maps or prefetch offline tiles.
- Do not bundle legacy APKs, EXEs, or reverse-engineered artifacts.

## Accessibility

- Keyboard-accessible tabs, file picker, chart focus, table navigation, and export actions.
- High-contrast route palette that is not only red/green dependent.
- Text alternatives for parse warnings and chart values.
- Responsive layouts that avoid horizontal scrolling except inside data tables.

## Testing

Unit tests:

- Parser tests for modern OpenVTA rows.
- Parser tests for legacy phone rows from `DataFormat_Phone.pdf`.
- Parser tests for standalone IMU rows from `DataFormat_IMU.pdf`.
- Format detection tests for decimal-degree phone data vs scaled IMU box data.
- Calibration offset estimation tests.
- Filter behavior tests with known synthetic signals.
- Segment export tests that preserve original rows where possible.

Browser tests:

- Open `.Vta` fixture by file chooser.
- Drop `.zip` fixture and select a session.
- Verify map, summary, and charts render.
- Verify chart hover updates point details.
- Verify segment export creates a file.
- Verify calibration file changes calibrated chart values and can be reset.

Visual tests:

- Desktop 1440px workspace.
- Tablet width.
- Mobile width with tabs.
- Empty state.
- Parse warning state.

CI:

- Typecheck.
- Unit tests.
- Lint.
- Playwright smoke tests.
- Build static site.
- Deploy to GitHub Pages only from `main`.

## Deployment

Use GitHub Pages with GitHub Actions:

- `ci.yml` runs on pull requests and pushes.
- `pages.yml` builds and deploys the static app from `main`.
- Keep the repository public to preserve zero-cost Actions and Pages usage.
- Publish user documentation under `/docs` or as in-app help.
- Include a sample data fixture small enough for repository use.

## Legacy Compatibility Matrix

| Feature | Legacy VTA_Road | Web/PWA Phase 1 | Web/PWA Phase 2 | Notes |
| --- | --- | --- | --- | --- |
| Android `.Vta` load | Yes | Yes | Yes | Core compatibility target |
| OpenVTA extended rows | No | Yes | Yes | Supports `$`, `@`, `#` rows |
| ZIP import | Manual package workflow | Yes | Yes | Client-side JSZip |
| Route map | Yes | Yes | Yes | Interactive web map |
| Speed color scale | Yes | Yes | Yes | User-editable later |
| Velocity chart | Yes | Yes | Yes | Linked to map |
| Acceleration chart | Yes | Yes | Yes | Raw/calibrated/filter views |
| Pitch/roll chart | Yes | Yes if present | Yes if present | Empty state for missing orientation |
| Friction circle | Yes | Yes | Yes | Phase 2 supports calibrated values |
| Path segment export | Yes | Yes | Yes | Preserve original rows when possible |
| Region analysis | Yes | No | No | Future module after Phase 2 |
| Calibration | Yes | Read-only warning | Yes | CAL and manual offsets |
| Butterworth filter | Yes | No | Yes | Forward/backward for evenly sampled data; warned fallback for irregular timestamps |
| CAD tools | Yes | No | No | Out of scope |
| RT-3000/Vericom/Smarty import | Optional legacy | No | No | Future only with samples |
| RCM import | v1.11 | No | No | Future module |

## Implementation Defaults

- Repository name: `openvta-analyzer`.
- UI framework: React.
- Chart library: Apache ECharts.
- Map engine: MapLibre GL JS with OSM-compatible raster tiles for low-volume interactive use.
- Map fallback: coordinate plot when tiles or WebGL fail.
- Example data: synthetic/public fixtures only. Do not commit private GPS logs.

## References

- Legacy VTA page: https://www.testcell3.com/vta-program.html
- Legacy road condition page: https://www.testcell3.com/road-condition.html
- Legacy RCM example: http://www.testcell5.com/Mine3/
- Legacy documentation zip: http://www.testcell5.com/Documents.zip
- OpenStreetMap tile usage policy: https://operations.osmfoundation.org/policies/tiles/
- GitHub Pages documentation: https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages
- GitHub Actions billing documentation: https://docs.github.com/en/billing/concepts/product-billing/github-actions
