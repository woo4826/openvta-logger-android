#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PACKAGE="${PACKAGE:-dev.openvta.logger}"
ACTIVITY="${ACTIVITY:-dev.openvta.logger/.MainActivity}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
OUT_DIR="${OUT_DIR:-/tmp/openvta-live-agent-qa}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
ARTIFACT_DIR="${ARTIFACT_DIR:-$OUT_DIR/$RUN_ID}"
DRIVER_ID="${DRIVER_ID:-QA60}"
IMU_PRESET="${IMU_PRESET:-imu_heading_10hz}"
GPS_PROVIDER="${GPS_PROVIDER:-gps}"
ROUTE_SECONDS="${ROUTE_SECONDS:-60}"
MIN_GPS_ROWS="${MIN_GPS_ROWS:-55}"
MIN_UNIQUE_GPS_POINTS="${MIN_UNIQUE_GPS_POINTS:-50}"
RESET_APP_DATA="${RESET_APP_DATA:-0}"
LIVE_BASE_URL="${LIVE_BASE_URL:-}"
LIVE_TENANT_ID="${LIVE_TENANT_ID:-}"
LIVE_DEVICE_ID="${LIVE_DEVICE_ID:-}"
LIVE_MQTT_CREDENTIAL="${LIVE_MQTT_CREDENTIAL:-}"
LIVE_WSS_CREDENTIAL="${LIVE_WSS_CREDENTIAL:-}"
LIVE_API_CREDENTIAL="${LIVE_API_CREDENTIAL:-}"
LIVE_ENABLED="${LIVE_ENABLED:-}"
LIVE_OUTBOX_WAIT_SECONDS="${LIVE_OUTBOX_WAIT_SECONDS:-45}"
LIVE_OFFLINE_BACKLOG="${LIVE_OFFLINE_BACKLOG:-0}"
LIVE_SERVER_VERIFY="${LIVE_SERVER_VERIFY:-0}"
LIVE_SERVER_BASE_URL="${LIVE_SERVER_BASE_URL:-$LIVE_BASE_URL}"
LIVE_SERVER_WAIT_SECONDS="${LIVE_SERVER_WAIT_SECONDS:-90}"
LIVE_SESSION_COOKIE="${LIVE_SESSION_COOKIE:-}"
NETWORK_RESTORE_WAIT_SECONDS="${NETWORK_RESTORE_WAIT_SECONDS:-8}"

SEOUL_LAT_MIN="${SEOUL_LAT_MIN:-37.50}"
SEOUL_LAT_MAX="${SEOUL_LAT_MAX:-37.62}"
SEOUL_LON_MIN="${SEOUL_LON_MIN:-126.90}"
SEOUL_LON_MAX="${SEOUL_LON_MAX:-127.10}"

if [[ ! -x "$ADB" ]]; then
  echo "Missing adb executable: $ADB" >&2
  echo "Set ADB=/path/to/adb or ANDROID_SDK=/path/to/android/sdk." >&2
  exit 1
fi

if [[ ! -f "$APK" ]]; then
  echo "Missing debug APK: $APK" >&2
  echo "Run ./gradlew assembleDebug first, or set APK=/path/to/app-debug.apk." >&2
  exit 1
fi

mkdir -p "$ARTIFACT_DIR"
LOGCAT_FILE="$ARTIFACT_DIR/logcat.txt"
CRASH_MARKERS_FILE="$ARTIFACT_DIR/crash_anr_markers.txt"
SUMMARY_FILE="$ARTIFACT_DIR/summary.txt"
LIVE_OUTBOX_STATUS_FILE="$ARTIFACT_DIR/live_outbox_status.txt"
SHOULD_STOP_RECORDING=0
NETWORK_FORCED_OFFLINE=0

if [[ -z "$LIVE_ENABLED" ]]; then
  if [[ -n "$LIVE_BASE_URL" && -n "$LIVE_TENANT_ID" && -n "$LIVE_DEVICE_ID" && -n "$LIVE_API_CREDENTIAL" ]]; then
    LIVE_ENABLED=1
  else
    LIVE_ENABLED=0
  fi
fi

if [[ "$LIVE_ENABLED" == "1" ]]; then
  if [[ -z "$LIVE_BASE_URL" || -z "$LIVE_TENANT_ID" || -z "$LIVE_DEVICE_ID" || -z "$LIVE_API_CREDENTIAL" ]]; then
    echo "LIVE_ENABLED=1 requires LIVE_BASE_URL, LIVE_TENANT_ID, LIVE_DEVICE_ID, and LIVE_API_CREDENTIAL." >&2
    exit 1
  fi
fi

if [[ "$LIVE_OFFLINE_BACKLOG" == "1" && "$LIVE_ENABLED" != "1" ]]; then
  echo "LIVE_OFFLINE_BACKLOG=1 requires Live credentials and LIVE_ENABLED=1." >&2
  exit 1
fi

if [[ "$LIVE_SERVER_VERIFY" == "1" && -z "$LIVE_SESSION_COOKIE" ]]; then
  echo "LIVE_SERVER_VERIFY=1 requires LIVE_SESSION_COOKIE with a session cookie value." >&2
  exit 1
fi

resolve_serial() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    printf "%s\n" "$ANDROID_SERIAL"
    return
  fi

  local devices
  devices=($("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }'))
  if [[ "${#devices[@]}" -ne 1 ]]; then
    echo "Set ANDROID_SERIAL when zero or multiple adb devices are connected." >&2
    "$ADB" devices >&2
    exit 1
  fi
  printf "%s\n" "${devices[0]}"
}

SERIAL="$(resolve_serial)"

adb_device() {
  "$ADB" -s "$SERIAL" "$@"
}

force_device_network_offline() {
  printf "Forcing emulator network offline for Live backlog capture.\n"
  NETWORK_FORCED_OFFLINE=1
  adb_device shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || true
  adb_device shell settings put global airplane_mode_on 1 >/dev/null 2>&1 || true
  adb_device shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1 || true
  adb_device shell svc wifi disable >/dev/null 2>&1 || true
  adb_device shell svc data disable >/dev/null 2>&1 || true
  sleep 3
}

restore_device_network() {
  printf "Restoring emulator network for Live backlog flush.\n"
  adb_device shell svc data enable >/dev/null 2>&1 || true
  adb_device shell svc wifi enable >/dev/null 2>&1 || true
  adb_device shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  adb_device shell settings put global airplane_mode_on 0 >/dev/null 2>&1 || true
  adb_device shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1 || true
  NETWORK_FORCED_OFFLINE=0
  sleep "$NETWORK_RESTORE_WAIT_SECONDS"
}

trigger_live_retry() {
  if [[ "$LIVE_ENABLED" != "1" ]]; then
    return
  fi
  adb_device shell am start -n "$ACTIVITY" --ez debugRetryLiveUpstream true >/dev/null 2>&1 || true
}

cleanup() {
  local status=$?
  if [[ -n "${SERIAL:-}" && "$SHOULD_STOP_RECORDING" == "1" ]]; then
    adb_device shell am start -n "$ACTIVITY" --ez debugStopRecording true >/dev/null 2>&1 || true
  fi
  if [[ -n "${SERIAL:-}" && "$NETWORK_FORCED_OFFLINE" == "1" ]]; then
    restore_device_network >/dev/null 2>&1 || true
  fi
  if [[ -n "${SERIAL:-}" ]]; then
    adb_device shell cmd location providers set-test-provider-enabled "$GPS_PROVIDER" false >/dev/null 2>&1 || true
    adb_device shell cmd location providers remove-test-provider "$GPS_PROVIDER" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT

latest_remote_file() {
  local extension="$1"
  adb_device shell "run-as $PACKAGE sh -c 'ls -t files/vta/sessions/*.$extension 2>/dev/null | head -n 1'" | tr -d '\r[:space:]'
}

copy_remote_text_file() {
  local remote_path="$1"
  local local_path="$2"
  adb_device exec-out run-as "$PACKAGE" cat "$remote_path" > "$local_path"
}

wait_for_new_vta() {
  local previous="$1"
  local candidate
  for _ in {1..30}; do
    candidate="$(latest_remote_file Vta || true)"
    if [[ -n "$candidate" && "$candidate" != "$previous" ]]; then
      printf "%s\n" "$candidate"
      return 0
    fi
    sleep 1
  done

  echo "Timed out waiting for a new .Vta file in /data/data/$PACKAGE/files/vta/sessions." >&2
  return 1
}

wait_for_vta_footer() {
  local remote_path="$1"
  local footer
  for _ in {1..30}; do
    footer="$(adb_device shell "run-as $PACKAGE sh -c 'tail -n 1 \"$remote_path\" 2>/dev/null'" | tr -d '\r')"
    if [[ "$footer" == "%% End" ]]; then
      return 0
    fi
    sleep 1
  done

  echo "Timed out waiting for %% End footer in $remote_path." >&2
  adb_device shell "run-as $PACKAGE sh -c 'tail -n 20 \"$remote_path\" 2>/dev/null'" >&2 || true
  return 1
}

prepare_gps_test_provider() {
  adb_device shell cmd location set-location-enabled true >/dev/null 2>&1 || true
  adb_device shell cmd location providers remove-test-provider "$GPS_PROVIDER" >/dev/null 2>&1 || true
  if ! adb_device shell cmd location providers add-test-provider "$GPS_PROVIDER" \
    --requiresSatellite \
    --supportsAltitude \
    --supportsSpeed \
    --supportsBearing \
    --accuracy 1 \
    --powerRequirement 1 >/dev/null 2>&1; then
    adb_device shell cmd location providers add-test-provider "$GPS_PROVIDER" >/dev/null 2>&1 || true
  fi
  adb_device shell cmd location providers set-test-provider-enabled "$GPS_PROVIDER" true >/dev/null
}

set_test_location() {
  local lat="$1"
  local lon="$2"
  local altitude="$3"
  local speed="$4"
  local bearing="$5"
  local accuracy="$6"
  local time_millis
  time_millis="$(($(date +%s) * 1000))"

  if ! adb_device shell cmd location providers set-test-provider-location "$GPS_PROVIDER" \
    --location "$lat,$lon" \
    --accuracy "$accuracy" \
    --altitude "$altitude" \
    --speed "$speed" \
    --bearing "$bearing" \
    --time "$time_millis" >/dev/null 2>&1; then
    adb_device shell cmd location providers set-test-provider-location "$GPS_PROVIDER" \
      --location "$lat,$lon" \
      --accuracy "$accuracy" >/dev/null
  fi
}

route_point() {
  local index="$1"
  awk -v i="$index" 'BEGIN {
    lat = 37.566500 + (i * 0.000045) + (((i % 5) - 2) * 0.000003)
    lon = 126.978000 + (i * 0.000064) + (((i % 7) - 3) * 0.000003)
    altitude = 38 + (i % 15)
    speed = 7.0 + ((i % 8) * 0.25)
    bearing = 115 + ((i % 13) * 2)
    accuracy = 4 + (i % 4)
    printf "%.6f %.6f %.1f %.2f %.1f %.1f\n", lat, lon, altitude, speed, bearing, accuracy
  }'
}

inject_mock_route() {
  local index lat lon altitude speed bearing accuracy
  for ((index = 1; index <= ROUTE_SECONDS; index++)); do
    read -r lat lon altitude speed bearing accuracy < <(route_point "$index")
    set_test_location "$lat" "$lon" "$altitude" "$speed" "$bearing" "$accuracy"
    printf "Injected GPS point %02d/%02d: lat=%s lon=%s\n" "$index" "$ROUTE_SECONDS" "$lat" "$lon"
    sleep 1
  done
}

live_outbox_statuses() {
  local room_db="$ARTIFACT_DIR/live_outbox.db"
  if adb_device shell "run-as $PACKAGE test -f databases/openvta-live-outbox.db" >/dev/null 2>&1; then
    adb_device exec-out run-as "$PACKAGE" cat databases/openvta-live-outbox.db > "$room_db" || true
    if [[ -s "$room_db" ]]; then
      sqlite3 "$room_db" "SELECT COUNT(*), status FROM live_outbox GROUP BY status ORDER BY status;" 2>/dev/null \
        | awk -F'|' '{ print $1 " " $2 }'
      return
    fi
  fi

  adb_device shell "run-as $PACKAGE sh -c 'if ls files/vta/live-outbox/*.properties >/dev/null 2>&1; then grep -h \"^status=\" files/vta/live-outbox/*.properties | cut -d= -f2 | sort | uniq -c; fi'" | tr -d '\r'
}

wait_for_live_outbox_acked() {
  if [[ "$LIVE_ENABLED" != "1" ]]; then
    : > "$LIVE_OUTBOX_STATUS_FILE"
    return
  fi

  local deadline statuses acked pending sent failed
  deadline=$((SECONDS + LIVE_OUTBOX_WAIT_SECONDS))
  while (( SECONDS < deadline )); do
    statuses="$(live_outbox_statuses || true)"
    printf "%s\n" "$statuses" > "$LIVE_OUTBOX_STATUS_FILE"
    acked="$(awk '$2 == "Acked" { print $1 + 0 }' "$LIVE_OUTBOX_STATUS_FILE")"
    pending="$(awk '$2 == "Pending" { print $1 + 0 }' "$LIVE_OUTBOX_STATUS_FILE")"
    sent="$(awk '$2 == "Sent" { print $1 + 0 }' "$LIVE_OUTBOX_STATUS_FILE")"
    failed="$(awk '$2 == "Failed" { print $1 + 0 }' "$LIVE_OUTBOX_STATUS_FILE")"
    acked="${acked:-0}"
    pending="${pending:-0}"
    sent="${sent:-0}"
    failed="${failed:-0}"
    if [[ "$acked" -gt 0 && "$pending" -eq 0 && "$sent" -eq 0 && "$failed" -eq 0 ]]; then
      return
    fi
    sleep 2
  done

  echo "Timed out waiting for Live outbox entries to become Acked." >&2
  cat "$LIVE_OUTBOX_STATUS_FILE" >&2 || true
  exit 1
}

manifest_is_completed_downloadable() {
  local manifest_file="$1"
  python3 - "$manifest_file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
recording = body.get("recording") or {}
segments = body.get("segments") or []
missing = body.get("missingRanges") or []
downloadable = bool(body.get("downloadable"))
status = recording.get("status")
sample_count = int(recording.get("sampleCount") or 0)
if status == "completed" and downloadable and segments and not missing and sample_count > 0:
    sys.exit(0)
print(
    "status=%s downloadable=%s segments=%s missing=%s sampleCount=%s"
    % (status, downloadable, len(segments), missing, sample_count)
)
sys.exit(1)
PY
}

telemetry_response_has_points() {
  local telemetry_file="$1"
  local recording_id="$2"
  python3 - "$telemetry_file" "$recording_id" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    body = json.load(handle)
recording_id = sys.argv[2]
telemetry = body.get("telemetry") or []
if any(item.get("recordingId") == recording_id for item in telemetry):
    sys.exit(0)
print("telemetry missing recordingId=%s count=%s" % (recording_id, len(telemetry)))
sys.exit(1)
PY
}

wait_for_server_recording() {
  if [[ "$LIVE_SERVER_VERIFY" != "1" ]]; then
    return
  fi

  local recording_id="$1"
  local manifest_file="$ARTIFACT_DIR/server_manifest.json"
  local manifest_status_file="$ARTIFACT_DIR/server_manifest_status.txt"
  local telemetry_file="$ARTIFACT_DIR/server_telemetry.json"
  local telemetry_status_file="$ARTIFACT_DIR/server_telemetry_status.txt"
  local download_file="$ARTIFACT_DIR/server_download.Vta"
  local deadline http_status

  deadline=$((SECONDS + LIVE_SERVER_WAIT_SECONDS))
  while (( SECONDS < deadline )); do
    http_status="$(curl -sS -o "$manifest_file" -w "%{http_code}" \
      -H "cookie: $LIVE_SESSION_COOKIE" \
      "$LIVE_SERVER_BASE_URL/api/recordings/$recording_id/manifest" || true)"
    if [[ "$http_status" == "200" ]] && manifest_is_completed_downloadable "$manifest_file" > "$manifest_status_file" 2>&1; then
      http_status="$(curl -sS -o "$telemetry_file" -w "%{http_code}" \
        -H "cookie: $LIVE_SESSION_COOKIE" \
        "$LIVE_SERVER_BASE_URL/api/devices/$LIVE_DEVICE_ID/telemetry?limit=1" || true)"
      if [[ "$http_status" != "200" ]] || ! telemetry_response_has_points "$telemetry_file" "$recording_id" > "$telemetry_status_file" 2>&1; then
        {
          printf "telemetryHttpStatus=%s\n" "$http_status"
          cat "$telemetry_status_file" 2>/dev/null || true
        } > "$ARTIFACT_DIR/server_wait_last_status.txt"
        sleep 3
        continue
      fi
      curl -fsS -o "$download_file" \
        -H "cookie: $LIVE_SESSION_COOKIE" \
        "$LIVE_SERVER_BASE_URL/api/recordings/$recording_id/download"
      if [[ -s "$download_file" ]]; then
        printf "serverManifest=%s\nserverTelemetry=%s\nserverDownload=%s\n" "$manifest_file" "$telemetry_file" "$download_file" >> "$SUMMARY_FILE"
        return
      fi
    else
      {
        printf "httpStatus=%s\n" "$http_status"
        cat "$manifest_status_file" 2>/dev/null || true
      } > "$ARTIFACT_DIR/server_wait_last_status.txt"
    fi
    sleep 3
  done

  echo "Timed out waiting for server recording to become completed and downloadable: $recording_id" >&2
  cat "$ARTIFACT_DIR/server_wait_last_status.txt" >&2 2>/dev/null || true
  exit 1
}

assert_vta_content() {
  local vta_file="$1"
  local meta_file="$2"
  local gps_rows unique_gps_points

  if ! grep -q '^%% End$' "$vta_file"; then
    echo "Latest .Vta is missing the %% End footer: $vta_file" >&2
    exit 1
  fi

  if ! grep -q "^%% ImuPresetId: $IMU_PRESET$" "$vta_file"; then
    echo "Latest .Vta does not include the expected IMU preset header: $IMU_PRESET" >&2
    exit 1
  fi

  if ! grep -q "^imuPresetId=$IMU_PRESET$" "$meta_file"; then
    echo "Latest session metadata does not include imuPresetId=$IMU_PRESET: $meta_file" >&2
    exit 1
  fi

  gps_rows="$(awk -F, '/^\$/ { count++ } END { print count + 0 }' "$vta_file")"
  if [[ "$gps_rows" -lt "$MIN_GPS_ROWS" ]]; then
    echo "Expected at least $MIN_GPS_ROWS GPS rows, got $gps_rows." >&2
    exit 1
  fi

  unique_gps_points="$(awk -F, '/^\$/ { print $3 "," $4 }' "$vta_file" | sort -u | wc -l | tr -d '[:space:]')"
  if [[ "$unique_gps_points" -lt "$MIN_UNIQUE_GPS_POINTS" ]]; then
    echo "Expected at least $MIN_UNIQUE_GPS_POINTS unique GPS points, got $unique_gps_points." >&2
    exit 1
  fi

  if ! awk -F, \
    -v lat_min="$SEOUL_LAT_MIN" \
    -v lat_max="$SEOUL_LAT_MAX" \
    -v lon_min="$SEOUL_LON_MIN" \
    -v lon_max="$SEOUL_LON_MAX" \
    '/^\$/ {
      if (($3 + 0) < lat_min || ($3 + 0) > lat_max || ($4 + 0) < lon_min || ($4 + 0) > lon_max) {
        printf "Out-of-bounds GPS row: %s\n", $0
        bad++
      }
    }
    END { exit bad ? 1 : 0 }' "$vta_file" > "$ARTIFACT_DIR/out_of_bounds_rows.txt"; then
    echo "One or more GPS rows are outside Seoul bounds." >&2
    cat "$ARTIFACT_DIR/out_of_bounds_rows.txt" >&2
    exit 1
  fi

  printf "gpsRows=%s\nuniqueGpsPoints=%s\n" "$gps_rows" "$unique_gps_points" >> "$SUMMARY_FILE"
}

assert_no_crash_or_anr() {
  adb_device logcat -d > "$LOGCAT_FILE" || true
  if grep -E '(FATAL EXCEPTION|ANR in|Application Not Responding|am_crash|am_anr|Force finishing)' "$LOGCAT_FILE" \
    | grep -E "($PACKAGE|AndroidRuntime|ActivityManager)" > "$CRASH_MARKERS_FILE"; then
    echo "Crash or ANR markers were found in logcat:" >&2
    cat "$CRASH_MARKERS_FILE" >&2
    exit 1
  fi
  : > "$CRASH_MARKERS_FILE"
}

printf "Using adb serial: %s\n" "$SERIAL"
printf "Artifacts: %s\n" "$ARTIFACT_DIR"

adb_device wait-for-device
adb_device install -r -t "$APK" >/dev/null
if [[ "$RESET_APP_DATA" == "1" ]]; then
  adb_device shell pm clear "$PACKAGE" >/dev/null
fi
adb_device shell pm grant "$PACKAGE" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1 || true
adb_device shell pm grant "$PACKAGE" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
adb_device shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
adb_device shell appops set --uid 2000 android:mock_location allow >/dev/null 2>&1 || true

prepare_gps_test_provider
set_test_location "37.566500" "126.978000" "38" "7.0" "115" "4"

adb_device shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
adb_device shell input keyevent 224 >/dev/null 2>&1 || true
adb_device shell wm dismiss-keyguard >/dev/null 2>&1 || true
PREVIOUS_VTA="$(latest_remote_file Vta || true)"
adb_device logcat -c || true

if [[ "$LIVE_OFFLINE_BACKLOG" == "1" ]]; then
  force_device_network_offline
fi

start_args=(
  shell am start -n "$ACTIVITY"
  --ez debugApplySettings true
  --es debugDriverId "$DRIVER_ID"
  --es debugImuPresetId "$IMU_PRESET"
  --ez debugPassiveMode true
  --ez debugKeepLocalFiles true
  --ez debugDarkMode false
)
if [[ "$LIVE_ENABLED" == "1" ]]; then
  start_args+=(
    --ez debugLiveEnabled true
    --es debugLiveBaseUrl "$LIVE_BASE_URL"
    --es debugLiveTenantId "$LIVE_TENANT_ID"
    --es debugLiveDeviceId "$LIVE_DEVICE_ID"
    --es debugLiveApiCredential "$LIVE_API_CREDENTIAL"
  )
  [[ -n "$LIVE_MQTT_CREDENTIAL" ]] && start_args+=(--es debugLiveMqttCredential "$LIVE_MQTT_CREDENTIAL")
  [[ -n "$LIVE_WSS_CREDENTIAL" ]] && start_args+=(--es debugLiveWssCredential "$LIVE_WSS_CREDENTIAL")
fi
start_args+=(--ez debugStartRecording true)
adb_device "${start_args[@]}" >/dev/null
SHOULD_STOP_RECORDING=1
REMOTE_VTA="$(wait_for_new_vta "$PREVIOUS_VTA")"

inject_mock_route

adb_device shell am start -n "$ACTIVITY" --ez debugStopRecording true >/dev/null
SHOULD_STOP_RECORDING=0
wait_for_vta_footer "$REMOTE_VTA"
if [[ "$LIVE_OFFLINE_BACKLOG" == "1" ]]; then
  restore_device_network
fi
trigger_live_retry
sleep 3
wait_for_live_outbox_acked

LOCAL_VTA="$ARTIFACT_DIR/$(basename "$REMOTE_VTA")"
REMOTE_META="${REMOTE_VTA%.Vta}.meta"
LOCAL_META="$ARTIFACT_DIR/$(basename "$REMOTE_META")"
copy_remote_text_file "$REMOTE_VTA" "$LOCAL_VTA"
copy_remote_text_file "$REMOTE_META" "$LOCAL_META"

assert_no_crash_or_anr
assert_vta_content "$LOCAL_VTA" "$LOCAL_META"
wait_for_server_recording "$(basename "$REMOTE_VTA" .Vta)"
if [[ "$LIVE_SERVER_VERIFY" == "1" ]] && ! cmp -s "$LOCAL_VTA" "$ARTIFACT_DIR/server_download.Vta"; then
  echo "Server downloaded VTA does not match the local VTA artifact." >&2
  exit 1
fi
adb_device exec-out screencap -p > "$ARTIFACT_DIR/after_60s.png" || true

{
  printf "package=%s\n" "$PACKAGE"
  printf "activity=%s\n" "$ACTIVITY"
  printf "apk=%s\n" "$APK"
  printf "serial=%s\n" "$SERIAL"
  printf "routeSeconds=%s\n" "$ROUTE_SECONDS"
  printf "imuPreset=%s\n" "$IMU_PRESET"
  printf "remoteVta=%s\n" "$REMOTE_VTA"
  printf "localVta=%s\n" "$LOCAL_VTA"
  printf "localMeta=%s\n" "$LOCAL_META"
  printf "logcat=%s\n" "$LOGCAT_FILE"
  printf "screenshot=%s\n" "$ARTIFACT_DIR/after_60s.png"
  printf "liveEnabled=%s\n" "$LIVE_ENABLED"
  printf "liveDeviceId=%s\n" "$LIVE_DEVICE_ID"
  printf "liveOfflineBacklog=%s\n" "$LIVE_OFFLINE_BACKLOG"
  printf "liveServerVerify=%s\n" "$LIVE_SERVER_VERIFY"
  printf "liveOutboxStatus=%s\n" "$LIVE_OUTBOX_STATUS_FILE"
} >> "$SUMMARY_FILE"

printf "Live mock GPS QA passed.\n"
printf "VTA: %s\n" "$LOCAL_VTA"
printf "Meta: %s\n" "$LOCAL_META"
printf "Logcat: %s\n" "$LOGCAT_FILE"
printf "Summary: %s\n" "$SUMMARY_FILE"
