# Local setup — Phase 1 on your machine

At the end of this guide, a local **PostgreSQL** database holds the Engage schema, and the **ingest service** runs on `http://localhost:8081` (8080 is taken on this machine; see §5). It receives signed **Shopify** and **Razorpay** webhooks through a tunnel and turns them into identities, checkouts, orders, payment attempts and consent records. That is everything the Phase 0 Razorpay spike needs.

Time: about 30 minutes the first time.

---

## 1. Your values — fill these in

> **Put real values in `config/local.env`, not in this document.** This file can end up in git; `config/local.env` is git-ignored. The table only tells you what goes where.

| Setting (in `config/local.env`) | What it is | Where you get it | Your value |
|---|---|---|---|
| `DB_HOST` | Postgres host | Local install: `localhost` | `localhost` |
| `DB_PORT` | Postgres port | Default `5432` | `5432` |
| `DB_NAME` | Main database | Fixed for this project | `wumika_admin` |
| `DB_USER` | App login | The Postgres superuser (existing role; the setup script only resets its password) | `postgres` |
| `DB_PASSWORD` | Password of `postgres` | Set when installing Postgres | already in `config/local.env` |
| `TEST_DB_NAME` | Test database | Must end in `_test` | `wumika_admin_test` |
| *(superuser)* | Only for the one-time setup script | Same `postgres` login as `DB_USER` | `postgres` |
| `CUSTOMER_ALLOWLIST_EMAILS` | The only customers Engage may store, process or message. The store is live. | Comma-separated emails; `*` = everyone, **only when explicitly approved**. Empty = the app refuses to start. | `marylindajohnson22@gmail.com` |
| `SHOPIFY_SHOP_DOMAIN` | Your store | The myshopify domain, not the custom domain `www.wumika.com` | `e4bac4-ef.myshopify.com` |
| `SHOPIFY_API_SECRET` | App **Client secret** | Dev Dashboard → your app → Settings | already in `config/local.env` |
| `SHOPIFY_CLIENT_ID` | App **Client ID** | Same page (needed from Phase 2) | already in `config/local.env` |
| `SHOPIFY_ADMIN_TOKEN` | Optional Admin API token (P1-T03: inventory item → variant) | Leave blank: ingest-api exchanges the Client ID and secret for a 24 h token itself (client credentials grant; app and store in the same Dev Dashboard organization). The app needs `read_inventory` and `read_products` approved on the store | blank |
| `SHOPIFY_ADMIN_API_VERSION` | Admin API version | Same as the app's webhook `api_version` | `2026-07` |
| `RAZORPAY_WEBHOOK_SECRET` | Webhook secret | **You invent it** and type the same value into Razorpay (§6.2) | already in `config/local.env` |
| `RAZORPAY_MODE` | `test` or `live` | Start with `test` | `test` |
| `INGEST_PORT` | Service port | 8080 is taken on this machine | `8081` |
| `PUBLIC_BASE_URL` | Tunnel URL | From the tunnel (§6.1); changes on every quick-tunnel restart | update in `config/local.env` |
| `FIREBASE_SERVICE_ACCOUNT_FILE` | FCM credentials, for the worker (Phase 3) | Firebase → Project settings → Service accounts → Generate key. Keep it in `config/` (git-ignored); a relative path resolves from the repo root under `:worker:run` | `config/firebase-service-account.json` |
| `WORKER_PORT` | Worker `/health` port (Phase 3) | Any free port | `8084` |
| `STOREFRONT_BASE_URL` | Where push links point, e.g. `<this>/cart` (Phase 3) | The domain shoppers subscribe on. Blank = `https://SHOPIFY_SHOP_DOMAIN` | blank locally |
| `WORKER_JOBS_ENABLED` | `false` stops the worker's scheduled jobs (event dispatch, cascade tick, sweeper) | Leave `true` | `true` |

Everything else in the file (Firebase, WhatsApp, SMS) stays blank until Phases 2–4.

---

## 2. Install the prerequisites

### PostgreSQL 16 or newer

| OS | Install |
|---|---|
| Windows | EDB installer from postgresql.org → Download → Windows. Keep the default port 5432 and **remember the `postgres` password** you set. Tick "Command Line Tools" so `psql` is installed. |
| macOS | [Postgres.app](https://postgresapp.com), or `brew install postgresql@16 && brew services start postgresql@16` |
| Ubuntu/Debian | `sudo apt install postgresql` (then `sudo -u postgres psql` to reach the superuser) |

Check it: `psql --version` should print 16 or newer.

No extensions are needed. The schema deliberately avoids contrib modules, so a minimal install works.

### Java

**Gradle itself must run on JDK 25.** The Micronaut 5 Gradle plugin refuses an older JVM, so the toolchain auto-download does not help here: it only covers compiling, not running Gradle.

Install Eclipse Temurin 25 from adoptium.net, or unpack the portable zip and point `JAVA_HOME` at it for the build only. On this machine it is unpacked at `C:\Users\linda\.jdks\jdk-25.0.4.1+1`, and the system default stays on Java 21.

Check it: `java -version`, or `& "$env:JAVA_HOME\bin\java" -version` if you set `JAVA_HOME` per session.

### Windows with Avast: HTTPS scanning

Avast Web/Mail Shield re-signs HTTPS traffic with its own root certificate. That root is in the Windows certificate store but not in a freshly unpacked JDK, so Gradle fails with `PKIX path building failed` when downloading dependencies. Tell Java to use the Windows store:

```powershell
$env:JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

That is enough for Gradle. **It is not enough for sending push from this machine**: Avast also rewrites Google's compressed responses, so FCM calls fail even with the Windows trust store (seen 2026-09-29). Turn Web Shield off, or add exceptions for `fcm.googleapis.com` and `oauth2.googleapis.com`, before running the worker or `FcmSmokeTest` here.

### A tunnel (to receive real webhooks)

Shopify and Razorpay deliver webhooks **only to public HTTPS URLs**. Razorpay rejects `localhost` outright. Install either:
- **cloudflared** (no account needed for quick tunnels): developers.cloudflare.com → cloudflared → Install
- **ngrok** (a free account gives you one stable domain, which saves re-registering webhooks)

---

## 3. Create your config file

From the repository root:

```bash
cp config/local.env.example config/local.env        # Windows PowerShell: copy config\local.env.example config\local.env
```

Open `config/local.env` and fill in the values from §1. The format is `KEY=value`: no quotes, no spaces around `=`. Passwords may contain any characters, including `$`, quotes and spaces; they are read literally.

---

## 4. Create the database (once)

Run as the Postgres superuser, using **the same** `DB_USER`, `DB_PASSWORD`, `DB_NAME` and `TEST_DB_NAME` you put in `config/local.env`:

**macOS / Linux**
```bash
psql -h localhost -U postgres -d postgres \
     -v app_user=postgres -v app_password='YOUR_DB_PASSWORD' \
     -v app_db=wumika_admin -v test_db=wumika_admin_test \
     -f scripts/db-setup.sql
```

**Windows (PowerShell)** — one line:
```powershell
psql -h localhost -U postgres -d postgres -v app_user=postgres -v "app_password=YOUR_DB_PASSWORD" -v app_db=wumika_admin -v test_db=wumika_admin_test -f scripts/db-setup.sql
```

You should see `Done. Role and databases are ready.` The script is idempotent, so running it again is harmless and resets the app password to the one you pass.

What it creates: the two databases `wumika_admin` and `wumika_admin_test`, owned by `postgres`. Because `DB_USER` is the existing `postgres` superuser, no new role is created; the script only resets its password to the one you pass, so pass the current one. The app runs its migrations as `postgres` locally. Production should still use a dedicated non-superuser login.

JDBC URL: `jdbc:postgresql://localhost:5432/wumika_admin`

---

## 5. Run the service

```bash
./gradlew :ingest-api:run          # Windows: gradlew.bat :ingest-api:run
```

**Windows (PowerShell), this machine:** JDK 25 via `JAVA_HOME`, the Avast workaround, one line:
```powershell
$env:JAVA_HOME="$HOME\.jdks\jdk-25.0.4.1+1"; $env:JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"; .\gradlew.bat :ingest-api:run
```

The first run downloads Gradle and Micronaut. It takes a few minutes. Expected log lines:

```
Config OK — shop=e4bac4-ef.myshopify.com razorpay.mode=test customers=1 allowlisted customer(s)
Flyway ... Successfully applied 6 migrations to schema "public" (V1 … V6)
Startup completed in ...ms. Server Running: http://localhost:8081
```

**Port:** on this machine port 8080 is taken by a Windows service (`AgentService`), so `config/local.env` sets `INGEST_PORT=8081`. The health check and tunnel commands below already use 8081. **Never point the tunnel at 8080:** that would expose the other service to the internet.

If a value is still a placeholder, the run stops **before** starting and names it:
```
Edit config/local.env and set: RAZORPAY_WEBHOOK_SECRET
```

Health check, from another terminal:
```bash
curl http://localhost:8081/health
```

### Register the WhatsApp consent wording (once)

A WhatsApp opt-in is recorded **only** if the exact wording the shopper saw is registered. An unregistered copy version grants nothing and logs a warning, by design. For local testing:

```sql
-- psql -h localhost -U postgres -d wumika_admin
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface) VALUES
 ('wa_v1', 'whatsapp', 'Send me order updates and offers from YOUR_BRAND on WhatsApp',
  '{transactional,marketing}', 'cart');
```

Use your real brand name and the exact words your cart checkbox will show. Changing the words later means a new version (`wa_v2`); rows are immutable.

---

## 6. Receive real webhooks

### 6.1 Start a tunnel

```bash
cloudflared tunnel --url http://localhost:8081
# or: ngrok http 8081
```

Copy the `https://…` URL into `PUBLIC_BASE_URL` in `config/local.env`.

Quick cloudflared tunnels get a **new URL on every restart**, and both dashboards must then be updated. For anything longer than an afternoon, use an ngrok static domain or a named Cloudflare tunnel.

### 6.2 Razorpay (Test Mode)

Dashboard → switch on **Test Mode** → Account & Settings → **Webhooks** → Add New Webhook:

| Field | Value |
|---|---|
| Webhook URL | `{PUBLIC_BASE_URL}/webhooks/razorpay` |
| Secret | exactly your `RAZORPAY_WEBHOOK_SECRET` |
| Active events | `payment.failed`, `payment.captured`, `payment.authorized` |

The webhook **secret** is the value you typed here. It is not the API key secret. Using the key secret is the classic cause of every request returning 401.

### 6.3 Shopify

Subscribe the app's webhooks in `shopify-extension/shopify.app.toml` and deploy with `shopify app deploy`:

```toml
[webhooks]
api_version = "2026-07"   # pin to the current stable version

[[webhooks.subscriptions]]
topics = ["checkouts/create", "checkouts/update", "orders/create",
          "carts/create", "carts/update", "customers/create", "customers/update"]
uri = "https://YOUR-TUNNEL/webhooks/shopify"
```

App webhooks are signed with the app's **Client secret**, which is `SHOPIFY_API_SECRET`. The app also needs **protected customer data** access (Level 2: name, email, phone, address), or checkout and order webhooks arrive with those fields empty. Request it in the Dev Dashboard.

Do not mix in webhooks created under **Shopify Admin → Settings → Notifications → Webhooks**. Those are signed with a *different* key shown on that page, and they will fail verification here.

### 6.4 Run the Razorpay spike

Follow `docs/technical/phase-0-prerequisites.md` §3: make UPI and card payments fail on purpose in test mode, then:

```sql
SELECT * FROM payment_failure_match_report;
```

---

## 7. Tests

| Command | What it proves | Needs |
|---|---|---|
| `./gradlew :core-domain:test` | 40 unit tests: Razorpay and Shopify signatures, App Proxy (forged customer id, replay after 5 min), phone normalisation, lakh/crore formatting | Nothing |
| `./gradlew :ingest-api:test` | End to end: signed webhook → inbox → handler → Postgres, delivered out of order; the late-order compliance case | `config/local.env` + `wumika_admin_test` |
| `psql -h localhost -U postgres -d wumika_admin -f db/tests/invariants.sql` | 17 database rules: append-only history, four-eyes approval, dedupe, erasure | Migrated database. Runs in a transaction and **rolls back**, so it is safe on any database. |
| `scripts/send-test-webhook.sh razorpay` | A correctly signed webhook against your running service (also `shopify orders/create`, `bad-signature`) | Service running; bash + curl + openssl (Git Bash on Windows) |

Integration tests **truncate** tables before each test. They refuse to run against any database whose name does not end in `_test`.

---

## 8. Useful queries

```sql
-- Is the inbox keeping up? Anything stuck, and why?
SELECT source, topic, attempts, last_error, received_at
  FROM webhook_inbox WHERE processed_at IS NULL ORDER BY received_at;

-- Everything we know about one shopper
SELECT k.identity_id, k.kind, k.value, k.verified
  FROM identity_keys k
 WHERE k.identity_id = (SELECT identity_id FROM identity_keys WHERE kind = 'phone' AND value = '919876543210');

-- Consent in force right now for that identity
SELECT channel, purpose, state, source, occurred_at FROM consent_current WHERE identity_id = '…';

-- Razorpay failures and how they matched
SELECT * FROM payment_failure_match_report;
```

---

## 9. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Razorpay webhooks all 401 | `RAZORPAY_WEBHOOK_SECRET` must equal the secret typed on the webhook in the dashboard, not the API key secret. |
| Shopify webhooks all 401 | `SHOPIFY_API_SECRET` must be the app **Client secret**. `SHOPIFY_SHOP_DOMAIN` must be the `*.myshopify.com` domain. Admin-notification webhooks use a different key (§6.3). |
| `password authentication failed for user "postgres"` | Password in `config/local.env` differs from the setup script's. Re-run §4 with the right one; it resets the password. |
| `connection refused` on 5432 | Postgres is not running. Windows: Services → postgresql-x64-16 → Start. macOS: open Postgres.app. |
| Webhooks never arrive | The tunnel URL changed (quick tunnels do on restart). Update `PUBLIC_BASE_URL` and both dashboards. |
| Checkout/order rows have no phone or email | The app lacks protected customer data access (§6.3). |
| `WhatsApp opt-in ticked with copy version 'wa_v1' that is not registered` | Register the wording (§5). No consent is granted until you do. This is intentional. |
| `Unsupported class file major version` or `Dependency requires at least JVM runtime version 25` | Gradle is running on an old JDK. Point `JAVA_HOME` at JDK 25 (§2); the toolchain download does not cover Gradle itself. |
| `PKIX path building failed` while downloading dependencies | Avast (or another HTTPS scanner) is re-signing traffic. Set `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` (§2). |
| Port in use (`Address already in use: bind`) | Find the owner: `Get-NetTCPConnection -LocalPort 8081 -State Listen`. Pick a free port in `INGEST_PORT` and point the tunnel at the same port. |

---

## 10. What was verified before handover

Verified against **PostgreSQL 16**, with the exact SQL and Java in this repository:

- All six migrations applied as a non-superuser login; 17 invariant tests pass
- `resolve_identity`: 23 checks, including gift-recipient protection and **8 concurrent resolvers producing exactly one identity**
- All webhook extraction SQL against realistic payloads, including Razorpay's `notes: []` quirk and paise amounts: 24 checks
- The Java handlers, controllers and inbox over real JDBC, end to end, in three delivery orders (natural, fully reversed, late-after-unsubscribe), plus poison-message rollback and replay idempotency
- `scripts/db-setup.sql` (idempotent, passwords containing quotes) and `scripts/send-test-webhook.sh` (secrets containing `$`, quotes, spaces)

**Your first `./gradlew build` is the first compile against the real Micronaut 5 libraries.** Maven Central was not reachable from the build environment, so the Micronaut-facing layer (controller and DI annotations, `@ConfigurationProperties` records, `@Scheduled`, and `@Body byte[]` binding) was compiled against faithful stand-ins of those APIs, not the libraries themselves. It is deliberately thin: about ten well-known types. If anything there needs adjusting, it will be a one-line annotation or import fix, not logic.
