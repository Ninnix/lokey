#!/usr/bin/env bash
set -eu
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
"$adb" shell input keyevent KEYCODE_HOME
"$adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb" shell am instrument -w -e mode "${1:-gestures}" org.lokey.app.test/org.lokey.app.KeyboardTest
"$adb" exec-out run-as org.lokey.app cat files/test-results.json > artifacts/device-tests.json
python3 -c 'import json; assert json.load(open("artifacts/device-tests.json"))["passed"]'
