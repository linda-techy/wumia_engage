# Load test (P7-T04)

Replays real traffic at ingest-api: Shopify carts, checkouts and orders, Razorpay failed payments, Meta delivery statuses, pixel events and storefront prompt events. The shapes are the ingest-api test fixtures, made distinct per request and signed per request, so the real signature checks and handlers run. The full profile ramps to **400 events/s** over 5 minutes, holds for 15, and ramps down over 2.

## Never production

- **The target must be staging,** with no WhatsApp, MSG91, SES or Shopify Admin credentials. The shoppers are synthetic (`load+NNNNN@example.com`, `+91 99xxx xxxxx`), and without those credentials nothing can be sent to anyone. Leave Firebase in place: the load creates no push subscribers, so nothing is pushed.
- **The script refuses to start unless you name the host twice** (`TARGET` and `ALLOW_TARGET`). It refuses anything that looks like production (`*.wumika.com` other than engage-dev, `engage.*`, `*.myshopify.com`), and allows only `PROFILE=smoke` on the shared dev box. It does this in `setup()`, before the first request.
- **Allowlist:** set `CUSTOMER_ALLOWLIST_EMAILS=*` on staging for the run. With a restricted allowlist, most events are acknowledged and dropped, which is cheaper than real traffic and proves nothing.
- **Topics left out on purpose:** `inventory_levels/update` (it calls the Shopify Admin API) and SES notices (they fetch AWS certificates).

## Run

The secrets are the target's own values. Pass them through the environment, not the command line.

```bash
export TARGET=https://staging.example ALLOW_TARGET=staging.example
export SHOPIFY_API_SECRET=… SHOPIFY_SHOP_DOMAIN=… RAZORPAY_WEBHOOK_SECRET=… WA_APP_SECRET=… PIXEL_WRITE_KEY=…

PROFILE=smoke k6 run load/k6/webhooks.js      # 20 events/s for a minute: is everything wired?
k6 run load/k6/webhooks.js                    # the real run: 22 minutes

TARGET=… METRICS_TOKEN=… PSQL='psql "postgres://…"' load/k6/after.sh   # right after the burst
```

`SHOPPERS` (default 5000) sets the pool of synthetic shoppers that events are spread across.

## Pass bar

| Check | Where | Limit |
|---|---|---|
| Webhook acknowledgement | k6 `http_req_duration{kind:webhook}` | p99 < 500 ms |
| Server errors | k6 `server_errors` | 0 |
| Storefront calls | k6 `http_req_duration{kind:storefront}` | p99 < 800 ms |
| The test kept its rate | k6 `dropped_iterations` | < 50 (more means too few VUs, not a slow target) |
| Inbox backlog drains | `after.sh` (`engage_inbox_pending`) | 0 within 5 minutes |
| No pool exhaustion | `after.sh` (`hikaricp_connections_timeout_total`) | 0 |
| Cascade ticker lag | `after.sh` with `PSQL` | < 30 s |

k6 exits non-zero when a threshold fails; `after.sh` does too. Anything it cannot read counts as a FAIL, never a pass. Record each run in `docs/qa/p7-load.md`.

After the run, delete the synthetic data:

```sql
DELETE FROM identities WHERE id IN (SELECT identity_id FROM identity_keys WHERE value LIKE 'load+%@example.com');
```

Or run `erase_identity` per identity if the audit trail matters on staging.
