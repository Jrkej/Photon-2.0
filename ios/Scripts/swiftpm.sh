#!/usr/bin/env bash
# Runs `swift <args>` inside ios/PhotonKit with every SwiftPM, Foundation and clang cache
# kept under PhotonKit/.build/.local-state, so a build or test run writes nothing to $HOME
# or /tmp. Uses the repo-local toolchain at .devtools/swift when it exists.
#
# Examples:  ios/Scripts/swiftpm.sh build
#            ios/Scripts/swiftpm.sh test
#            ios/Scripts/swiftpm.sh test --filter UploadEngineTests
set -euo pipefail

IOS="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$IOS/.." && pwd)"
PKG="$IOS/PhotonKit"

if [ -x "$ROOT/.devtools/swift/usr/bin/swift" ]; then
  export PATH="$ROOT/.devtools/swift/usr/bin:$PATH"
fi
command -v swift >/dev/null || { echo "error: swift not found" >&2; exit 1; }

STATE="$PKG/.build/.local-state"
mkdir -p "$STATE/home" "$STATE/cache" "$STATE/tmp"
export HOME="$STATE/home" XDG_CACHE_HOME="$STATE/cache" TMPDIR="$STATE/tmp"

# SwiftPM finds the home directory through the user database, not $HOME, so its config,
# security and cache directories are also passed explicitly.
SPM_PATHS=(--cache-path "$STATE/cache/swiftpm" --config-path "$STATE/swiftpm-config" --security-path "$STATE/swiftpm-security")

# Even so, SwiftPM creates an empty ~/.swiftpm skeleton (configuration/, security/ and a
# cache symlink pointing back here). When it did not exist before this run, remove exactly
# that skeleton afterwards: rmdir only removes empty directories.
REAL_HOME="$(getent passwd "$(id -un)" 2>/dev/null | cut -d: -f6)"
DOT="${REAL_HOME:-/nonexistent}/.swiftpm"
HAD_DOT=0
[ -e "$DOT" ] || [ -L "$DOT" ] && HAD_DOT=1

cd "$PKG"
set +e
case "${1:-}" in
  build|test|run|package)
    sub="$1"; shift
    swift "$sub" "${SPM_PATHS[@]}" "$@" ;;
  *)
    swift "$@" ;;
esac
STATUS=$?
set -e

if [ "$HAD_DOT" = 0 ] && [ -d "$DOT" ]; then
  if [ -L "$DOT/cache" ]; then
    case "$(readlink "$DOT/cache")" in "$STATE"/*) rm -f "$DOT/cache" ;; esac
  fi
  rmdir "$DOT/configuration" "$DOT/security" "$DOT" 2>/dev/null || true
fi
exit $STATUS
