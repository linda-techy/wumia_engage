-- V50: tools for the WhatsApp inbox (owner's audit 2026-10-27).
--   * saved answers staff insert with one tap (seeded from wumika.com's
--     shipping, returns and FAQ pages, edited in the inbox);
--   * new-message alerts to staff phones and computers (standard Web Push,
--     no message content in the push);
--   * the greeting / away auto-reply and its menu.

CREATE TABLE inbox_quick_replies (
  id          BIGSERIAL PRIMARY KEY,
  title       TEXT NOT NULL CHECK (length(title) BETWEEN 1 AND 40),
  body        TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 1024),
  sort        INT  NOT NULL DEFAULT 100,
  updated_by  UUID REFERENCES operators(id) ON DELETE SET NULL,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO inbox_quick_replies (title, body, sort) VALUES
 ('Delivery time',
  'Orders are packed and dispatched within 1–2 business days (not on Sundays or public holidays). Delivery takes 2–4 business days within Kerala and 4–7 business days to the rest of India.', 10),
 ('Shipping charges',
  'Standard shipping is free on orders of ₹600 and above. Orders below ₹600 have a flat ₹60 shipping charge.', 20),
 ('Payment',
  'You pay securely online at checkout through Razorpay. The payment options available to you are shown on the checkout page.', 30),
 ('Damaged or wrong item',
  'So sorry about that, {name}! Please send your order number and a photo of the item here. We''ll send a free replacement or a full refund, your choice, and you usually don''t need to send anything back.', 40),
 ('Size exchange',
  'If a ring or bracelet doesn''t fit, message us within 7 days of delivery. If your size is in stock, post the unworn piece back to us (postage at your cost) and we''ll ship the replacement free. Many of our rings and bracelets are adjustable.', 50),
 ('Ring size guide',
  'Here''s how to find your size at home: https://www.wumika.com/pages/size-guide Many of our rings are adjustable, and fixed sizes are listed on the product page.', 60),
 ('Cancel an order',
  'You can cancel free of charge any time before your order is dispatched. Share your order number and we''ll stop it and refund you in full.', 70),
 ('Refund timing',
  'Approved refunds are processed within 5 business days, straight back to the way you paid. Your bank may take a further 3–7 business days to show it.', 80);

-- One row per device an operator turned alerts on for. No payload is ever
-- sent, so the subscription's encryption keys are not needed or stored.
CREATE TABLE staff_push_subscriptions (
  id           BIGSERIAL PRIMARY KEY,
  operator_id  UUID NOT NULL REFERENCES operators(id) ON DELETE CASCADE,
  endpoint     TEXT NOT NULL UNIQUE,
  user_agent   TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_sent_at TIMESTAMPTZ,
  failures     INT NOT NULL DEFAULT 0
);
CREATE INDEX staff_push_subscriptions_operator_idx ON staff_push_subscriptions (operator_id);

-- The VAPID key pair that signs staff alerts, created on first use. It only
-- proves the alert comes from Engage; it cannot read or send anything else.
CREATE TABLE staff_push_keys (
  id           INT PRIMARY KEY CHECK (id = 1),
  public_key   TEXT NOT NULL,          -- uncompressed P-256 point, base64url
  private_key  TEXT NOT NULL,          -- PKCS#8, base64
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- How far the staff-alert job has read wa_messages. Started at the current
-- end so messages already in the inbox raise no alert.
CREATE TABLE inbox_job_state (
  job              TEXT PRIMARY KEY,
  last_message_id  BIGINT NOT NULL,
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO inbox_job_state (job, last_message_id)
SELECT 'staff_alert', COALESCE((SELECT max(id) FROM wa_messages), 0);

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('inbox.business_hours', 'GLOBAL', 'TIME_RANGE', 'WhatsApp support hours (IST)',
  'Inside these hours a new conversation gets the greeting and staff get alerts; outside them the customer gets the away message and alerts wait until opening.', 'GUARDED', 140, NULL),
 ('inbox.auto_reply', 'GLOBAL', 'BOOL', 'WhatsApp greeting and away message',
  'Answers a new conversation at once with a menu: Track my order, Shop bestsellers, Talk to us. Free: it replies inside the window the customer opened.', 'GUARDED', 141, NULL),
 ('inbox.shop_url', 'GLOBAL', 'STRING', 'WhatsApp menu: bestsellers link',
  'Where "Shop bestsellers" sends the customer.', 'SAFE', 142,
  '{"type":"string","pattern":"^https://","maxLength":300}')
ON CONFLICT (key) DO NOTHING;

UPDATE config_keys SET default_value = v.val FROM (VALUES
  ('inbox.business_hours', '{"from":"10:00","to":"19:00"}'::jsonb),
  ('inbox.auto_reply',     'true'::jsonb),
  ('inbox.shop_url',       '"https://www.wumika.com/collections/all?sort_by=best-selling"'::jsonb)
) AS v(key, val) WHERE config_keys.key = v.key AND config_keys.default_value IS NULL;
