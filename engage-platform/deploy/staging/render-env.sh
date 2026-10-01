#!/bin/sh
# Render /opt/wumika-staging/.env from AWS SSM Parameter Store (P1-T06).
# Every parameter under /wumika/staging/ becomes NAME=value, e.g.
#   /wumika/staging/DB_PASSWORD  (SecureString)  ->  DB_PASSWORD=...
# Runs on the staging host with an instance role allowed ssm:GetParametersByPath
# and kms:Decrypt on that path only. Values are never printed.
set -eu
umask 077
OUT="${1:-/opt/wumika-staging/.env}"
TMP="$(mktemp "${OUT}.XXXXXX")"
aws ssm get-parameters-by-path --region ap-south-1 --path /wumika/staging/ --recursive --with-decryption \
    --query 'Parameters[].[Name,Value]' --output text |
  while IFS="$(printf '\t')" read -r name value; do
    printf '%s=%s\n' "${name##*/}" "$value"
  done > "$TMP"
test -s "$TMP" || { echo "render-env: no parameters under /wumika/staging/" >&2; rm -f "$TMP"; exit 1; }
mv "$TMP" "$OUT"
echo "render-env: wrote $(wc -l < "$OUT") variables to $OUT"
