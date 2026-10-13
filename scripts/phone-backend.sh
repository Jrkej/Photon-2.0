#!/usr/bin/env bash
# Terminal 1 of the USB phone setup: runs the PHOTON dev backend on this machine.
# API (Auth + orchestrator) on 127.0.0.1:8080, image server on 127.0.0.1:8081. The phone reaches them
# through `adb reverse`, which scripts/phone-install.sh sets up. Leave this running; Ctrl-C stops it.
#
#   scripts/phone-backend.sh                 # data in .devdata/ (kept between runs)
#   scripts/phone-backend.sh --data /tmp/x   # any photon-dev option is passed through
#
# Real sign-in on the phone (optional): pass the provider credentials as environment variables, e.g.
#   PHOTON_GITHUB_CLIENT_ID=... PHOTON_GITHUB_CLIENT_SECRET=... scripts/phone-backend.sh
#     (GitHub OAuth App callback URL: http://127.0.0.1:8080/v1/auth/oauth/return)
#   PHOTON_GOOGLE_CLIENT_ID=<web client id> scripts/phone-backend.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ ! -x .venv/bin/photon-dev ]; then
  echo "Setting up the Python environment (first run only)..."
  python3 -m venv .venv
  .venv/bin/pip install -q --upgrade pip
  .venv/bin/pip install -q -e ".[dev]"
fi

if [ ! -x .devtools/redis/bin/redis-server ]; then
  echo "Building Redis (first run only)..."
  scripts/dev-deps.sh
fi

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
for port in 8080 8081 6390; do
  if port_in_use "$port"; then
    echo "Port $port is already in use. Is another photon-dev running? Stop it first." >&2
    exit 1
  fi
done

echo "Starting the PHOTON dev backend. Next, in another terminal: scripts/phone-install.sh"
exec .venv/bin/photon-dev "$@"
