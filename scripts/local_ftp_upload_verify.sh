#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
PYTHON="${PYTHON:-python3}"
FTP_HOST_FOR_APP="${FTP_HOST_FOR_APP:-10.0.2.2}"
FTP_PORT="${FTP_PORT:-2121}"
FTP_USER="${FTP_USER:-vta}"
FTP_PASSWORD="${FTP_PASSWORD:-vta-pass}"
PASSIVE_START="${PASSIVE_START:-30000}"
PASSIVE_END="${PASSIVE_END:-30009}"
PY_DEPS="${VTA_FTP_PY_DEPS:-${TMPDIR:-/tmp}/vta-pyftpdlib}"
FTP_ROOT="${VTA_FTP_ROOT:-${TMPDIR:-/tmp}/vta-local-ftp-upload}"
SCREENSHOT_DIR="${SCREENSHOT_DIR:-/tmp}"

mkdir -p "$PY_DEPS"
if ! PYTHONPATH="$PY_DEPS" "$PYTHON" -c 'import pyftpdlib' >/dev/null 2>&1; then
  "$PYTHON" -m pip install --quiet --target "$PY_DEPS" pyftpdlib
fi

rm -rf "$FTP_ROOT"
mkdir -p "$FTP_ROOT"
SERVER_LOG="$FTP_ROOT/ftp-server.log"

PYTHONPATH="$PY_DEPS" "$PYTHON" - "$FTP_ROOT" "$FTP_PORT" "$FTP_USER" "$FTP_PASSWORD" "$PASSIVE_START" "$PASSIVE_END" "$FTP_HOST_FOR_APP" >"$SERVER_LOG" 2>&1 <<'PY' &
import logging
import os
import sys
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

root, port, user, password, passive_start, passive_end, masquerade_address = sys.argv[1:]
port = int(port)
passive_start = int(passive_start)
passive_end = int(passive_end)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
authorizer = DummyAuthorizer()
authorizer.add_user(user, password, root, perm="elradfmwMT")

handler = FTPHandler
handler.authorizer = authorizer
handler.passive_ports = range(passive_start, passive_end + 1)
handler.masquerade_address = masquerade_address
handler.banner = "VTA local FTP ready"

server = FTPServer(("0.0.0.0", port), handler)
logging.info("FTP server listening on 0.0.0.0:%s root=%s passive=%s-%s", port, root, passive_start, passive_end)
server.serve_forever()
PY
SERVER_PID="$!"

cleanup() {
  kill "$SERVER_PID" >/dev/null 2>&1 || true
  wait "$SERVER_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for _ in {1..40}; do
  if nc -z 127.0.0.1 "$FTP_PORT" >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
done
if ! nc -z 127.0.0.1 "$FTP_PORT" >/dev/null 2>&1; then
  echo "Local FTP server did not open port $FTP_PORT. Log: $SERVER_LOG" >&2
  cat "$SERVER_LOG" >&2 || true
  exit 1
fi

VERIFY_REAL_FTP_UPLOAD=1 \
VTA_FTP_ROOT="$FTP_ROOT" \
VTA_FTP_HOST="$FTP_HOST_FOR_APP" \
VTA_FTP_PORT="$FTP_PORT" \
VTA_FTP_USER="$FTP_USER" \
VTA_FTP_PASSWORD="$FTP_PASSWORD" \
SCREENSHOT_DIR="$SCREENSHOT_DIR" \
  ./scripts/emulator_verify.sh

mapfile -t uploaded_files < <(find "$FTP_ROOT" -maxdepth 1 -type f -name '*.Zip' | sort)
if [[ "${#uploaded_files[@]}" -ne 1 ]]; then
  echo "Expected exactly one uploaded ZIP in $FTP_ROOT, found ${#uploaded_files[@]}" >&2
  find "$FTP_ROOT" -maxdepth 2 -type f -ls >&2
  exit 1
fi

UPLOADED_ZIP="${uploaded_files[0]}"
APP_ZIP="$FTP_ROOT/app-side-$(basename "$UPLOADED_ZIP")"
"$ADB" exec-out run-as dev.openvta.logger sh -c 'cat "$(ls -t files/vta/sessions/*.Zip | head -n 1)"' > "$APP_ZIP"

if ! cmp -s "$APP_ZIP" "$UPLOADED_ZIP"; then
  echo "Uploaded ZIP differs from app-side ZIP." >&2
  ls -lh "$APP_ZIP" "$UPLOADED_ZIP" >&2
  exit 1
fi

unzip -t "$UPLOADED_ZIP" >/dev/null
if ! unzip -l "$UPLOADED_ZIP" | grep -q '\.Vta$'; then
  echo "Uploaded ZIP does not contain a .Vta entry." >&2
  unzip -l "$UPLOADED_ZIP" >&2
  exit 1
fi
if ! grep -q 'STOR .*\.Zip' "$SERVER_LOG"; then
  echo "FTP server log did not record a ZIP STOR command." >&2
  cat "$SERVER_LOG" >&2 || true
  exit 1
fi

echo "Local FTP upload verification passed: $(basename "$UPLOADED_ZIP") ($(wc -c < "$UPLOADED_ZIP" | tr -d '[:space:]') bytes)"
echo "FTP root: $FTP_ROOT"
echo "FTP log: $SERVER_LOG"
