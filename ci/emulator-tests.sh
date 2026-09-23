#!/usr/bin/env bash
# Runs on the CI emulator (adb is on PATH). Produces ci/evidence/ with screenshots, logcat,
# the app database and the instrumented test reports, then smoke-tests the release APK.
set -euo pipefail

PKG=com.khatwa.app
EVIDENCE=ci/evidence
mkdir -p "$EVIDENCE"

DEBUG_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RELEASE_APK=app/build/outputs/apk/release/app-release.apk

adb wait-for-device
# Make the device usable for UI automation: mark setup complete, keep the screen on,
# wake it and dismiss the (swipe) keyguard. Without this the app window is behind the
# lock screen and UiAutomator sees nothing.
prepare_screen() {
  # Never let "System UI isn't responding" / crash dialogs cover the app on the slow headless emulator.
  adb shell settings put global hide_error_dialogs 1 || true
  adb shell settings put global device_provisioned 1 || true
  adb shell settings put secure user_setup_complete 1 || true
  adb shell svc power stayon true || true
  adb shell input keyevent KEYCODE_WAKEUP || true
  adb shell wm dismiss-keyguard || true
  adb shell input keyevent 82 || true
  sleep 2
}
diag() {
  adb shell screencap -p "/sdcard/khatwa-evidence/$1.png" || true
  {
    echo "== $1 =="; date
    adb shell dumpsys window windows | grep -E "mCurrentFocus|mFocusedApp" || true
    adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" || true
    adb shell dumpsys window | grep -iE "keyguard(Showing|Occluded)|mDreamingLockscreen|isKeyguard" | head -5 || true
  } >> "$EVIDENCE/window-state.txt" 2>&1
}
prepare_screen
adb logcat -c || true
adb logcat -v time > "$EVIDENCE/logcat.txt" 2>&1 &
LOGCAT_PID=$!

finish() {
  set +e
  echo "== collecting evidence =="
  adb pull /sdcard/khatwa-evidence "$EVIDENCE/" 2>/dev/null || true
  adb exec-out run-as $PKG cat databases/khatwa.db > "$EVIDENCE/khatwa.db" 2>/dev/null || true
  adb exec-out run-as $PKG sh -c 'cd files/evidence 2>/dev/null && tar cf - .' > "$EVIDENCE/hierarchy.tar" 2>/dev/null || true
  (cd "$EVIDENCE" && mkdir -p hierarchy && tar xf hierarchy.tar -C hierarchy 2>/dev/null; rm -f hierarchy.tar) || true
  adb shell dumpsys activity services $PKG > "$EVIDENCE/dumpsys-services.txt" 2>/dev/null || true
  adb shell "settings get secure enabled_accessibility_services" > "$EVIDENCE/accessibility-setting.txt" 2>/dev/null || true
  cp -r app/build/reports/androidTests "$EVIDENCE/androidTest-report" 2>/dev/null || true
  cp -r app/build/outputs/androidTest-results "$EVIDENCE/androidTest-results" 2>/dev/null || true
  kill $LOGCAT_PID 2>/dev/null || true
  ls -la "$EVIDENCE" || true
}
trap finish EXIT

echo "== device =="
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell "mkdir -p /sdcard/khatwa-evidence"

echo "== install debug + test APKs =="
adb uninstall $PKG >/dev/null 2>&1 || true
adb install -r -g "$DEBUG_APK"
adb install -r "$TEST_APK"
adb shell pm grant $PKG android.permission.ACTIVITY_RECOGNITION || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow || true
adb shell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow || true

echo "== instrumented tests =="
prepare_screen
diag "00-before-tests"
./gradlew :app:connectedDebugAndroidTest --stacktrace
adb shell screencap -p /sdcard/khatwa-evidence/99-after-tests.png || true

echo "== release smoke test =="
adb uninstall $PKG >/dev/null 2>&1 || true
adb install -r -g "$RELEASE_APK"
adb shell pm grant $PKG android.permission.ACTIVITY_RECOGNITION || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb logcat -c || true
prepare_screen
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 10
diag "release-00-launched"
adb shell screencap -p /sdcard/khatwa-evidence/release-01-launch.png
# Walk through onboarding by tapping the "next"/"start" button found via uiautomator dump.
tap_text() {
  adb shell uiautomator dump /sdcard/khatwa-evidence/ui.xml >/dev/null 2>&1 || return 1
  adb pull /sdcard/khatwa-evidence/ui.xml /tmp/ui.xml >/dev/null 2>&1 || return 1
  python3 - "$@" <<'PY'
import re, sys, subprocess
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for label in sys.argv[1:]:
    m = re.search(r'text="' + re.escape(label) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not m:
        m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="' + re.escape(label) + r'"', xml)
    if m:
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        subprocess.run(['adb', 'shell', 'input', 'tap', str(x), str(y)])
        print(f'tapped {label} at {x},{y}')
        sys.exit(0)
print('button not found'); sys.exit(1)
PY
}
for i in 1 2 3 4 5 6 7 8; do
  tap_text "التالي" "ابدأ" || break
  sleep 1.5
done
sleep 4
adb shell screencap -p /sdcard/khatwa-evidence/release-02-after-onboarding.png
PID=$(adb shell pidof $PKG | tr -d '\r' || true)
if [ -z "$PID" ]; then
  echo "FAIL: release app process is not running"; exit 1
fi
if adb logcat -d | grep -A3 "FATAL EXCEPTION" | grep -q "$PKG"; then
  echo "FAIL: release app crashed"; adb logcat -d | grep -A30 "FATAL EXCEPTION" | head -60; exit 1
fi
adb shell dumpsys activity services $PKG | grep -q "StepService" && echo "OK: StepService running in release" || echo "note: StepService not listed (onboarding may not be finished)"
echo "OK: release APK launched without crashing (pid $PID)"
