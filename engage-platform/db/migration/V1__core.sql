-- V1: core data spine — identity graph, events, consent ledger, sends, money.
--
-- Postgres 16. gen_random_uuid() is built in (13+), so no pgcrypto needed.
--
-- Three rules this schema encodes:
--   * phone is the primary identity key in India, stored as 91XXXXXXXXXX
--   * consents is APPEND-ONLY; current state is a view over history
--   * sends records BLOCKED and DEFERRED attempts too: that table is the
--     audit trail and the analytics denominator (CLAUDE.md invariant 3)

/* ============================== enums ============================== */

CREATE TYPE key_kind AS ENUM (
  'phone', 'email', 'anon', 'fcm_token', 'shopify_customer',
  'cart_token', 'checkout_token', 'wa_id'
);
CREATE TYPE channel       AS ENUM ('whatsapp', 'push', 'email', 'sms', 'rcs');
CREATE TYPE purpose       AS ENUM ('transactional', 'marketing');
CREATE TYPE consent_state AS ENUM ('granted', 'withdrawn');
CREATE TYPE msg_category  AS ENUM ('marketing', 'utility', 'authentication', 'service');
CREATE TYPE send_status   AS ENUM (
  'blocked', 'deferred', 'queued', 'sent', 'delivered', 'read', 'clicked', 'failed'
);

/* ============================= identity ============================= */

CREATE TABLE identities (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  merged_into UUID REFERENCES identities(id),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE identity_keys (
  id          BIGSERIAL PRIMARY KEY,
  identity_id UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  kind        key_kind NOT NULL,
  value       TEXT NOT NULL,
  -- verified = came from a signed/confirmed source (App Proxy customer id,
  -- WhatsApp inbound, delivered utility message). Only verified keys may merge.
  verified    BOOLEAN NOT NULL DEFAULT false,
  first_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (kind, value)
);
CREATE INDEX identity_keys_identity_idx ON identity_keys (identity_id, kind);

CREATE TABLE profiles (
  identity_id     UUID PRIMARY KEY REFERENCES identities(id) ON DELETE CASCADE,
  attrs           JSONB NOT NULL DEFAULT '{}',   -- name, city, state, size_top, locale
  computed        JSONB NOT NULL DEFAULT '{}',   -- aov_band, net_margin_band, last_order_at
  wa_window_until TIMESTAMPTZ,                   -- WhatsApp 24h service window
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX profiles_computed_idx ON profiles USING GIN (computed jsonb_path_ops);

/* ============================== events ============================== */

CREATE TABLE events (
  id          BIGSERIAL PRIMARY KEY,
  identity_id UUID REFERENCES identities(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  props       JSONB NOT NULL DEFAULT '{}',
  source      TEXT NOT NULL,        -- shopify | razorpay | whatsapp | courier | storefront | pixel | system
  dedupe_key  TEXT UNIQUE,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX events_identity_idx ON events (identity_id, name, occurred_at DESC);
CREATE INDEX events_name_idx     ON events (name, occurred_at DESC);

-- Webhook INBOX. Shopify, Meta and Razorpay all deliver at-least-once and all
-- time out at ~5s. The controller does exactly one thing inside that budget:
-- verify the signature and INSERT here (the primary key dedupes retries).
-- The raw payload is stored in the same statement, so a crash after the 200
-- loses nothing: the inbox processor picks the row up on the next tick.
CREATE TABLE webhook_inbox (
  source       TEXT NOT NULL,        -- shopify | razorpay | meta | courier
  delivery_id  TEXT NOT NULL,        -- X-Shopify-Webhook-Id | x-razorpay-event-id | ...
  topic        TEXT NOT NULL,        -- orders/create | payment.failed | ...
  payload      JSONB NOT NULL,
  received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  attempts     SMALLINT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ,
  last_error   TEXT,
  PRIMARY KEY (source, delivery_id)
);
CREATE INDEX webhook_inbox_pending_idx ON webhook_inbox (next_attempt_at)
  WHERE processed_at IS NULL;
CREATE INDEX webhook_inbox_age_idx ON webhook_inbox (received_at) WHERE processed_at IS NOT NULL;

/* ========================== consent ledger ========================== */

CREATE TABLE consents (
  id          BIGSERIAL PRIMARY KEY,
  identity_id UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  channel     channel NOT NULL,
  purpose     purpose NOT NULL,
  state       consent_state NOT NULL,
  source      TEXT NOT NULL,        -- cart_attr | thank_you | soft_ask:<surface> | wa_thread | wa_stop_reply | shopify_checkout
  copy_version TEXT,                -- consent_copy_versions.version (V4)
  evidence    JSONB NOT NULL DEFAULT '{}',   -- verbatim copy, page, ip, ua, order id, pre_ticked=false
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX consents_lookup_idx ON consents (identity_id, channel, purpose, occurred_at DESC);

CREATE VIEW consent_current AS
SELECT DISTINCT ON (identity_id, channel, purpose)
       identity_id, channel, purpose, state, source, copy_version, occurred_at
  FROM consents
 ORDER BY identity_id, channel, purpose, occurred_at DESC, id DESC;

-- Hard blocks that outrank consent: bounces, provider errors, user blocks.
CREATE TABLE suppressions (
  identity_id UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  channel     channel NOT NULL,
  reason      TEXT NOT NULL,
  until       TIMESTAMPTZ,          -- NULL = permanent
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (identity_id, channel, reason)
);

/* ============================ templates ============================= */

-- Registry mirror of templates authored in code. The WhatsApp-specific Meta
-- state (approved category, quality) lives in wa_templates (V4).
CREATE TABLE templates (
  key        TEXT PRIMARY KEY,
  channel    channel NOT NULL,
  category   msg_category NOT NULL,
  status     TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'paused', 'retired')),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

/* =============================== sends ============================== */

CREATE TABLE sends (
  id              BIGSERIAL PRIMARY KEY,
  identity_id     UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  channel         channel NOT NULL,
  category        msg_category NOT NULL,
  template_key    TEXT NOT NULL,
  intent_key      TEXT,                 -- cart_recovery, payment_failed, campaign:<id>
  step_index      INT,
  idempotency_key TEXT NOT NULL UNIQUE,
  status          send_status NOT NULL,
  decision        JSONB NOT NULL DEFAULT '{}',  -- why policy allowed/blocked; no secrets, no PII
  provider_id     TEXT,
  delivered_count INT,                  -- FCM multicast: devices that accepted
  cost_paise      INTEGER,
  failed_reason   TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at         TIMESTAMPTZ,
  delivered_at    TIMESTAMPTZ,
  read_at         TIMESTAMPTZ,
  clicked_at      TIMESTAMPTZ
);
-- The frequency-cap query runs on every send decision. Index it exactly.
CREATE INDEX sends_cap_idx ON sends (identity_id, channel, category, created_at DESC)
  WHERE status NOT IN ('blocked', 'deferred');
CREATE INDEX sends_provider_idx ON sends (provider_id) WHERE provider_id IS NOT NULL;
CREATE INDEX sends_intent_idx   ON sends (intent_key, created_at DESC);

/* ============================ measurement =========================== */

CREATE TABLE holdouts (
  identity_id UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  experiment  TEXT NOT NULL,
  bucket      TEXT NOT NULL CHECK (bucket IN ('control', 'treatment')),
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (identity_id, experiment)
);

CREATE TABLE conversions (
  id          BIGSERIAL PRIMARY KEY,
  identity_id UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  order_id    TEXT NOT NULL UNIQUE,
  value_paise BIGINT NOT NULL,
  last_send_id BIGINT REFERENCES sends(id),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX conversions_identity_idx ON conversions (identity_id, occurred_at DESC);

-- Cost guard reads this. Booked from WhatsApp status webhooks (billed category),
-- immediately for other paid channels.
CREATE TABLE spend_ledger (
  day      DATE NOT NULL,
  channel  channel NOT NULL,
  category msg_category NOT NULL,
  messages BIGINT NOT NULL DEFAULT 0,
  paise    BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (day, channel, category)
);
