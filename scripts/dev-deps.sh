#!/usr/bin/env bash
# Builds the local Redis used by the tests and the dev launcher (no Docker needed).
# Postgres comes from the `pgserver` wheel in the dev dependencies.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REDIS_VERSION="${REDIS_VERSION:-7.2.4}"
DEST="$ROOT/.devtools/redis-$REDIS_VERSION"
if [ -x "$DEST/bin/redis-server" ]; then echo "redis $REDIS_VERSION already built: $DEST"; exit 0; fi
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
curl -fsSL "https://download.redis.io/releases/redis-$REDIS_VERSION.tar.gz" -o "$WORK/redis.tgz"
tar -xzf "$WORK/redis.tgz" -C "$WORK"
make -C "$WORK/redis-$REDIS_VERSION" -j"$(nproc)" BUILD_TLS=no >/dev/null
make -C "$WORK/redis-$REDIS_VERSION" PREFIX="$DEST" install >/dev/null
ln -sfn "$DEST" "$ROOT/.devtools/redis"
echo "built $("$DEST/bin/redis-server" --version)"
