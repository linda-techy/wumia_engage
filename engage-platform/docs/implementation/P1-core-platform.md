# P1 — Core platform · Implementation

**Weeks 1–2 · Owners: BE1, BE2 · Design:** [`technical/phase-1-core-platform.md`](../technical/phase-1-core-platform.md)

## Status: ◐ built and verified; 6 gap tasks remain

What exists and has passed against PostgreSQL 16:

| Piece | Where | Verified by |
|---|---|---|
| Schema V1–V6 | `db/migration/` | `db/tests/invariants.sql` (17 tests) |
| Identity resolution with trust levels | `V6__identity_functions.sql` → `resolve_identity(jsonb)` | 23 checks, including 8 concurrent resolvers producing one identity |
| Verifiers, phone numbers, money | `core-domain/` | 40 unit tests |
| Webhook intake → inbox → handlers | `ingest-api/` (`ShopifyWebhookController`, `RazorpayWebhookController`, `InboxProcessor`) | `WebhookFlowTest`: natural, reversed and late orderings, plus a poison message |
| Payload parsing in SQL | `ingest-api/src/main/resources/sql/*.sql` | 24 extraction checks |
| Consent from order attributes and Shopify email consent | `ConsentWriter` | end-to-end tests |

**Not yet done:** the code has been compiled against stubs of the Micronaut API in a sandbox, but has never been built with real dependencies from Maven Central. P1-T01 closes that gap, and every other task depends on it.

## Tasks

| ID | Task | Owner | Est. | Depends on |
|---|---|---|---|---|
| P1-T01 | First real `./gradlew build` | BE1 | 0.5–1 d | P0-T08 helps |
| P1-T02 | Fulfillments + courier webhook adapter + V9 shipments | BE2 | 2 d | T01, ADR-005 |
| P1-T03 | Inventory webhooks → `variant_restocked` | BE1 | 1.5 d | T01, `SHOPIFY_ADMIN_TOKEN` |
| P1-T04 | Remaining Shopify topics: cancelled, refunds, paid, products, uninstall | BE1 | 1.5 d | T01 |
| P1-T05 | Inbox housekeeping + metrics | BE2 | 1 d | T01 |
| P1-T06 | Staging deployment | BE2 | 1 d | T01–T05 |

---

### ☐ P1-T01 — First real build

**Why this is a task:** the Micronaut 5 API was compiled against 15 hand-written stub types. Real signatures may differ in small ways: annotation packages, `HttpResponse` factory methods, `@Scheduled` attributes, Jackson 3 packages (`tools.jackson.*`).

**Steps**
1. Install JDK 25 and Postgres 16. Run `scripts/db-setup.sql` (`LOCAL-SETUP.md` §2–3).
2. `cp config/local.env.example config/local.env` and fill in the required values.
3. `./gradlew build`. Fix compile errors **by changing the call site to the real API**. Do not change behaviour.
4. `./gradlew :ingest-api:run`, then `scripts/send-test-webhook.sh razorpay`, `bad-signature` and `shopify checkouts/update`.
5. `psql -d engage_test -f db/tests/invariants.sql`.

**Rules**
- A test that fails after the build compiles is a real bug. Fix the code, not the assertion, unless the assertion contradicts `CLAUDE.md`.
- Record every API delta in `docs/decisions/build-notes.md`, one line each, so the design docs can be corrected.

**Done when**
```bash
./gradlew build                                   # BUILD SUCCESSFUL, 40 unit + WebhookFlowTest green
psql -d engage_test -f db/tests/invariants.sql    # ends with "ALL 17 INVARIANT TESTS PASSED"
scripts/send-test-webhook.sh razorpay             # HTTP 200, then a payment_attempts row
scripts/send-test-webhook.sh bad-signature        # HTTP 401, no inbox row
```

**Claude Code prompt**
> P1-T01. The code in `core-domain/` and `ingest-api/` was written against stubbed Micronaut 5 APIs and has never been built with real dependencies. Run `./gradlew build`. Fix each compile error by adapting the call to the real Micronaut 5.0.x / Jackson 3 API, without changing behaviour. Then run all tests and the invariants SQL. Record every API difference in `docs/decisions/build-notes.md`. Do not add features.

---

### ☐ P1-T02 — Fulfillments and courier events

The design (§4) relies on shipping events to drive `order_tracking` (P4) and `ndr_rescue` (P5). Shopify tells us an order shipped. Only the courier tells us *out for delivery*, *delivered* and *NDR*.

**Migration `V9__shipments_and_order_lifecycle.sql`** (also holds the tables T03 and T04 need, so P1 adds one migration)
```sql
CREATE TABLE shipments (
    id               BIGSERIAL PRIMARY KEY,
    order_id         TEXT,                               -- no FK: fulfillments/create can beat orders/create
    fulfillment_id   TEXT        UNIQUE,                 -- Shopify fulfillment id
    awb              TEXT,
    carrier          TEXT,
    tracking_url     TEXT,
    status           TEXT        NOT NULL DEFAULT 'created'
                     CHECK (status IN ('created','in_transit','out_for_delivery',
                                       'delivered','ndr','rto','cancelled')),
    status_at        TIMESTAMPTZ NOT NULL,               -- time of the courier event, not ours
    ndr_reason       TEXT,
    ndr_attempts     INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX shipments_awb_uq ON shipments (carrier, awb) WHERE awb IS NOT NULL;

CREATE TABLE shipment_events (
    shipment_id  BIGINT      NOT NULL REFERENCES shipments(id),
    source_event TEXT        NOT NULL,                   -- aggregator's event id: dedupe key
    status       TEXT        NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    raw          JSONB       NOT NULL,
    PRIMARY KEY (shipment_id, source_event)
);

ALTER TABLE orders
    ADD COLUMN cancelled_at      TIMESTAMPTZ,
    ADD COLUMN cancel_reason     TEXT,
    ADD COLUMN refunded_paise    BIGINT NOT NULL DEFAULT 0 CHECK (refunded_paise >= 0);
-- financial_status already exists (V3).

CREATE TABLE order_refunds (              -- dedupe for refunds/create
    refund_id    TEXT PRIMARY KEY,
    order_id     TEXT NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE inventory_levels (           -- per-location availability, for P1-T03
    inventory_item_id TEXT NOT NULL,
    location_id       TEXT NOT NULL,
    available         INT  NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (inventory_item_id, location_id)
);

CREATE TABLE variant_prices (             -- last seen price, for price_dropped (P1-T04)
    variant_id  TEXT PRIMARY KEY,
    product_id  TEXT NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    updated_at  TIMESTAMPTZ NOT NULL
);
```

**Order independence (invariant 11).** A courier event can arrive before `fulfillments/create`. When no shipment matches the AWB, the courier handler creates a shipment row with `order_id` NULL; the fulfillment handler then attaches to it via `(carrier, awb)`. `status` only moves forward: a late `in_transit` never overwrites `delivered`. Compare `status_at`, not arrival time.

**Files**
```
ingest-api/src/main/java/in/brand/engage/ingest/courier/CourierWebhookController.java
ingest-api/src/main/java/in/brand/engage/ingest/courier/CourierAdapter.java          # interface
ingest-api/src/main/java/in/brand/engage/ingest/courier/<Aggregator>Adapter.java     # per ADR-005
ingest-api/src/main/java/in/brand/engage/ingest/courier/CourierInboxHandler.java
ingest-api/src/main/resources/sql/shopify_fulfillment.sql
ingest-api/src/main/resources/sql/courier_event.sql
ingest-api/src/test/resources/fixtures/shopify_fulfillment_create.json
ingest-api/src/test/resources/fixtures/courier_*.json                               # ofd, delivered, ndr
```

`CourierAdapter` maps the aggregator's status vocabulary to the seven statuses above, verifies its signature scheme, and returns the dedupe key. The controller writes to `webhook_inbox` with `source='courier'` exactly as the Shopify and Razorpay controllers do.

**Events emitted:** `order_shipped`, `out_for_delivery`, `order_delivered`, `delivery_failed` (NDR), each with `props.order_id` and `props.awb`.

**Tests**
- Fulfillment then courier events; courier events first; `delivered` before `out_for_delivery` → final status `delivered`.
- Same courier event twice → one `shipment_events` row, one event.
- Bad courier signature → 401, no inbox row.

**Done when:** `./gradlew :ingest-api:test` is green including the three orderings, and `SELECT status FROM shipments` shows `delivered` for the reversed-order fixture.

**Claude Code prompt**
> P1-T02. Add `V9__shipments_and_order_lifecycle.sql` exactly as specified (it includes tables T03/T04 use). Add the courier intake following the existing inbox pattern (`RazorpayWebhookController` → `webhook_inbox` → `InboxHandler`). The aggregator is the one recorded in ADR-005; put its specifics only in its `CourierAdapter` implementation. Parse payloads in SQL files like the existing handlers. Status only moves forward by `status_at`. Test natural, reversed and duplicate orderings.

---

### ☐ P1-T03 — Inventory → `variant_restocked`

`inventory_levels/update` carries `inventory_item_id` and `available` per location. It carries neither the variant id nor the total across locations. Back-in-stock (P3-T07) needs "this variant went from 0 to positive".

**Steps**
1. Handler resolves `inventory_item_id → variant_id` with the Admin GraphQL API (`inventoryItem(id) { variant { id product { id } } }`) and caches the mapping in `inventory_state` (V3; one row per inventory item, holding the last **total**).
2. Upsert the payload's location into `inventory_levels` (V9), guarded by `updated_at` so a late update cannot overwrite a newer one. Sum across locations.
3. When the previous total was ≤ 0 and the new one is > 0, write event `variant_restocked` with `{variant_id, product_id, quantity}`, dedupe key `restock:<variant_id>:<inventory_item_updated_at>`.
4. When the previous total was > 0 and the new one is ≤ 0, write `variant_sold_out` (P5-T05 cancels back-in-stock runs on it).

**Files**
```
ingest-api/src/main/java/in/brand/engage/ingest/shopify/ShopifyAdminClient.java   # GraphQL, token from SHOPIFY_ADMIN_TOKEN
ingest-api/src/main/java/in/brand/engage/ingest/shopify/InventoryHandler.java
ingest-api/src/main/resources/sql/shopify_inventory.sql
```

**Config:** `SHOPIFY_ADMIN_TOKEN` and `SHOPIFY_ADMIN_API_VERSION` in `local.env.example` and `LOCAL-SETUP.md` §1.

**Rules**
- The Admin API call happens **outside** the database transaction; the handler is re-run on failure by the inbox's backoff.
- Respect Shopify's GraphQL cost limits: a restock storm after a warehouse sync can produce thousands of updates. Cache the mapping so each item is looked up once.

**Tests:** 0 → 5 emits one event; 5 → 7 emits nothing; 0 → 5 replayed emits one; two locations where one goes 0 → 3 while the other is already 4 emits nothing. The Admin client is faked in tests.

**Done when:** the four tests pass and a manual stock change on the dev store produces one `variant_restocked` row.

**Claude Code prompt**
> P1-T03. Implement `inventory_levels/update` handling per this task. Resolve inventory item → variant with the Shopify Admin GraphQL API outside the transaction, cache in `inventory_state`, keep per-location rows in `inventory_levels`, and emit `variant_restocked` only on a 0 → positive transition of the total across locations. Fake the Admin client in tests.

---

### ☐ P1-T04 — Remaining Shopify topics

| Topic | Effect | Event |
|---|---|---|
| `orders/cancelled` | `orders.cancelled_at`, `cancel_reason` | `order_cancelled` (cancels live cascades for this order from P4) |
| `refunds/create` | insert into `order_refunds` (dedupe on refund id); `orders.refunded_paise` = sum for the order | `refund_initiated` |
| `orders/paid` | `orders.financial_status` | none |
| `products/update` | variant prices into `variant_prices` | `price_dropped` when a variant's price falls |
| `app/uninstalled` | log + alert; stop the inbox processor accepting new Shopify work | none |

**Money:** Shopify sends decimal strings (`"1499.00"`). Convert with `Paise.ofRupees`, which throws on more than two decimals. Never parse to `double`.

**Tests:** each topic with natural and replayed delivery; a refund replayed twice adds once; a price rise emits nothing.

**Done when:** all topics in `LOCAL-SETUP.md` §6.3 have a handler and a fixture, and `./gradlew :ingest-api:test` is green.

**Claude Code prompt**
> P1-T04. Add handlers for the five Shopify topics in this task, following `ShopifyInboxHandler` and its SQL-file pattern. Idempotent and order-independent. Money via `Paise.ofRupees`. One fixture per topic.

---

### ☐ P1-T05 — Housekeeping and metrics

**Jobs** (`@Scheduled`, IST cron, single-runner via `pg_try_advisory_lock`)
- Delete `webhook_inbox` rows processed more than 7 days ago. Never delete unprocessed rows.
- Report rows at `attempts >= 10` as dead letters (metric + log line with source, topic, delivery_id, last_error).

**Metrics** (Micrometer, Prometheus endpoint)
| Metric | Type | Alert |
|---|---|---|
| `engage_inbox_pending` | gauge | > 1,000 for 5 min |
| `engage_inbox_oldest_pending_seconds` | gauge | > 300 |
| `engage_inbox_dead_letters` | gauge | > 0 |
| `engage_webhook_received_total{source,topic,result}` | counter | 401 rate > 1% |
| `engage_handler_seconds{source,topic}` | timer | p99 > 2 s |

**Done when:** `curl localhost:8080/prometheus` shows all five, and a poison fixture drives `engage_inbox_dead_letters` to 1.

---

### ☐ P1-T06 — Staging deployment

1. Container image with Jib or the Micronaut Docker plugin.
2. Managed Postgres 16 in `ap-south-1`. Flyway runs as a separate job before the app (`08-deployment-and-ops.md`), and `FLYWAY_ON_STARTUP=false` in staging.
3. Secrets from the secret manager into env vars. No `local.env` in the image.
4. Public HTTPS URL. Register it as the Shopify and Razorpay (Test Mode) webhook targets.

**Done when:** a real test-mode order on the dev store and a failed Razorpay test payment both appear in staging's `orders` and `payment_attempts`, and `payment_failure_match_report` shows a match.

---

## Exit gate

- [ ] T01: real build green, invariants green
- [ ] T02–T04: every Shopify and courier topic the later phases consume has a handler, tested in three orderings
- [ ] T05: dead letters visible
- [ ] T06: staging receives real webhooks
- [ ] P0-T06 spike run against staging, ADR-002 written
