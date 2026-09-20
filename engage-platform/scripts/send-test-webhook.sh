#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Send a correctly signed fixture webhook to your LOCAL ingest-api, exactly as
# Razorpay or Shopify would. Use it to check the app before wiring the real
# dashboards to your tunnel.
#
#   scripts/send-test-webhook.sh razorpay
#   scripts/send-test-webhook.sh shopify checkouts/update
#   scripts/send-test-webhook.sh shopify orders/create
#   scripts/send-test-webhook.sh shopify carts/update
#   scripts/send-test-webhook.sh shopify customers/update
#   scripts/send-test-webhook.sh bad-signature        # expect HTTP 401
#
# Needs: bash, curl, openssl (Git Bash on Windows has all three).
# Reads secrets from config/local.env.
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=config/local.env
[[ -f $ENV_FILE ]] || { echo "Missing $ENV_FILE — cp config/local.env.example $ENV_FILE and edit it."; exit 1; }

# Parse KEY=value lines literally (no shell expansion), so passwords
# containing $, ' or spaces are read exactly as written.
while IFS= read -r line || [[ -n $line ]]; do
  [[ -z $line || $line == \#* || $line != *=* ]] && continue
  key=${line%%=*}; value=${line#*=}
  key=$(echo "$key" | tr -d '[:space:]')
  export "$key=$value"
done < "$ENV_FILE"

URL="http://localhost:${INGEST_PORT:-8080}"
FX=ingest-api/src/test/resources/fixtures
STAMP="$(date +%s)-$RANDOM"

case "${1:-}" in
  razorpay|bad-signature)
    FILE=$FX/razorpay_payment_failed.json
    SECRET=$RAZORPAY_WEBHOOK_SECRET
    [[ $1 == bad-signature ]] && SECRET="not-the-real-secret"
    SIG=$(openssl dgst -sha256 -hmac "$SECRET" -hex < "$FILE" | sed 's/^.*= //')
    curl -sS -o /dev/null -w "razorpay payment.failed -> HTTP %{http_code}\n" \
      -X POST "$URL/webhooks/razorpay" \
      -H "Content-Type: application/json" \
      -H "X-Razorpay-Signature: $SIG" \
      -H "x-razorpay-event-id: evt_local_$STAMP" \
      --data-binary @"$FILE"
    ;;
  shopify)
    TOPIC=${2:-orders/create}
    case $TOPIC in
      orders/create)    FILE=$FX/shopify_order_create.json ;;
      checkouts/update) FILE=$FX/shopify_checkout_update.json ;;
      carts/update)     FILE=$FX/shopify_cart_update.json ;;
      customers/update) FILE=$FX/shopify_customer_update.json ;;
      *) echo "Unknown topic $TOPIC"; exit 1 ;;
    esac
    SIG=$(openssl dgst -sha256 -hmac "$SHOPIFY_API_SECRET" -binary < "$FILE" | base64)
    curl -sS -o /dev/null -w "shopify $TOPIC -> HTTP %{http_code}\n" \
      -X POST "$URL/webhooks/shopify" \
      -H "Content-Type: application/json" \
      -H "X-Shopify-Topic: $TOPIC" \
      -H "X-Shopify-Webhook-Id: local-$STAMP" \
      -H "X-Shopify-Shop-Domain: $SHOPIFY_SHOP_DOMAIN" \
      -H "X-Shopify-Hmac-Sha256: $SIG" \
      --data-binary @"$FILE"
    ;;
  *)
    sed -n '3,14p' "$0"; exit 1 ;;
esac
