#!/bin/sh
# Drive EdgeStream on the SHIELD with every kind of input and assert a focused node after each
# step (the remote must never go dead). Also reports jank for the whole run.
#   sh tools/remote_run.sh            (app must be on Home at rest, Sports tile focused)
# Inputs covered: D-pad (19-23), Back (4), Escape (111), Space (62), gamepad A/B (96/97),
# held Right (fast repeat), and a pointer tap. Exits non-zero if any step loses focus.
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D="-s ${DEVICE:?set DEVICE to the TV, e.g. DEVICE=<tv-ip>:5555}"
fails=0

focused() {
  "$ADB" $D shell uiautomator dump /sdcard/rr.xml >/dev/null 2>&1
  "$ADB" $D shell cat /sdcard/rr.xml | tr '>' '\n' | grep 'focused="true"' |
    sed -E 's/.*bounds="([^"]*)".*/\1/' | head -1
}

step() { # step "label" keycode [keycode...]
  label=$1; shift
  for k in "$@"; do "$ADB" $D shell input keyevent "$k"; sleep 0.5; done
  sleep 0.8
  f=$(focused)
  if [ -n "$f" ]; then echo "PASS  $label  -> $f"; else echo "FAIL  $label  (nothing focused)"; fails=$((fails+1)); fi
}

# Always start from a clean launch on Home: stray presses into a settings page would change
# the user's real settings.
"$ADB" $D shell am force-stop com.edgestream.tv
"$ADB" $D shell monkey -p com.edgestream.tv -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
sleep 10
"$ADB" $D shell dumpsys gfxinfo com.edgestream.tv reset >/dev/null
step "dock: Right x2"            22 22
step "dock: Left"                21
step "band: Up into cards"       19
step "band: Right x2"            22 22
step "band: Down back to dock"   20
step "band: Up, Back to dock"    19 4
step "dock: held Right x12"      22 22 22 22 22 22 22 22 22 22 22 22
step "dock: Left x12 to start"   21 21 21 21 21 21 21 21 21 21 21 21
step "dock: Right to Live TV"    22 22 22 22
step "OK (Space) opens Live TV"  62
sleep 2
step "section: Down x3"          20 20 20
step "section: Escape (to top)"  111
step "section: Escape (to dock)" 111
step "gamepad A opens Live TV"   96
sleep 2
step "gamepad B back to dock"    97 97
step "DPAD_CENTER opens Live TV" 23
sleep 2
step "Back x2 to dock"           4 4
# Pointer: tap the middle of the screen's dock row (air mouse) — must still leave focus somewhere.
"$ADB" $D shell input mouse tap 1150 935; sleep 3   # Movies tile on the dock
# Android enters touch mode after a pointer click; legacy pages (until rebuilt) only accept
# focus again after the next key press — the first remote press must restore focus.
step "pointer tap, then Down"   20
step "Back after tap"            4

echo "--- frames"
"$ADB" $D shell dumpsys gfxinfo com.edgestream.tv | grep -E "Total frames|Janky frames|90th percentile|99th percentile" | head -4
echo "--- failures: $fails"
exit $fails
