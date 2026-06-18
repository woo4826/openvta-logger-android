#!/usr/bin/env bash
set -euo pipefail

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
ORIGINAL_APK="${ORIGINAL_APK:-VTALogger_V230.apk}"
NEW_APK="${NEW_APK:-app/build/outputs/apk/debug/app-debug.apk}"
ORIGINAL_PACKAGE="VTA.Logger"
NEW_PACKAGE="com.temporal.vtalogger"
MOCK_SPEED_KNOTS="${MOCK_SPEED_KNOTS:-13.89}"
OUT_DIR="${OUT_DIR:-/tmp/vta-speed-compare}"

resolve_serial() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    printf "%s\n" "$ANDROID_SERIAL"
    return
  fi

  mapfile -t devices < <("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
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

wait_for_text() {
  local text="$1"
  local ui
  for _ in {1..20}; do
    ui="$(adb_device exec-out uiautomator dump /dev/tty 2>/dev/null || true)"
    if grep -q "text=\"$text\"" <<< "$ui"; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for text: $text" >&2
  echo "$ui" >&2
  return 1
}

tap_text() {
  local text="$1"
  local ui line bounds x1 y1 x2 y2
  for _ in {1..20}; do
    ui="$(adb_device exec-out uiautomator dump /dev/tty 2>/dev/null || true)"
    line="$(awk -v target="text=\"$text\"" 'BEGIN { RS = "<" } index($0, target) { print; exit }' <<< "$ui")"
    bounds="$(sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' <<< "$line")"
    if [[ -n "$bounds" ]]; then
      read -r x1 y1 x2 y2 <<< "$bounds"
      adb_device shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))"
      return 0
    fi
    sleep 1
  done
  echo "Unable to find text: $text" >&2
  echo "$ui" >&2
  return 1
}

grant_common_permissions() {
  local package="$1"
  adb_device shell pm grant "$package" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
  adb_device shell pm grant "$package" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1 || true
  adb_device shell pm grant "$package" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  adb_device shell pm grant "$package" android.permission.WRITE_EXTERNAL_STORAGE >/dev/null 2>&1 || true
}

send_route() {
  adb_device emu geo fix 126.9780 37.5665 38 9 "$MOCK_SPEED_KNOTS" >/dev/null
  sleep 2
  adb_device emu geo fix 126.9823 37.5500 88 8 "$MOCK_SPEED_KNOTS" >/dev/null
  sleep 2
}

copy_original_vta() {
  adb_device shell 'latest=$(ls -t /sdcard/RoadData/*.Vta | head -n 1); cat "$latest"' > "$OUT_DIR/original.Vta"
}

copy_new_vta() {
  adb_device shell "run-as $NEW_PACKAGE sh -c 'latest=\$(ls -t files/vta/sessions/*.Vta | head -n 1); cat \"\$latest\"'" > "$OUT_DIR/kotlin.Vta"
}

assert_nonzero_speed() {
  local label="$1"
  local file="$2"
  local count
  count="$(awk -F, '/^\$/ && ($6 + 0) > 0 { count++ } END { print count + 0 }' "$file")"
  if [[ "$count" == "0" ]]; then
    echo "$label produced no GPS rows with non-zero Speed." >&2
    grep -n '^\$' "$file" >&2 || true
    exit 1
  fi
  printf "%s non-zero GPS speed rows: %s\n" "$label" "$count"
  grep -n '^\$' "$file"
}

mkdir -p "$OUT_DIR"

if [[ ! -f "$ORIGINAL_APK" ]]; then
  echo "Missing original APK: $ORIGINAL_APK" >&2
  exit 1
fi
if [[ ! -f "$NEW_APK" ]]; then
  echo "Missing Kotlin APK: $NEW_APK. Run ./gradlew assembleDebug first." >&2
  exit 1
fi

adb_device wait-for-device
adb_device install --bypass-low-target-sdk-block -r "$ORIGINAL_APK" >/dev/null
adb_device install -r "$NEW_APK" >/dev/null
grant_common_permissions "$ORIGINAL_PACKAGE"
grant_common_permissions "$NEW_PACKAGE"

adb_device shell am force-stop "$NEW_PACKAGE" >/dev/null 2>&1 || true
adb_device shell am force-stop "$ORIGINAL_PACKAGE" >/dev/null 2>&1 || true
adb_device shell rm -rf /sdcard/RoadData
adb_device shell am start -n "$ORIGINAL_PACKAGE/.main" >/dev/null
wait_for_text "Start"
tap_text "Start"
sleep 2
send_route
tap_text "Stop"
sleep 2
copy_original_vta

adb_device shell am force-stop "$ORIGINAL_PACKAGE" >/dev/null 2>&1 || true
adb_device shell pm clear "$NEW_PACKAGE" >/dev/null
grant_common_permissions "$NEW_PACKAGE"
adb_device shell am start -n "$NEW_PACKAGE/.MainActivity" --ez debugStartRecording true >/dev/null
sleep 2
send_route
adb_device shell am start -n "$NEW_PACKAGE/.MainActivity" --ez debugStopRecording true >/dev/null
sleep 2
copy_new_vta

assert_nonzero_speed "Original VTALogger_V230" "$OUT_DIR/original.Vta"
assert_nonzero_speed "Kotlin VTA Logger" "$OUT_DIR/kotlin.Vta"
printf "Speed comparison artifacts: %s/original.Vta and %s/kotlin.Vta\n" "$OUT_DIR" "$OUT_DIR"
