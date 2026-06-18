#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.temporal.vtalogger"
ACTIVITY="com.temporal.vtalogger/.MainActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"
ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
SCREENSHOT_DIR="${SCREENSHOT_DIR:-/tmp}"
VERIFY_REAL_FTP_UPLOAD="${VERIFY_REAL_FTP_UPLOAD:-0}"
VTA_FTP_HOST="${VTA_FTP_HOST:-10.0.2.2}"
VTA_FTP_PORT="${VTA_FTP_PORT:-2121}"
VTA_FTP_USER="${VTA_FTP_USER:-vta}"
VTA_FTP_PASSWORD="${VTA_FTP_PASSWORD:-vta-pass}"
VTA_FTP_ROOT="${VTA_FTP_ROOT:-}"

SEOUL_VISIBLE_ROUTE=(
  "126.978000 37.566500 38 9 5.40"
  "126.977650 37.566050 40 10 6.20"
  "126.977000 37.565400 42 11 7.80"
  "126.976200 37.564700 44 9 10.50"
  "126.975100 37.564000 46 8 12.20"
  "126.974000 37.563200 48 12 15.00"
  "126.972800 37.562400 50 11 17.30"
  "126.971500 37.561500 52 10 18.90"
  "126.970500 37.560600 49 9 14.40"
  "126.969700 37.559700 45 8 11.80"
  "126.969100 37.558900 43 10 9.20"
  "126.968500 37.557800 41 9 8.50"
  "126.970000 37.556500 58 10 12.60"
  "126.972000 37.555200 64 11 16.20"
  "126.974500 37.553800 72 12 19.40"
  "126.976800 37.552500 82 10 21.00"
  "126.979500 37.551100 92 9 17.70"
  "126.982300 37.550000 88 8 13.30"
)

SEOUL_SCREEN_OFF_ROUTE=(
  "126.985500 37.548700 80 9 11.10"
  "126.988200 37.547500 75 10 14.80"
  "126.990800 37.546300 68 11 18.40"
  "126.993100 37.545000 60 12 22.00"
  "126.996000 37.544000 56 10 24.50"
  "126.999200 37.543100 52 9 21.30"
  "127.002000 37.542000 48 8 16.60"
  "127.005000 37.541000 45 10 13.20"
  "127.008000 37.540200 43 11 12.40"
  "127.010500 37.539500 42 12 15.70"
  "127.013200 37.538800 40 10 20.20"
  "127.015800 37.538100 39 9 23.60"
  "127.018000 37.537400 37 8 18.90"
  "127.020000 37.536800 36 10 10.50"
)

dump_ui() {
  local ui
  for _ in {1..5}; do
    "$ADB" shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
    ui="$("$ADB" shell cat /sdcard/window.xml 2>/dev/null | tr -d '\r' || true)"
    if grep -q "<hierarchy" <<< "$ui" && ! grep -q "ERROR: null root node" <<< "$ui"; then
      printf "%s\n" "$ui"
      return 0
    fi
    sleep 0.5
  done
  printf "%s\n" "${ui:-}"
}

text_bounds() {
  local text="$1"
  local ui
  ui="$(dump_ui)"
  awk -v text_target="text=\"$text\"" -v desc_target="content-desc=\"$text\"" 'BEGIN { RS = "<" } index($0, text_target) || index($0, desc_target) { print; exit }' <<< "$ui" \
    | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p'
}

tap_text() {
  local text="$1"
  local bounds x1 y1 x2 y2 x y
  for _ in {1..10}; do
    bounds="$(text_bounds "$text")"
    if [[ -n "$bounds" ]]; then
      read -r x1 y1 x2 y2 <<< "$bounds"
      x=$(((x1 + x2) / 2))
      y=$(((y1 + y2) / 2))
      "$ADB" shell input tap "$x" "$y"
      return 0
    fi
    sleep 1
  done
  echo "Unable to find text: $text" >&2
  dump_ui >&2
  return 1
}

tap_text_with_scroll() {
  local text="$1"
  local bounds
  for _ in {1..18}; do
    bounds="$(text_bounds "$text")"
    if [[ -n "$bounds" ]]; then
      tap_text "$text"
      return 0
    fi
    "$ADB" shell input swipe 540 1650 540 1050 250
    sleep 1
  done
  echo "Unable to find text after scrolling: $text" >&2
  dump_ui >&2
  return 1
}

tap_bottom_nav() {
  local tab="$1"
  local size width height x y
  size="$("$ADB" shell wm size | awk -F': ' '/Physical size/ { print $2; exit }' | tr -d '\r')"
  width="${size%x*}"
  height="${size#*x}"
  case "$tab" in
    Dashboard) x=$((width / 8)) ;;
    Live) x=$((width * 3 / 8)) ;;
    Sessions) x=$((width * 5 / 8)) ;;
    Settings) x=$((width * 7 / 8)) ;;
    *)
      echo "Unknown bottom nav tab: $tab" >&2
      return 1
      ;;
  esac
  y=$((height - 180))
  "$ADB" shell input tap "$x" "$y"
  sleep 1
}

scroll_to_top() {
  for _ in {1..5}; do
    "$ADB" shell input swipe 540 520 540 1900 250
    sleep 0.3
  done
}

wait_for_text() {
  local text="$1"
  local ui
  for _ in {1..20}; do
    ui="$(dump_ui)"
    if grep -q "text=\"$text\"" <<< "$ui" || grep -q "content-desc=\"$text\"" <<< "$ui"; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for text: $text" >&2
  dump_ui >&2
  return 1
}

wait_for_ui_contains() {
  local text="$1"
  local ui
  for _ in {1..20}; do
    ui="$(dump_ui)"
    if grep -Fq "$text" <<< "$ui"; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for UI text containing: $text" >&2
  dump_ui >&2
  return 1
}

wait_for_ui_regex() {
  local regex="$1"
  local ui
  for _ in {1..20}; do
    ui="$(dump_ui)"
    if grep -Eq "$regex" <<< "$ui"; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for UI regex: $regex" >&2
  dump_ui >&2
  return 1
}

wait_for_text_with_scroll() {
  local text="$1"
  local ui
  for _ in {1..22}; do
    ui="$(dump_ui)"
    if grep -q "text=\"$text\"" <<< "$ui" || grep -q "content-desc=\"$text\"" <<< "$ui"; then
      return 0
    fi
    "$ADB" shell input swipe 540 1650 540 1050 250
    sleep 1
  done
  echo "Timed out waiting for text after scrolling: $text" >&2
  dump_ui >&2
  return 1
}

ensure_awake_unlocked() {
  "$ADB" shell input keyevent 224 || true
  "$ADB" shell wm dismiss-keyguard || true
  "$ADB" shell input keyevent 82 || true
  sleep 1
}

latest_vta() {
  "$ADB" shell "run-as $PACKAGE sh -c 'ls -t files/vta/sessions/*.Vta 2>/dev/null | head -n 1'" | tr -d '\r'
}

remote_size() {
  local file="$1"
  "$ADB" shell "run-as $PACKAGE sh -c 'wc -c < \"$file\"'" | tr -d '\r[:space:]'
}

latest_meta() {
  "$ADB" shell "run-as $PACKAGE sh -c 'ls -t files/vta/sessions/*.meta 2>/dev/null | head -n 1'" | tr -d '\r'
}

wait_for_upload_state() {
  local expected="$1"
  local meta state
  for _ in {1..60}; do
    meta="$(latest_meta)"
    if [[ -n "$meta" ]]; then
      state="$("$ADB" shell "run-as $PACKAGE sh -c 'grep \"^uploadState=\" \"$meta\" 2>/dev/null | cut -d= -f2'" | tr -d '\r[:space:]')"
      if [[ "$state" == "$expected" ]]; then
        return 0
      fi
    fi
    sleep 1
  done
  echo "Timed out waiting for uploadState=$expected" >&2
  [[ -n "${meta:-}" ]] && "$ADB" shell "run-as $PACKAGE cat \"$meta\"" >&2 || true
  return 1
}

wait_for_local_uploaded_file() {
  local file="$1"
  for _ in {1..60}; do
    if [[ -s "$file" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for local FTP upload file: $file" >&2
  [[ -n "$VTA_FTP_ROOT" ]] && find "$VTA_FTP_ROOT" -maxdepth 2 -type f -ls >&2 || true
  return 1
}

inject_geo_route() {
  local delay_seconds="$1"
  shift
  local point lon lat altitude satellites speed_knots
  for point in "$@"; do
    read -r lon lat altitude satellites speed_knots <<< "$point"
    "$ADB" emu geo fix "$lon" "$lat" "$altitude" "$satellites" "$speed_knots" || true
    sleep "$delay_seconds"
  done
}

assert_no_runtime_crash() {
  local log_file
  log_file="$(mktemp)"
  "$ADB" logcat -d -v time > "$log_file" || true
  if grep -E "ANR in $PACKAGE|Force finishing activity $PACKAGE|am_crash.*$PACKAGE|Input dispatching timed out.*$PACKAGE" "$log_file" >&2; then
    echo "Runtime crash or ANR found in logcat. Full log: $log_file" >&2
    exit 1
  fi
  if grep -A8 "FATAL EXCEPTION" "$log_file" | grep -F "Process: $PACKAGE" >&2; then
    echo "Runtime crash found in app process. Full log: $log_file" >&2
    exit 1
  fi
  rm -f "$log_file"
}

if [[ ! -f "$APK" ]]; then
  echo "Missing $APK. Run ./gradlew assembleDebug first." >&2
  exit 1
fi

"$ADB" wait-for-device
"$ADB" uninstall "$PACKAGE" >/dev/null 2>&1 || true
"$ADB" install -r "$APK"
"$ADB" shell pm grant "$PACKAGE" android.permission.ACCESS_COARSE_LOCATION || true
"$ADB" shell pm grant "$PACKAGE" android.permission.ACCESS_FINE_LOCATION || true
"$ADB" shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS || true
"$ADB" logcat -c || true
"$ADB" emu geo fix 126.978000 37.566500 38 9 5.40 || true
sleep 1

if [[ "$VERIFY_REAL_FTP_UPLOAD" == "1" ]]; then
  "$ADB" shell am start -n "$ACTIVITY" \
    --ez debugApplySettings true \
    --es debugDriverId "CC00" \
    --es debugImuPresetId "imu_heading_10hz" \
    --es debugFtpHost "$VTA_FTP_HOST" \
    --ei debugFtpPort "$VTA_FTP_PORT" \
    --es debugFtpUser "$VTA_FTP_USER" \
    --es debugFtpPassword "$VTA_FTP_PASSWORD" \
    --ez debugPassiveMode true \
    --ez debugKeepLocalFiles true \
    --ez debugDarkMode false >/dev/null
else
  "$ADB" shell am start -n "$ACTIVITY" \
    --ez debugApplySettings true \
    --es debugDriverId "CC00" \
    --es debugImuPresetId "imu_heading_10hz" \
    --ez debugPassiveMode true \
    --ez debugKeepLocalFiles true \
    --ez debugDarkMode false >/dev/null
fi
sleep 1

"$ADB" shell am start -n "$ACTIVITY"
scroll_to_top
wait_for_text "Start"
wait_for_ui_contains "IMU preset: IMU heading 10Hz"
"$ADB" emu geo fix 126.978000 37.566500 38 9 5.40 || true
sleep 1
tap_text "Start"
wait_for_text "Live visualization"
sleep 2

inject_geo_route 0.8 "${SEOUL_VISIBLE_ROUTE[@]}"
wait_for_text_with_scroll "Route map"
wait_for_text_with_scroll "Map data © OpenStreetMap contributors"
wait_for_text_with_scroll "Latest GPS"
wait_for_text_with_scroll "Latitude"
wait_for_text_with_scroll "Longitude"
wait_for_text_with_scroll "Altitude"
wait_for_text_with_scroll "Accuracy"
wait_for_text_with_scroll "Provider"
wait_for_text_with_scroll "Recent GPS fixes"
wait_for_ui_regex '20[0-9][0-9]\.[0-9][0-9]\.[0-9][0-9] [0-9][0-9]:[0-9][0-9] \('
scroll_to_top
mkdir -p "$SCREENSHOT_DIR"
"$ADB" exec-out screencap -p > "$SCREENSHOT_DIR/vta_live_korea_light.png"

LATEST="$(latest_vta)"
if [[ -z "$LATEST" ]]; then
  echo "No active .Vta file was created after tapping Start." >&2
  exit 1
fi

SIZE_BEFORE="$(remote_size "$LATEST")"
"$ADB" shell input keyevent 26 || true
sleep 1
SIZE_AFTER="$SIZE_BEFORE"
inject_geo_route 1.0 "${SEOUL_SCREEN_OFF_ROUTE[@]}"
SIZE_AFTER="$(remote_size "$LATEST")"
ensure_awake_unlocked

if [[ "$SIZE_AFTER" -le "$SIZE_BEFORE" ]]; then
  echo "VTA file did not grow while the screen was off: before=$SIZE_BEFORE after=$SIZE_AFTER" >&2
  exit 1
fi

wait_for_text "Stop recording"
tap_text "Stop recording"
tap_bottom_nav Dashboard
wait_for_text "Ready"
sleep 1

REMOTE_DIR="/data/data/$PACKAGE/files/vta/sessions"
"$ADB" shell "run-as $PACKAGE ls -la files/vta/sessions"
VTA_COUNT="$("$ADB" shell "run-as $PACKAGE sh -c 'ls files/vta/sessions/*.Vta 2>/dev/null | wc -l'" | tr -d '\r[:space:]')"
if [[ "$VTA_COUNT" == "0" ]]; then
  echo "No .Vta files were created in $REMOTE_DIR" >&2
  exit 1
fi

VTA_CONTENT="$("$ADB" shell "run-as $PACKAGE sh -c 'FILE=\$(ls -t files/vta/sessions/*.Vta | head -n 1); cat \"\$FILE\"'" | tr -d '\r')"
grep -q '^%% VTALogger Kotlin Version' <<< "$VTA_CONTENT"
grep -Eq '^%% FormatVersion: [23]' <<< "$VTA_CONTENT"
grep -q '^\$' <<< "$VTA_CONTENT"
grep -q '^@' <<< "$VTA_CONTENT"
grep -q '^#' <<< "$VTA_CONTENT"
grep -q ',ImuHeading,' <<< "$VTA_CONTENT"
grep -q ',imu_heading_10hz,' <<< "$VTA_CONTENT"
grep -q '37.566' <<< "$VTA_CONTENT"
grep -q '126.97' <<< "$VTA_CONTENT"
grep -q '%% End' <<< "$VTA_CONTENT"
GPS_ROW_COUNT="$(awk -F, '/^\$/ { count++ } END { print count + 0 }' <<< "$VTA_CONTENT")"
if [[ "$GPS_ROW_COUNT" -lt 20 ]]; then
  echo "Expected at least 20 GPS rows from the dense Seoul route, got $GPS_ROW_COUNT." >&2
  exit 1
fi
UNIQUE_GPS_COUNT="$(awk -F, '/^\$/ { print $3 "," $4 }' <<< "$VTA_CONTENT" | sort -u | wc -l | tr -d '[:space:]')"
if [[ "$UNIQUE_GPS_COUNT" -lt 16 ]]; then
  echo "Expected diverse route coordinates, got only $UNIQUE_GPS_COUNT unique GPS points." >&2
  exit 1
fi
UNIQUE_ALTITUDE_COUNT="$(awk -F, '/^\$/ { print int($5) }' <<< "$VTA_CONTENT" | sort -u | wc -l | tr -d '[:space:]')"
if [[ "$UNIQUE_ALTITUDE_COUNT" -lt 8 ]]; then
  echo "Expected varied altitude values, got only $UNIQUE_ALTITUDE_COUNT unique altitude buckets." >&2
  exit 1
fi
if ! awk -F, '/^\$/ { if (($3 + 0) < 33 || ($3 + 0) > 39 || ($4 + 0) < 124 || ($4 + 0) > 132) bad++ } END { exit bad ? 1 : 0 }' <<< "$VTA_CONTENT"; then
  echo "A GPS row was outside the expected Korea latitude/longitude bounds." >&2
  exit 1
fi
NON_ZERO_SPEED_COUNT="$(awk -F, '/^\$/ && ($6 + 0) > 0 { count++ } END { print count + 0 }' <<< "$VTA_CONTENT")"
if [[ "$NON_ZERO_SPEED_COUNT" -lt 16 ]]; then
  echo "Expected at least 16 GPS rows with non-zero Speed, got $NON_ZERO_SPEED_COUNT." >&2
  exit 1
fi
ENHANCED_ROW_COUNT="$(awk -F, '/^@/ { count++ } END { print count + 0 }' <<< "$VTA_CONTENT")"
if [[ "$ENHANCED_ROW_COUNT" -lt 20 ]]; then
  echo "Expected at least 20 enhanced GPS rows from imu_heading_10hz, got $ENHANCED_ROW_COUNT." >&2
  exit 1
fi
if ! awk -F, '/^@/ { if ($12 != "ImuHeading" || $14 != "imu_heading_10hz") bad++ } END { exit bad ? 1 : 0 }' <<< "$VTA_CONTENT"; then
  echo "Enhanced rows did not preserve source/preset metadata." >&2
  exit 1
fi
META_CONTENT="$("$ADB" shell "run-as $PACKAGE sh -c 'FILE=\$(ls -t files/vta/sessions/*.meta | head -n 1); cat \"\$FILE\"'" | tr -d '\r')"
grep -q '^imuPresetId=imu_heading_10hz' <<< "$META_CONTENT"

tap_bottom_nav Sessions
wait_for_text "Recent sessions"
tap_text_with_scroll "Zip"
sleep 1
ZIP_COUNT="$("$ADB" shell "run-as $PACKAGE sh -c 'ls files/vta/sessions/*.Zip 2>/dev/null | wc -l'" | tr -d '\r[:space:]')"
if [[ "$ZIP_COUNT" == "0" ]]; then
  echo "No .Zip files were created after tapping Zip." >&2
  exit 1
fi
ZIP_BASENAME="$(basename "${LATEST%.Vta}.Zip")"
for _ in {1..10}; do
  if grep -q "ZIP: $ZIP_BASENAME" <<< "$(dump_ui)"; then
    break
  fi
  sleep 1
done
if ! grep -q "ZIP: $ZIP_BASENAME" <<< "$(dump_ui)"; then
  echo "ZIP file exists but UI did not show $ZIP_BASENAME." >&2
  dump_ui >&2
  exit 1
fi
if grep -q 'ZIP: .* (0 bytes)' <<< "$(dump_ui)"; then
  echo "ZIP file exists but UI still shows 0 bytes." >&2
  dump_ui >&2
  exit 1
fi
tap_text_with_scroll "Upload"
if [[ "$VERIFY_REAL_FTP_UPLOAD" == "1" ]]; then
  if [[ -z "$VTA_FTP_ROOT" ]]; then
    echo "VTA_FTP_ROOT must be set when VERIFY_REAL_FTP_UPLOAD=1" >&2
    exit 1
  fi
  wait_for_upload_state "Uploaded"
  wait_for_local_uploaded_file "$VTA_FTP_ROOT/$ZIP_BASENAME"
  if [[ ! -s "$VTA_FTP_ROOT/$ZIP_BASENAME" ]]; then
    echo "Uploaded ZIP is empty or missing: $VTA_FTP_ROOT/$ZIP_BASENAME" >&2
    exit 1
  fi
else
  wait_for_ui_regex "FTP (host|user) is not configured"
fi

tap_text_with_scroll "View"
wait_for_text_with_scroll "Session view"
wait_for_text_with_scroll "Route map"
wait_for_text_with_scroll "Map data © OpenStreetMap contributors"
wait_for_text_with_scroll "Latest GPS"
wait_for_text_with_scroll "Recent GPS fixes"
wait_for_text_with_scroll "Elapsed RT"
wait_for_text_with_scroll "Speed km/h"

tap_bottom_nav Settings
wait_for_text "Dark mode"
tap_text "Dark mode"
tap_text "Save"
wait_for_ui_contains "Settings saved"
tap_bottom_nav Live
sleep 2
"$ADB" exec-out screencap -p > "$SCREENSHOT_DIR/vta_live_korea_dark.png"
assert_no_runtime_crash

if [[ "$VERIFY_REAL_FTP_UPLOAD" == "1" ]]; then
  echo "Emulator verification passed: $VTA_COUNT VTA file(s), $ZIP_COUNT ZIP file(s), GPS rows $GPS_ROW_COUNT, screen-off growth $SIZE_BEFORE->$SIZE_AFTER bytes, FTP uploaded $ZIP_BASENAME."
else
  echo "Emulator verification passed: $VTA_COUNT VTA file(s), $ZIP_COUNT ZIP file(s), GPS rows $GPS_ROW_COUNT, screen-off growth $SIZE_BEFORE->$SIZE_AFTER bytes."
fi
echo "Screenshots: $SCREENSHOT_DIR/vta_live_korea_light.png, $SCREENSHOT_DIR/vta_live_korea_dark.png"
