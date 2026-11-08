#!/usr/bin/env bash
# OmniCam smoke test on an Android emulator: opens every mode, takes photos and short recordings,
# switches cameras, goes to the background and back, then runs thousands of random taps (monkey).
# Fails if the app crashes or stops responding. Screenshots and the log are kept as an artifact.
set -u
PKG=app.omnicam.dev
OUT=smoke
mkdir -p "$OUT"
FAIL=0

log() { echo "== $*"; }

crashed() {
  adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null || true
  if grep -q "$PKG" "$OUT/crash.txt"; then return 0; fi
  adb logcat -d | grep -E "ANR in $PKG|FATAL EXCEPTION" -A 30 > "$OUT/fatal.txt" || true
  [ -s "$OUT/fatal.txt" ]
}

check() {
  if crashed; then
    log "CRASH after: $1"
    cat "$OUT/crash.txt" "$OUT/fatal.txt" 2>/dev/null | head -80
    FAIL=1
    # start again so the remaining steps still run
    adb shell am start -W -n "$PKG/app.omnicam.MainActivity" >/dev/null 2>&1
    sleep 4
    adb logcat -c; adb logcat -b crash -c
  fi
}

shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }

# Tap a UI element by its text or accessibility label (Compose exposes both to UI Automator)
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null
  local xy
  xy=$(python3 - "$1" "$OUT/ui.xml" <<'PY'
import re, sys
want, path = sys.argv[1], sys.argv[2]
xml = open(path, encoding="utf-8", errors="ignore").read()
for node in re.finditer(r'<node [^>]*>', xml):
    n = node.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r' content-desc="([^"]*)"', n)
    if (t and t.group(1) == want) or (d and d.group(1) == want):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; return 0; fi
  log "not on screen: $1"; return 1
}

adb logcat -c; adb logcat -b crash -c
log "install"
adb install -r -g app/build/outputs/apk/debug/*.apk || { log "install failed"; exit 1; }
adb shell am start -W -n "$PKG/app.omnicam.MainActivity"
sleep 8
shot 00-start
check "start"

for m in PHOTO PRO VIDEO TIME-LAPSE QR PHOTO; do
  log "mode $m"
  tap "$m" && sleep 4
  shot "mode-$m"
  check "switch to $m"
  case "$m" in
    PHOTO|PRO)
      tap "Take photo" && sleep 4; check "photo in $m"
      adb shell input keyevent KEYCODE_VOLUME_DOWN; sleep 3; check "volume key photo in $m" ;;
    VIDEO|TIME-LAPSE)
      tap "Start recording" && sleep 5; shot "rec-$m"
      tap "Stop recording" && sleep 4; check "recording in $m" ;;
  esac
done

log "front camera"
tap "Switch camera" && sleep 4; shot front; check "flip to front"
tap "Take photo" && sleep 4; check "front photo"
tap "Switch camera" && sleep 4; check "flip back"

log "tap to focus and pinch-free brightness slide"
adb shell input tap 540 700; sleep 1
adb shell input swipe 540 900 540 600 400; sleep 2; check "focus and brightness"

log "settings and info"
tap "Settings" && sleep 2; shot settings; adb shell input keyevent KEYCODE_BACK; sleep 2; check "settings"
tap "Camera info" && sleep 3; shot info; adb shell input keyevent KEYCODE_BACK; sleep 2; check "info"

log "background while recording"
tap "VIDEO" && sleep 3
tap "Start recording" && sleep 3
adb shell input keyevent KEYCODE_HOME; sleep 4
adb shell am start -W -n "$PKG/app.omnicam.MainActivity"; sleep 5
shot after-background; check "home while recording"

log "rotate the device"
adb shell settings put system accelerometer_rotation 0
for r in 1 0 3 0; do adb shell settings put system user_rotation $r; sleep 2; done
check "rotation"

log "photo request from another app"
adb shell am start -W -a android.media.action.IMAGE_CAPTURE -n "$PKG/app.omnicam.MainActivity"; sleep 5
shot external; tap "Take photo"; sleep 5; check "IMAGE_CAPTURE"
adb shell am start -W -n "$PKG/app.omnicam.MainActivity"; sleep 5
shot after-external; check "back after IMAGE_CAPTURE"

log "monkey: random taps and gestures"
adb shell monkey -p "$PKG" --pct-syskeys 0 --pct-appswitch 3 --pct-anyevent 0 --throttle 120 -s 7 -v 4000 \
  > "$OUT/monkey.txt" 2>&1 || true
tail -5 "$OUT/monkey.txt"
if grep -qE "CRASH|NOT RESPONDING" "$OUT/monkey.txt"; then
  log "monkey found a crash"; grep -E "CRASH|NOT RESPONDING" -A 25 "$OUT/monkey.txt" | head -60; FAIL=1
fi
check "monkey"

adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
grep -E "omnicam|OmniCam|AndroidRuntime" "$OUT/logcat.txt" | grep -E " E | W " | tail -60 > "$OUT/errors.txt" || true
log "done, fail=$FAIL"
exit $FAIL
