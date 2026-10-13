#!/usr/bin/env bash
# Collects everything that decides whether PHOTON can share photos in the background, from a phone on USB:
# the app build, sign-in and session state, the sync service, battery and background restrictions, scheduled
# jobs, why the process last died, and the app's own log. Read-only: it changes nothing on the phone.
#
#   scripts/phone-diagnose.sh            print the report
#   scripts/phone-diagnose.sh --watch    then follow the app's log live (take a photo with PHOTON in the background)
set -uo pipefail

PACKAGE=com.photon.app
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ADB="$(command -v adb || echo "$SDK/platform-tools/adb")"
WATCH=0
[ "${1:-}" = "--watch" ] && WATCH=1

state="$("$ADB" get-state 2>&1 | head -1)"
if [ "$state" != "device" ]; then
  echo "Phone not ready: $state"
  echo "Unlock the phone and accept \"Allow USB debugging\" (tick \"Always allow from this computer\")."
  exit 1
fi
sh() { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }
section() { printf '\n== %s\n' "$1"; }

section "Phone and app"
echo "phone:   $(sh getprop ro.product.model), Android API $(sh getprop ro.build.version.sdk)"
echo "app:     $(sh dumpsys package $PACKAGE | grep -m1 versionName | xargs), updated $(sh dumpsys package $PACKAGE | grep -m1 lastUpdateTime | cut -d= -f2)"
echo "process: $(sh pidof $PACKAGE || echo 'not running')"

section "Sync service (must be running while you're in a session)"
svc="$(sh dumpsys activity services $PACKAGE | grep -E 'ServiceRecord|isForeground' | head -4)"
echo "${svc:-NOT RUNNING}"
echo "state:   $(sh dumpsys activity processes $PACKAGE | grep -m1 -oE 'curProcState=[0-9]+')  (4 = foreground service, 15+ = cached)"

section "Restrictions"
echo "battery optimisation exempt: $(sh dumpsys deviceidle whitelist | grep -q $PACKAGE && echo yes || echo 'no (the in-app card asks for this)')"
echo "standby bucket:              $(sh am get-standby-bucket $PACKAGE)  (10 active, 20 working set, 30 frequent, 40 rare, 45 restricted)"
echo "run in background (appop):   $(sh cmd appops get $PACKAGE RUN_ANY_IN_BACKGROUND | head -1)  ('ignore' = battery set to Restricted)"
echo "start foreground (appop):    $(sh cmd appops get $PACKAGE START_FOREGROUND | head -1)"
echo "data saver:                  $(sh cmd netpolicy get restrict-background)"
echo "notifications allowed:       $(sh dumpsys package $PACKAGE | grep -m1 -oE 'POST_NOTIFICATIONS: granted=(true|false)')"
echo "photo access:                $(sh dumpsys package $PACKAGE | grep -m1 -oE 'READ_MEDIA_IMAGES: granted=(true|false)')"

section "Scheduled background jobs (WorkManager)"
sh dumpsys jobscheduler $PACKAGE | grep -E 'JOB #|Required constraints|Unsatisfied constraints|Satisfied constraints|trigger|Ready:' | head -24

section "Why the process last died"
sh dumpsys activity exit-info $PACKAGE | grep -E 'timestamp=|reason=' | sed 's/^ *//' | head -8

section "App log (last 40 lines, tag PHOTON)"
sh logcat -d -s PHOTON | tail -40

if [ "$WATCH" = 1 ]; then
  section "Live app log: press Home, take a photo, watch for 'observer', 'queued', 'upload' (Ctrl-C to stop)"
  exec "$ADB" logcat -s PHOTON
fi
