# P7-T06 — Security review and abuse hardening

Written for: whoever runs the pen test, rotates credentials and signs off go-live.
Status 2026-10-15: the code part is done and tested; the pen test, credential rotation and backup rehearsal need production and people.

## Fixed in this review

| Finding | Risk | Fix | Proof |
|---|---|---|---|
| An unexpected error (say a database error) answered with Micronaut's default 500, which **puts the exception message in the body**. A Postgres message can carry customer data ("Key (email)=(…) already exists"). | Medium: data leak to anyone who can trigger an error, including storefront visitors | `UnexpectedErrorHandler` in admin-api and ingest-api: the cause is logged under an 8-hex reference; the body carries only the reference | `UnexpectedErrorTest` in both modules (an email address in the exception does not reach the response; bad JSON is still a 400) |
| Storefront rate limits were per pod, in memory. Production runs 3–10 ingest pods, so N× the limit got through. | Low–medium: device, funnel and waitlist tables floodable | `RateLimiter` counts in Postgres (`rate_windows`, unlogged, V37), purged hourly. If Postgres is unreachable the check allows, since the request needs the database anyway | `RateLimiterTest`: two instances share one count |
| `stock_waitlist` had no per-shopper bound; the anon id is chosen by the browser, so the rate limit alone does not cap it | Low: table growth | At most 50 waiting alerts per identity; the 51st answers 429; asking again for one already waiting is fine | `StorefrontSubscriberTest.fifty_waiting_size_alerts_is_the_most_one_shopper_can_have` |
| A Shopify checkout payload without a token hit a NOT NULL error and was retried | Low: noise and retries | Skipped with a warning | `EndpointFuzzTest` (signed webhooks) |

The admin login limiter stays in memory. admin-api runs 2 pods, and each account locks in the database after five failures (`operators.failed_logins`, `locked_until`), which holds across pods.

## Fuzzing

`EndpointFuzzTest` (ingest-api, seed `20261015`, deterministic) sends malformed input to every public endpoint:

- **Bodies:** fields of the wrong type, dropped or added; 70 000-character strings; NUL and emoji; SQL and HTML; JSON arrays nested 5 000 deep; truncated JSON; random bytes.
- **Content types:** JSON, text, form, none.
- **Storefront App Proxy endpoints:** correctly signed, so the bodies reach validation. The test asserts it saw both 400s and 2xx, and never a 401.
- **Pixel:** with and without the write key.
- **Thank-you opt-in:** forged session tokens.
- **Link endpoints:** forged unsubscribe tokens, tracking refs and pay refs.
- **Every provider webhook:** unsigned or wrongly signed, all refused.
- **Shopify webhooks:** correctly signed with odd JSON, then the inbox drained.

Result: no 5xx anywhere. No response contains a stack trace, class name, `java.`, `org.postgresql` or `SQLState`. A malformed signed payload fails only its own inbox row (it is retried, then dead-lettered), never the inbox.

## External pen test scope

In scope, against staging with production-like config:

1. **Admin API authentication.**
   - Login, MFA, recovery codes, lockout, the per-IP limit.
   - JWT signing and validation: `alg` confusion, expired and not-yet-valid tokens, a token from another environment.
   - Refresh-token rotation and reuse detection; logout and session revocation.
   - The one-time set-password link.
2. **Authorisation.** Every `/api/**` route against every role. `ControllerRolesTest` asserts each has `@RequiresRole` or a justified `@PublicEndpoint`. In particular:
   - OWNER-only erase and privacy reports.
   - ANALYST reveal, with the daily PII limit.
   - CONFIG_ADMIN bill upload.
   - Four-eyes config approval.
3. **App Proxy identity binding.**
   - The signature over the query (`logged_in_customer_id` cannot be chosen by the browser).
   - Timestamp freshness and replay.
   - A browser id that belonged to someone else.
4. **Session-token verification** for the Thank-you opt-in (Shopify checkout UI extension JWT): audience, issuer, `dest` shop, expiry.
5. **Signed links.** Unsubscribe (`/u/{token}`), tracking (`/apps/push/t/{ref}`) and pay links (`/apps/push/pay/{ref}`): forgery, truncation, reuse on another customer, open redirect through the pay link.
6. **Webhooks.** Signature checks for Shopify, Razorpay, Meta (`X-Hub-Signature-256`), MSG91 and courier (URL token), SES/SNS (certificate host and signature); replay through the inbox dedupe.
7. **Privacy.** That erasure leaves nothing (T18 asserts this in the database), that the data-request report is OWNER-only and logged, and that `customers/redact` cannot be triggered unsigned.

Out of scope: Shopify, Meta, Razorpay, MSG91 and AWS themselves; denial of service by volume (the load test, P7-T04, covers capacity).

**Done when:** every high or critical finding is fixed and re-tested by the tester. Record findings and retests at the end of this file.

## Rotate before launch

Rotate after the pen test and before the first live message, so nothing issued during development stays valid. Set the new values only in the production secret manager, never in a file in the repository.

| Secret | Where to rotate | Notes |
|---|---|---|
| `WA_ACCESS_TOKEN` (Meta system-user token) | Meta Business Settings → System users → generate new token, then revoke the old | Non-expiring, so valid forever once leaked; rotate quarterly after launch too |
| `WA_APP_SECRET` | Meta app → Settings → Basic → App secret → Reset | Signs inbound webhooks; update before the next webhook arrives |
| `WA_WEBHOOK_VERIFY_TOKEN` | Ours: pick a new random value, then re-verify the webhook in the Meta app | |
| `SHOPIFY_API_SECRET` | Shopify Dev Dashboard → app → Client credentials → rotate | **Also signs tracking, pay and unsubscribe links and push beacons** (`TrackRef`, `UnsubscribeToken`, `BeaconSignature`). Every link in an already-sent message stops working, which is why it is rotated before launch and not casually after |
| `RAZORPAY_WEBHOOK_SECRET` | Razorpay Dashboard → Webhooks → edit → new secret | |
| Firebase service account (`FIREBASE_SERVICE_ACCOUNT_B64`) | Google Cloud → IAM → service account → new key; delete the old key | |
| `ADMIN_JWT_KEY_FILE` (admin JWT key pair) | Generate a new pair | Signs out every operator; publish the new public key (`/.well-known/jwks.json`) before switching |
| `ADMIN_MFA_KEY` (wraps `operators.mfa_secret_enc`) | Needs re-encryption of existing MFA secrets, or every operator re-enrols | Plan it; do not just swap the value |
| `MSG91_AUTH_KEY`, `MSG91_DLR_TOKEN` | MSG91 panel; the DLR token is ours and goes in the delivery-report URL | |
| `COURIER_WEBHOOK_TOKEN`, `PIXEL_WRITE_KEY`, `METRICS_TOKEN` | Ours: new random values; update the courier webhook URL, the pixel extension setting and the metrics scraper | The pixel key is public by design (it ships in the storefront); rotating only shakes off old copies |
| `DB_PASSWORD` | Managed Postgres → new password for the app user | |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | Prefer an IAM role on the pods; otherwise a new key, then deactivate the old | SES is off, so this may not be set at all |

## Backup restore rehearsal

Once production exists (P7-T07), before go-live:

1. Restore the latest production snapshot (PITR) into a scratch instance in the same region. Record the start time.
2. Point a throwaway ingest-api at it with `FLYWAY_ON_STARTUP=false` and every provider credential blank, so nothing can send.
3. Check: Flyway history matches the release; row counts for `identities`, `consents`, `sends`, `orders` and `audit_log` match production at the snapshot time; `db/tests/invariants.sql` passes (it rolls back).
4. Record the end time and the total in this file. The target is under 1 hour for a database of the launch size.
5. Delete the scratch instance and its snapshot copy.

## Findings and retests

| Date | Finding | Severity | Fixed in | Retested |
|---|---|---|---|---|
| | | | | |
