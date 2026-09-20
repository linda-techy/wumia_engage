-- V5: payment attempts from Razorpay webhooks.
--
-- payment_failed is a UTILITY WhatsApp template only when it references a real,
-- gateway-confirmed payment attempt. This table is that evidence. A checkout
-- that stalls with no row here is an abandoned checkout (MARKETING) instead.
-- See docs/technical/phase-5-journeys.md §2.
--
-- Razorpay amounts arrive in the smallest currency unit, i.e. paise for INR,
-- which is exactly our storage unit. Never divide by 100 on the way in.

CREATE TABLE payment_attempts (
  id                 BIGSERIAL PRIMARY KEY,
  gateway            TEXT NOT NULL DEFAULT 'razorpay',
  gateway_payment_id TEXT NOT NULL,               -- pay_XXXXXXXX
  gateway_order_id   TEXT,                        -- order_XXXXXXXX
  status             TEXT NOT NULL CHECK (status IN ('failed','authorized','captured')),
  amount_paise       BIGINT NOT NULL CHECK (amount_paise >= 0),
  currency           TEXT NOT NULL,
  method             TEXT,                        -- upi | card | netbanking | wallet | emi
  contact_raw        TEXT,                        -- as Razorpay sent it
  phone              TEXT,                        -- normalised 91XXXXXXXXXX, NULL if not a mobile
  email              TEXT CHECK (email = lower(email)),
  notes              JSONB NOT NULL DEFAULT '{}', -- the spike checks whether Shopify refs appear here
  error_code         TEXT,
  error_description  TEXT,
  error_source       TEXT,                        -- customer | bank | gateway | business | internal
  error_step         TEXT,
  error_reason       TEXT,                        -- payment_timed_out, payment_cancelled, ...
  identity_id        UUID REFERENCES identities(id) ON DELETE SET NULL,
  checkout_token     TEXT REFERENCES checkouts(token) ON DELETE SET NULL,
  match_method       TEXT CHECK (match_method IN ('notes_ref','phone_amount_window','none')),
  gateway_created_at TIMESTAMPTZ NOT NULL,
  received_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (gateway, gateway_payment_id, status)
);
CREATE INDEX payment_attempts_phone_idx    ON payment_attempts (phone, gateway_created_at DESC);
CREATE INDEX payment_attempts_checkout_idx ON payment_attempts (checkout_token);

-- Phase 0 spike report: how often can a failure be joined to a checkout, and
-- by which method? If the 'none' share is high, payment_failed cannot rely on
-- this signal and the build falls back to checkout_abandon (marketing).
CREATE VIEW payment_failure_match_report AS
SELECT date_trunc('day', received_at AT TIME ZONE 'Asia/Kolkata') AS ist_day,
       COALESCE(match_method, 'unprocessed')                      AS match_method,
       count(*)                                                    AS failures,
       count(*) FILTER (WHERE phone IS NULL)                       AS without_mobile,
       round(avg(extract(epoch FROM received_at - gateway_created_at))::numeric, 1)
                                                                   AS avg_webhook_lag_s
  FROM payment_attempts
 WHERE status = 'failed'
 GROUP BY 1, 2
 ORDER BY 1 DESC, 2;
