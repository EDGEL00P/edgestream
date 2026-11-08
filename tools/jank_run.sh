#!/bin/sh
# Smoothness only: drives a fixed, realistic remote sequence on the SHIELD with NO uiautomator
# dumps (those are heavy and pollute frame stats), then prints the app's frame statistics.
# Start on Home at rest.   sh tools/jank_run.sh [label]
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D="-s ${DEVICE:?set DEVICE to the TV, e.g. DEVICE=<tv-ip>:5555}"
k() { for c in "$@"; do "$ADB" $D shell input keyevent "$c"; sleep 0.35; done; }

# Always start from a clean launch on Home: stray presses into a settings page would change
# the user's real settings.
"$ADB" $D shell am force-stop com.edgestream.tv
"$ADB" $D shell monkey -p com.edgestream.tv -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
sleep 10
"$ADB" $D shell dumpsys gfxinfo com.edgestream.tv reset >/dev/null
k 22 22 21 21 22 22 22 21 21 21          # browse the dock
k 19 22 22 22 21 21 21 20                # into the band and back
k 22 22 22 22 21 21 21 21                # dock again
sleep 1
"$ADB" $D shell dumpsys gfxinfo com.edgestream.tv > "${TMPDIR:-/tmp}/gfx_${1:-run}.txt"
grep -E "Total frames|Janky frames|50th percentile|90th percentile|99th percentile|Slow UI thread|Slow bitmap|Slow issue draw|Frame deadline missed" "${TMPDIR:-/tmp}/gfx_${1:-run}.txt" | head -9
