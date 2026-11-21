#!/usr/bin/env bash
# Runs inside the emulator job: install, set the scene, run ScreenshotTest, pull the PNGs into ./shots.
set -euo pipefail
APP=il.yishai.parking
adb wait-for-device
adb install -r -g android/app/build/outputs/apk/debug/app-debug.apk
adb install -r -g android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do adb shell pm grant $APP android.permission.$p || true; done

# Clean status bar: fixed clock, full battery and signal, no notification icons.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1058 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

# Stand on Usishkin 54 (zone 9). GPS fixes repeat so the fused provider has a fresh one.
adb shell settings put secure location_mode 3 || true
for i in 1 2 3; do adb emu geo fix 34.77917 32.09501; sleep 1; done
( while true; do adb emu geo fix 34.77917 32.09501 >/dev/null 2>&1; sleep 5; done ) &
GEO=$!

set +e
adb shell am instrument -w -e class $APP.ScreenshotTest $APP.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.txt
set -e
kill $GEO || true

rm -rf shots && mkdir shots
adb pull /sdcard/Android/data/$APP/files/screenshots/. shots/ || true
ls -la shots
adb logcat -d -s AndroidRuntime:E parking:W | tail -60 || true
grep -q "^OK (" instrument.txt || { echo "walkthrough failed"; exit 1; }
