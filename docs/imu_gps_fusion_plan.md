# IMU And GPS Fusion Plan

## Problem Statement

The current app records GPS fixes at the rate provided by Android and the device GNSS chipset. On many phones this is effectively about 1 Hz, even when the app requests `minTimeMs = 0` and `minDistanceMeters = 0`. IMU sensors can produce much higher-rate samples, but they do not create new absolute GPS measurements. They can improve visual smoothness, latency, short-gap continuity, and post-processed path quality when fused carefully with GPS.

The app already stores the two key timing primitives needed for this work:

- GPS `elapsedRealtimeNanos`, which is aligned with Android's elapsed realtime clock.
- `SensorEvent.timestamp`, which Android defines in nanoseconds using the same elapsed realtime time base.

## Android Constraints

- Android sensor events include raw values, timestamps, and accuracy metadata. See Android's sensor overview: <https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview>
- Android 12+ rate-limits many motion sensors to 200 Hz unless `HIGH_SAMPLING_RATE_SENSORS` is declared. The app should start with `SENSOR_DELAY_GAME` and only request higher rates after measuring battery/CPU impact.
- Long-running collection must happen from a foreground service when the app is not visible.
- `Location.getBearing()` is movement direction, not phone orientation. Device orientation must come from rotation vector/gyro/magnetometer logic.
- Phone mounting orientation is arbitrary. Vehicle-mode constraints need a calibration step before treating phone axes as vehicle axes.

## Source Basis

- Android Sensor overview and sensor rate limits: <https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview>
- Android `SensorEvent` timing and gyro integration example: <https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/hardware/SensorEvent.java>
- Android `Location` semantics: <https://developer.android.com/reference/android/location/Location>
- Error-State Kalman Filter plus RTS smoothing for GNSS/IMU robustness: <https://www.mdpi.com/1424-8220/23/7/3676>
- Madgwick orientation filter for efficient IMU/MARG attitude estimation: <https://web.enib.fr/~kerhoas/iot/reseau-de-capteurs/carte-imu-mpu9250/documents/INVENSENSE/madgwick_internal_report.pdf>
- IMU preintegration between sparse positioning keyframes: <https://arxiv.org/abs/1512.02363>

## Phase 1: Timestamp-Aligned Visualization Interpolation

This is the lowest-risk improvement and should be implemented first.

Goals:

- Keep legacy `$` GPS and `#` sensor record semantics unchanged.
- Render smoother live and saved-session paths at 5-10 Hz without pretending those points are raw GPS.
- Label every generated point with `source = GPS` or `source = IMU_INTERP`.

Method:

1. Record accelerometer, gyroscope, and rotation-vector samples at `SENSOR_DELAY_GAME`.
2. Align GPS and sensor samples by `elapsedRealtimeNanos`.
3. For each pair of adjacent GPS fixes, generate intermediate display-only points using cubic/Hermite interpolation.
4. Use GPS speed/bearing as endpoint velocity hints when valid.
5. Use gyro yaw delta and rotation-vector heading as a smoothing hint, with low trust when magnetometer quality is poor.
6. Clamp generated points to the next GPS fix so drift does not accumulate across intervals.

Deliverables:

- `TraceEstimator` domain class with deterministic unit tests.
- UI selector showing `GPS only` and `Smoothed`.
- Saved-session visualization that can replay the same estimator offline.
- Metrics: path smoothness, max interpolation deviation, CPU time, and battery impact.

Limitations:

- This improves display smoothness more than absolute accuracy.
- It should not be used as evidence that GPS itself is faster than the hardware reports.

## Phase 2: Offline Evaluation Harness

Before adding a real filter, build measurement tooling.

Evaluation approach:

1. Record controlled sessions with full GPS and IMU logs.
2. Treat the recorded GPS path as reference.
3. Downsample GPS to 1 Hz, run interpolation/fusion, then compare against held-out GPS points.
4. Report RMSE, max horizontal error, heading error, speed error, CPU cost, memory cost, and battery impact.
5. Evaluate walking, driving, stop-and-go, tunnel/urban-canyon, and phone-moved-inside-car cases separately.

Acceptance target:

- Smoothed path should reduce visual jitter without increasing max error materially.
- If metrics are worse than GPS-only, keep the feature disabled by default.

## Phase 3: Real-Time ESKF/EKF Fusion

After the evaluation harness exists, implement a real-time filter.

State candidate:

- Position: latitude/longitude converted to local ENU frame.
- Velocity: east/north/up.
- Attitude: quaternion or yaw/pitch/roll.
- Accelerometer bias.
- Gyroscope bias.

Filter flow:

1. Propagate state at IMU rate with accelerometer and gyroscope.
2. Correct state whenever GPS position/speed/bearing arrives.
3. Use GPS horizontal accuracy, speed accuracy, and bearing accuracy as measurement covariance.
4. Reject outlier GPS fixes based on accuracy and innovation gating.
5. Add stationary constraints when speed is near zero.
6. Optionally add non-holonomic vehicle constraints only after mount calibration.

Output policy:

- Raw GPS points remain raw.
- Fused estimates are written/rendered as derived samples with source and covariance.
- UI must show `GPS rate` and `Fused display rate` separately.

Expected benefit:

- Lower visual latency.
- Better short-gap continuity.
- More stable heading/speed during brief poor GPS periods.

Risk:

- IMU drift grows quickly without GPS correction.
- Magnetometer can be unreliable inside vehicles.
- Incorrect phone mount assumptions can make the estimate worse.

## Phase 4: Offline Smoothing

Offline smoothing can use the full session, so it can produce better saved-session paths than the live estimator.

Options:

- RTS smoother over the ESKF state history.
- Factor graph with GPS fixes as position factors and IMU preintegration between fixes.
- Segment-based smoothing that resets or downweights bad periods.

Output:

- Saved-session `Smoothed` view.
- Optional export of a separate derived track, not a replacement for raw VTA GPS records.

## Phase 5: Optional Map Matching

For road-driving workflows, map matching can improve visual path quality by snapping probable movement to roads. This must stay optional because it can be wrong in parking lots, tunnels, private roads, or off-road testing.

Implementation notes:

- Run map matching after GPS/IMU smoothing, not before.
- Store both original and matched geometry.
- Clearly label matched data in the UI and exports.

## Product Acceptance Criteria

- The app never silently replaces raw GPS with estimated points.
- Every estimated point has source, timestamp, and confidence metadata.
- Existing `.Vta` compatibility remains intact.
- Live map and saved-session visualization can switch between raw and smoothed traces.
- Unit tests cover interpolation, timestamp alignment, and coordinate transforms.
- Instrumentation tests verify that recording still works when smoothing is enabled.
- Performance remains acceptable on mid-range Android 10+ devices.
