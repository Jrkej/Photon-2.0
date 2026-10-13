#!/usr/bin/env bash
# Regenerates PhotonKit's protobuf models from the authoritative proto/photon.proto.
#
# The plugin (protoc-gen-swift) is built from the swift-protobuf checkout that PhotonKit
# itself depends on, so the generated code always matches the runtime library version.
#
# Needs: a Swift 6 toolchain (Xcode on macOS, or swift.org on Linux; the repo-local one at
# .devtools/swift is used when present, via Scripts/swiftpm.sh) and protoc (macOS: `brew install protobuf`;
# Ubuntu: `apt install protobuf-compiler`).
#
# Usage: ios/Scripts/generate-proto.sh          # regenerate in place
#        ios/Scripts/generate-proto.sh --check  # fail if the checked-in file is stale
set -euo pipefail

IOS="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$IOS/.." && pwd)"
PKG="$IOS/PhotonKit"
PROTO_DIR="$ROOT/proto"
OUT_DIR="$PKG/Sources/PhotonKit/Proto"

SWIFTPM="$IOS/Scripts/swiftpm.sh"   # keeps SwiftPM caches inside PhotonKit/.build
command -v protoc >/dev/null || { echo "error: protoc not found (brew install protobuf)" >&2; exit 1; }

"$SWIFTPM" package resolve >/dev/null
"$SWIFTPM" build -c release --product protoc-gen-swift >/dev/null
PLUGIN="$("$SWIFTPM" build -c release --show-bin-path)/protoc-gen-swift"

TARGET="$OUT_DIR"
if [ "${1:-}" = "--check" ]; then
  TARGET="$PKG/.build/proto-check"
  rm -rf "$TARGET" && mkdir -p "$TARGET"
fi

protoc \
  --plugin=protoc-gen-swift="$PLUGIN" \
  --swift_opt=Visibility=Public \
  --swift_opt=FileNaming=DropPath \
  --swift_out="$TARGET" \
  -I "$PROTO_DIR" \
  "$PROTO_DIR/photon.proto"

if [ "${1:-}" = "--check" ]; then
  if ! diff -q "$TARGET/photon.pb.swift" "$OUT_DIR/photon.pb.swift" >/dev/null; then
    echo "error: $OUT_DIR/photon.pb.swift is stale; run ios/Scripts/generate-proto.sh" >&2
    exit 1
  fi
  echo "photon.pb.swift is up to date"
else
  echo "generated $OUT_DIR/photon.pb.swift with $("$PLUGIN" --version 2>/dev/null || echo protoc-gen-swift)"
fi
