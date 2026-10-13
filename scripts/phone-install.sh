#!/usr/bin/env bash
# Terminal 2 of the USB phone setup: connects a phone on USB to the dev backend, then builds, installs and
# starts the debug app. Start scripts/phone-backend.sh first.
#
#   scripts/phone-install.sh             forward the ports, build, install, start the app
#   scripts/phone-install.sh --reverse   only forward the ports again (needed after replugging the cable)
#   scripts/phone-install.sh --logs      same as the first form, then follow the app's log
#
# Several phones connected: ANDROID_SERIAL=<serial from `adb devices`> scripts/phone-install.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE=com.photon.app
API=http://127.0.0.1:8080
IMAGE=http://127.0.0.1:8081
WAIT_S="${PHONE_WAIT_S:-60}"

REVERSE_ONLY=0
LOGS=0
for arg in "$@"; do
  case "$arg" in
    --reverse) REVERSE_ONLY=1 ;;
    --logs) LOGS=1 ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (see --help)" >&2; exit 2 ;;
  esac
done

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
elif [ -x "$SDK/platform-tools/adb" ]; then
  ADB="$SDK/platform-tools/adb"
else
  echo "adb not found. Install the Android SDK platform-tools, or set ANDROID_HOME." >&2
  exit 1
fi

# 1. Exactly one authorised phone (or the one named by ANDROID_SERIAL)
hinted=""
deadline=$((SECONDS + WAIT_S))
while :; do
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    states="$("$ADB" devices | awk -v s="$ANDROID_SERIAL" 'NR > 1 && $1 == s {print $2}')"
  else
    states="$("$ADB" devices | awk 'NR > 1 && NF >= 2 {print $2}')"
  fi
  count="$(printf '%s' "$states" | grep -c . || true)"
  if [ "$count" -gt 1 ]; then
    echo "Several devices are connected. Pick one:" >&2
    "$ADB" devices -l | tail -n +2 >&2
    echo "then run: ANDROID_SERIAL=<serial> $0 $*" >&2
    exit 1
  fi
  if [ "$states" = "device" ]; then
    break
  fi
  case "$states" in
    unauthorized) hint="Unlock the phone and accept the \"Allow USB debugging\" prompt." ;;
    offline) hint="The phone is offline: unplug it and plug it in again." ;;
    *) hint="No phone found. Plug it in and turn on USB debugging: Settings > About phone > tap Build number 7 times, then Settings > System > Developer options > USB debugging." ;;
  esac
  if [ "$hint" != "$hinted" ]; then
    echo "$hint Waiting up to ${WAIT_S}s..."
    hinted="$hint"
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "Gave up waiting for the phone." >&2
    exit 1
  fi
  sleep 2
done

model="$("$ADB" shell getprop ro.product.model | tr -d '\r')"
sdk_level="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "Phone: $model (Android API $sdk_level)"
if [ "${sdk_level:-0}" -lt 26 ]; then
  echo "PHOTON needs Android 8.0 (API 26) or newer." >&2
  exit 1
fi

# 2. The backend must be up on this machine
backend_ok=1
for url in "$API/readyz" "$IMAGE/readyz"; do
  if ! curl -sf -m 3 "$url" >/dev/null 2>&1; then
    backend_ok=0
  fi
done
if [ "$backend_ok" = 0 ]; then
  echo "Warning: the backend isn't answering on $API and $IMAGE." >&2
  echo "         Start it in another terminal with scripts/phone-backend.sh (continuing anyway)." >&2
fi

# 3. The phone's 127.0.0.1:8080 and :8081 now reach this machine
"$ADB" reverse tcp:8080 tcp:8080 >/dev/null
"$ADB" reverse tcp:8081 tcp:8081 >/dev/null
echo "Ports forwarded: phone 127.0.0.1:8080 and :8081 -> this machine"
if [ "$REVERSE_ONLY" = 1 ]; then
  exit 0
fi

# 4. Build and install the debug app pointed at those ports, then start it
echo "Building and installing the debug app..."
(cd "$ROOT/android" && ./gradlew -q installDebug \
  -Pphoton.debug.apiBase="$API" -Pphoton.debug.imageBase="$IMAGE")
"$ADB" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

cat <<EOF

PHOTON is installed and open on the phone.
  1. Sign in: "Developer sign-in" with any username, or Google/GitHub if the backend has them configured.
  2. Create a session and note the 8-character code. Allow photos ("Allow all") and notifications.
  3. Add a second member from this machine:
       cd $ROOT
       .venv/bin/photon-cli --profile bob login
       .venv/bin/photon-cli --profile bob join <CODE>
       .venv/bin/photon-cli --profile bob listen --save-dir ./received
  4. Take a photo on the phone: it appears in ./received/.
     .venv/bin/photon-cli --profile bob upload some.jpg  -> it appears in the phone's Pictures/PHOTON.
After replugging the cable: $0 --reverse
App log: $ADB logcat -s PHOTON
EOF

if [ "$LOGS" = 1 ]; then
  exec "$ADB" logcat -s PHOTON
fi
