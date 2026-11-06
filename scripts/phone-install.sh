#!/usr/bin/env bash
# Terminal 2 of the phone setup: connects phones to the dev backend, then builds, installs and starts the debug
# app. Start scripts/phone-backend.sh first.
#
#   scripts/phone-install.sh             one phone on USB: forward the ports, build, install, start the app
#   scripts/phone-install.sh --all       every phone on USB (testing with two or more phones)
#   scripts/phone-install.sh --reverse   only forward the ports again (after replugging); combine with --all
#   scripts/phone-install.sh --logs      one phone: as the first form, then follow the app's log
#   scripts/phone-install.sh --lan 192.168.1.20 [--all]
#                                        Wi-Fi instead of USB: build for this computer's address, install on any
#                                        phones plugged in now, and print the APK to copy to the others
#   scripts/phone-install.sh --urls https://api.example.com https://img.example.com [--all]
#                                        any network, through Cloudflare Tunnel (cloudflare.md): build for these
#                                        URLs, then install as with --lan
#
# One phone of several: ANDROID_SERIAL=<serial from `adb devices`> scripts/phone-install.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE=com.photon.app
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

REVERSE_ONLY=0
LOGS=0
ALL=0
LAN=""
URL_API=""
URL_IMAGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --reverse) REVERSE_ONLY=1 ;;
    --logs) LOGS=1 ;;
    --all) ALL=1 ;;
    --lan) shift; LAN="${1:-}"; [ -n "$LAN" ] || { echo "--lan needs this computer's address" >&2; exit 2; } ;;
    --urls) shift; URL_API="${1:-}"; shift; URL_IMAGE="${1:-}"
            [ -n "$URL_API" ] && [ -n "$URL_IMAGE" ] || { echo "--urls needs the API URL and the image URL" >&2; exit 2; } ;;
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done
if [ "$ALL" = 1 ] && [ "$LOGS" = 1 ]; then
  echo "--logs follows one phone; with --all, follow each one with: adb -s <serial> logcat -s PHOTON" >&2
  exit 2
fi
if [ -n "$LAN" ] && [ -n "$URL_API" ]; then
  echo "Use --lan or --urls, not both" >&2
  exit 2
fi
REMOTE=""   # set when the phones reach the backend over a network (Wi-Fi or a tunnel), not over USB
if [ -n "$URL_API" ]; then
  # Through a tunnel the phones reach the backend at its public URLs, from any network.
  [ "$REVERSE_ONLY" = 0 ] || { echo "--reverse is for USB; with --urls there is nothing to forward" >&2; exit 2; }
  REMOTE=1
  API="${URL_API%/}"
  IMAGE="${URL_IMAGE%/}"
  WAIT_S="${PHONE_WAIT_S:-3}"
elif [ -n "$LAN" ]; then
  # Over Wi-Fi the phone reaches this computer by its address: no USB port forwarding, and no phone has to be
  # plugged in (the APK can be copied over).
  [ "$REVERSE_ONLY" = 0 ] || { echo "--reverse is for USB; with --lan there is nothing to forward" >&2; exit 2; }
  REMOTE=1
  API="http://$LAN:8080"
  IMAGE="http://$LAN:8081"
  WAIT_S="${PHONE_WAIT_S:-3}"
else
  API=http://127.0.0.1:8080
  IMAGE=http://127.0.0.1:8081
  WAIT_S="${PHONE_WAIT_S:-60}"
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
elif [ -x "$SDK/platform-tools/adb" ]; then
  ADB="$SDK/platform-tools/adb"
else
  echo "adb not found. Install the Android SDK platform-tools, or set ANDROID_HOME." >&2
  exit 1
fi

devices_in() { "$ADB" devices | awk -v s="$1" 'NR > 1 && $2 == s {print $1}'; }
setup_hint="Plug it in and turn on USB debugging: Settings > About phone > tap Build number 7 times, then Settings > System > Developer options > USB debugging."

# 1. Which phones: every authorised one (--all), or exactly one (or the one named by ANDROID_SERIAL).
#    With --lan, none is required.
SERIALS=""
hinted=""
deadline=$((SECONDS + WAIT_S))
while :; do
  ready="$(devices_in device)"
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    ready="$(printf '%s\n' "$ready" | grep -Fx "$ANDROID_SERIAL" || true)"
  fi
  waiting="$(devices_in unauthorized) $(devices_in offline)"
  count="$(printf '%s' "$ready" | grep -c . || true)"
  if [ "$ALL" = 0 ] && [ "$count" -gt 1 ]; then
    echo "Several phones are connected. Set up all of them with: $0 --all" >&2
    echo "or pick one with: ANDROID_SERIAL=<serial> $0" >&2
    "$ADB" devices -l | tail -n +2 >&2
    exit 1
  fi
  if [ "$count" -ge 1 ] && { [ "$ALL" = 0 ] || [ -z "${waiting// /}" ]; }; then
    SERIALS="$ready"
    break
  fi
  if [ -z "$REMOTE" ]; then
    if [ -n "${waiting// /}" ]; then
      hint="Unlock each phone and accept the \"Allow USB debugging\" prompt (waiting for: ${waiting// /})."
    else
      hint="No phone found. $setup_hint"
    fi
    if [ "$hint" != "$hinted" ]; then
      echo "$hint Waiting up to ${WAIT_S}s..."
      hinted="$hint"
    fi
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    if [ "$count" -ge 1 ] || [ -n "$REMOTE" ]; then
      [ "$count" -ge 1 ] && [ -n "${waiting// /}" ] && \
        echo "Continuing with the ready phones; the others still need to accept USB debugging." >&2
      SERIALS="$ready"
      break
    fi
    echo "Gave up waiting for the phone." >&2
    exit 1
  fi
  sleep 1
done

for s in $SERIALS; do
  model="$("$ADB" -s "$s" shell getprop ro.product.model | tr -d '\r')"
  sdk_level="$("$ADB" -s "$s" shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "Phone: $model (Android API $sdk_level, serial $s)"
  if [ "${sdk_level:-0}" -lt 26 ]; then
    echo "  PHOTON needs Android 8.0 (API 26) or newer; skipping this phone." >&2
    SERIALS="$(printf '%s\n' $SERIALS | grep -vFx "$s" || true)"
  fi
done
[ -n "$SERIALS" ] || [ -n "$REMOTE" ] || exit 1

# 2. The backend must be up and reachable at the address the app will use
for url in "$API/readyz" "$IMAGE/readyz"; do
  if ! curl -sf -m 3 "$url" >/dev/null 2>&1; then
    echo "Warning: the backend isn't answering on $API and $IMAGE." >&2
    if [ -n "$URL_API" ]; then
      echo "         Check the tunnels are running and the backend was started with --api-url $API --image-url $IMAGE" >&2
      echo "         (continuing anyway)." >&2
    elif [ -n "$LAN" ]; then
      echo "         Start it with: scripts/phone-backend.sh --host 0.0.0.0 --public-host $LAN (continuing anyway)." >&2
    else
      echo "         Start it in another terminal with scripts/phone-backend.sh (continuing anyway)." >&2
    fi
    break
  fi
done

# 3. USB: each phone's 127.0.0.1:8080 and :8081 reach this machine
if [ -z "$REMOTE" ]; then
  for s in $SERIALS; do
    "$ADB" -s "$s" reverse tcp:8080 tcp:8080 >/dev/null
    "$ADB" -s "$s" reverse tcp:8081 tcp:8081 >/dev/null
    echo "Ports forwarded on $s: phone 127.0.0.1:8080 and :8081 -> this machine"
  done
fi
if [ "$REVERSE_ONLY" = 1 ]; then
  exit 0
fi

# 4. Build once, then install and start the app on each phone plugged in
echo "Building the debug app for $API ..."
(cd "$ROOT/android" && ./gradlew -q assembleDebug \
  -Pphoton.debug.apiBase="$API" -Pphoton.debug.imageBase="$IMAGE")
for s in $SERIALS; do
  echo "Installing on $s..."
  "$ADB" -s "$s" install -r "$APK" >/dev/null
  "$ADB" -s "$s" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
done

n="$(printf '%s\n' $SERIALS | grep -c . || true)"
echo
if [ -n "$REMOTE" ]; then
  echo "Built for $( [ -n "$LAN" ] && echo Wi-Fi || echo the tunnel ): the app talks to $API and $IMAGE." \
       "Every phone in the session needs this build."
  echo "Phones not plugged in: copy this file to the phone and open it (allow \"Install unknown apps\"):"
  echo "  $APK"
  echo "Check each phone reaches the server first: open $API/healthz in its browser; it should show \"ok\"."
fi
echo "PHOTON is installed and open on $n phone(s)."
cat <<GUIDE
  1. Sign in on each phone with a different name: "Developer sign-in", or Google/GitHub if configured.
  2. Create a session on one phone and note the 8-character code; join with it on the others
     (or scan the QR code shown by the first phone). Allow photos ("Allow all") and notifications.
  3. Take a photo on any phone: it appears on the others, and in their galleries under Pictures/PHOTON.
  A computer can join too:
       cd $ROOT
       .venv/bin/photon-cli --profile bob login
       .venv/bin/photon-cli --profile bob join <CODE>
       .venv/bin/photon-cli --profile bob listen --save-dir ./received
GUIDE
if [ -z "$REMOTE" ]; then
  echo "After replugging a cable: $0 --reverse$( [ "$ALL" = 1 ] && echo " --all" || true)"
fi
echo "App log: $ADB -s <serial> logcat -s PHOTON"

if [ "$LOGS" = 1 ] && [ -n "$SERIALS" ]; then
  exec "$ADB" -s "$SERIALS" logcat -s PHOTON
fi
