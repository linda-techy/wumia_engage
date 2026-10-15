# P7-T04 — Load test results

Written for: whoever runs the load test on staging and signs off go-live. How to run it, and the pass bar: [load/k6/README.md](../../load/k6/README.md).

## Script check (local, 2026-10-15)

Not a load result. It proves the scripts, signatures and checks work, against a local ingest-api on the test database (one JVM, Hikari pool 10, laptop).

- **k6 smoke:** k6 v2.3.0, `PROFILE=smoke`, 20 events/s for 60 s.
  - 1,100 requests, all accepted (every signature verified), 0 server errors.
  - Webhook p99 38 ms, storefront p99 51 ms; 0 dropped iterations.
- **Processing:**
  - The inbox stored and processed every event with no errors: 153 carts, 180 checkouts, 62 orders, 54 Razorpay failures, 94 Meta statuses.
  - 281 synthetic shoppers were resolved into identities.
- **`after.sh`:**
  - Inbox drained in 16 s, no pool timeouts, cascade lag 0 s.
  - It fails, rather than passes, with a wrong metrics token or a broken `PSQL`. Both were checked, after a first version passed them silently.
- **The safety gate** refused a production-looking host, `www.wumika.com`, an unconfirmed host, and the full profile on engage-dev, each before any request.

## Staging runs

Run on staging with production-sized Postgres (P7-T07), `CUSTOMER_ALLOWLIST_EMAILS=*` and no provider credentials.

| Date | Build | Profile | Peak rate | Webhook p99 | Storefront p99 | 5xx | Dropped | Inbox drained | Pool timeouts | Ticker lag | Result |
|---|---|---|---|---|---|---|---|---|---|---|---|
| | | full | 400/s | | | | | | | | |

Notes for each run: pod counts, Hikari pool size, Postgres instance class, anything that failed and what was changed.
