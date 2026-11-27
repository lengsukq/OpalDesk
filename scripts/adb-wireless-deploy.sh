#!/usr/bin/env bash
set -euo pipefail

ADB="${ANDROID_SDK_ROOT:-/Volumes/Su-SSD/dependencies/android-sdk}/platform-tools/adb"
MAIN_APK="/Volumes/Su-SSD/Code/OpalDesk/helper/build/outputs/apk/debug/helper-debug.apk"
IME_APK="/Volumes/Su-SSD/Code/OpalYuyanIme/app/build/outputs/apk/debug/app-debug.apk"
START_SCRIPT="/Volumes/Su-SSD/Code/OpalDesk/start_opaldesk.sh"

if [[ ! -x "$ADB" ]]; then
  echo "ERROR: adb not found at $ADB" >&2
  exit 1
fi

pick_adb_port() {
  for port in 5037 5038; do
    if ANDROID_ADB_SERVER_PORT="$port" "$ADB" start-server >/dev/null 2>&1; then
      echo "$port"
      return 0
    fi
  done
  echo "5037"
}

ADB_PORT="$(pick_adb_port)"
export ANDROID_ADB_SERVER_PORT="$ADB_PORT"

MODE="${1:-status}"
TARGET="${2:-192.168.31.105:34125}"

ensure_connected() {
  if [[ "$TARGET" == *:* ]] && ! "$ADB" devices | awk '{print $1, $2}' | grep -Fxq "$TARGET device"; then
    "$ADB" connect "$TARGET"
  fi
}

case "$MODE" in
  status)
    ensure_connected
    echo "=== PACKAGES ==="
    "$ADB" -s "$TARGET" shell "dumpsys package io.github.lengsukq.opaldesk | grep -E 'versionName|lastUpdateTime|codePath' | head -n 6" || true
    "$ADB" -s "$TARGET" shell "dumpsys package io.github.lengsukq.opaldesk.ime | grep -E 'versionName|lastUpdateTime|codePath' | head -n 6" || true
    echo "=== IME LIST ==="
    "$ADB" -s "$TARGET" shell ime list -s | grep -i opal || true
    echo "=== DAEMON ==="
    "$ADB" -s "$TARGET" shell "pgrep -af 'app_process.*OpalDeskDaemon' || ps -ef | grep -i OpalDeskDaemon | grep -v grep" || true
    ;;
  install-main)
    ensure_connected
    "$ADB" -s "$TARGET" install -r -d "$MAIN_APK"
    ;;
  install-ime)
    ensure_connected
    "$ADB" -s "$TARGET" install -r -d "$IME_APK"
    ;;
  start-daemon)
    ensure_connected
    "$ADB" -s "$TARGET" push "$START_SCRIPT" /data/local/tmp/start_opaldesk.sh
    "$ADB" -s "$TARGET" shell "sh /data/local/tmp/start_opaldesk.sh </dev/null >/data/local/tmp/start_opaldesk_run.log 2>&1"
    sleep 1
    "$ADB" -s "$TARGET" shell "cat /data/local/tmp/start_opaldesk_run.log 2>/dev/null || true"
    echo "=== DAEMON ==="
    "$ADB" -s "$TARGET" shell "pgrep -af 'app_process.*OpalDeskDaemon' || ps -ef | grep -i OpalDeskDaemon | grep -v grep" || true
    ;;
  diag-ime)
    ensure_connected
    OUT="/Volumes/Su-SSD/Code/OpalDesk/build/diag-ime.txt"
    mkdir -p "$(dirname "$OUT")"
    {
      echo "=== SETTINGS IME ==="
      "$ADB" -s "$TARGET" shell "echo default=\$(settings get secure default_input_method); echo enabled=\$(settings get secure enabled_input_methods); echo show_hard_kbd=\$(settings get secure show_ime_with_hard_keyboard)" || true
      echo "=== DAEMON LOG (tail 120) ==="
      "$ADB" -s "$TARGET" shell "tail -n 120 /data/local/tmp/opaldesk.log 2>/dev/null || true"
      echo "=== DUMPSYS INPUT_METHOD (key fields) ==="
      "$ADB" -s "$TARGET" shell "dumpsys input_method | grep -E 'mCurMethodId|mCurClient|mCurFocusedWindow|mCurFocusedWindowClient|mInputShown|mImeWindowVis|mDisplayId|mCurrentUserId|OpalYuyan|opaldesk|mCurFocusedWindowSoftInputMode|mShowRequested|mShowForced' | head -n 60" || true
      echo "=== DUMPSYS INPUT (focused + keyboards) ==="
      "$ADB" -s "$TARGET" shell "dumpsys input | grep -E 'FocusedWindows|FocusedApplication|FocusedDisplayId|mFocusedWindow|Keyboard|KeyEvent|Device [0-9]+:' | head -n 50" || true
      echo "=== DUMPSYS WINDOW (focused) ==="
      "$ADB" -s "$TARGET" shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|mInputMethodTarget|mImeLayeringTarget|Display #' | head -n 40" || true
      echo "=== LOGCAT (IME / OpalDesk / Yuyan) ==="
      "$ADB" -s "$TARGET" shell "logcat -d -t 800 | grep -iE 'opaldesk|opalyuyan|yuyan|InputMethodManager|ImeTracker|showSoftInput|dispatchKeyEvent|BridgeProvider|pluginImeSync' | tail -n 100" || true
    } > "$OUT" 2>&1
    echo "WROTE $OUT ($(wc -l < "$OUT") lines)"
    ;;
  *)
    echo "Usage: $0 {status|install-main|install-ime|start-daemon|diag-ime} [target]" >&2
    exit 1
    ;;
esac
