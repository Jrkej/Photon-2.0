#!/usr/bin/env bash
# Runs the PHOTON benchmarks (tests/bench) on their own stack: a real Redis, an embedded Postgres and the three
# services on random ports, so a running dev backend is never touched. Results: tests/bench/results/latest.md
#
#   scripts/bench.sh               all benchmarks (about 2-3 minutes)
#   scripts/bench.sh -k upload     only the ones whose name matches
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[ -x .devtools/redis/bin/redis-server ] || scripts/dev-deps.sh
exec .venv/bin/python -m pytest tests/bench -m bench -s -q -p no:cacheprovider "$@"
