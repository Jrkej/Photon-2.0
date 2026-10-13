#!/usr/bin/env bash
# An internal CA and a TLS certificate for Redis (and any other internal hop). Usage: scripts/make-certs.sh deploy/certs
set -euo pipefail
OUT="${1:-deploy/certs}"
mkdir -p "$OUT"
cd "$OUT"
[ -f ca.key ] || openssl req -x509 -newkey ed25519 -nodes -keyout ca.key -out ca.pem -days 3650 -subj "/CN=photon-internal-ca"
for name in redis; do
  openssl req -newkey ed25519 -nodes -keyout "$name.key" -out "$name.csr" -subj "/CN=$name"
  printf "subjectAltName=DNS:%s,DNS:%s-replica,DNS:localhost\n" "$name" "$name" > "$name.ext"
  openssl x509 -req -in "$name.csr" -CA ca.pem -CAkey ca.key -CAcreateserial -out "$name.pem" -days 825 -extfile "$name.ext"
  rm -f "$name.csr" "$name.ext"
done
chmod 600 ./*.key
echo "certificates in $OUT"
