# Staging (P1-T06)

Staging runs the same images as dev, against a **managed Postgres** and with secrets from **AWS SSM Parameter Store**. Nothing here is provisioned yet: the steps marked *you* need an AWS account decision and spending.

## What is ready

| Piece | Where |
|---|---|
| Migrations as a job, never on service start | `ingest-api migrate` (`Migrate.java`); the `migrate` service in both compose files. Dev already deploys this way |
| Compose for a managed database | `deploy/staging/docker-compose.yml` (no `db` container; `IMAGE_TAG` picks the build) |
| Secrets into env vars, no `.env` in images | `deploy/staging/render-env.sh` renders `.env` from `/wumika/staging/*` in SSM |
| Every variable the services read | `deploy/.env.example` |

## Provisioning (you)

1. **Postgres 16 on RDS, `ap-south-1`** (ADR-004). Smallest Graviton instance is enough for staging; private subnet; automated backups on; a database `wumika_staging` and an app user.
2. **A small EC2 host** (or ECS) in the same VPC, with Docker and the compose plugin, an instance role allowed `ssm:GetParametersByPath` + `kms:Decrypt` on `/wumika/staging/` only, and the security group allowed into RDS on 5432.
3. **SSM parameters** under `/wumika/staging/`, one per line of `deploy/.env.example` (SecureString for secrets): `DB_HOST` (the RDS endpoint), `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `SHOPIFY_*`, `RAZORPAY_*` (**test mode**), `CUSTOMER_ALLOWLIST_EMAILS`, `PIXEL_WRITE_KEY`, `METRICS_TOKEN`, `COURIER_WEBHOOK_TOKEN`, `PUBLIC_BASE_URL`, `BUSINESS_TZ`. Fresh values, not dev's.
4. **Public HTTPS URL**, e.g. `engage-staging.wumika.com`: DNS to the host, TLS by nginx + certbot (as on dev) or an ALB.
5. **Webhooks** pointing at staging:
   - Shopify: a separate `wumikaEngage-staging` app in the Dev Dashboard (its own client id and secret), installed on the dev store only; config `shopify-extension/shopify.app.staging.toml` copied from the dev one with the staging URL.
   - Razorpay: **Test Mode** webhook `https://<staging>/webhooks/razorpay` (events `payment.failed`, `payment.captured`, `payment.authorized`). The Razorpay account is live: switch the dashboard to Test Mode before adding it.
   - Shiprocket: only a test account, never the live store's.

## Deploy

```sh
cd /opt/wumika-staging
./render-env.sh                                  # .env from SSM, mode 600
IMAGE_TAG=<git sha> docker compose pull         # or docker load, as dev's CI does
IMAGE_TAG=<git sha> docker compose up -d        # migrate runs first; services wait for it
docker compose ps                                # migrate: exited (0); the rest: up
curl -fsS https://<staging>/health
```

A failed migration leaves `migrate` exited non-zero and no service started on the new images: fix forward with the next migration.

## Done when

On the dev store, a real test-mode order, and a failed Razorpay **test** payment for the same shopper, appear in staging:

```sql
SELECT id, financial_status FROM orders ORDER BY created_at DESC LIMIT 1;
SELECT status, order_id FROM payment_attempts ORDER BY created_at DESC LIMIT 1;
SELECT * FROM payment_failure_match_report;      -- shows the match
```
