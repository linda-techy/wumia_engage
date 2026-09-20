-- V3: Shopify storefront + FCM web push.
-- See docs/technical/phase-1-core-platform.md §1 and phase-2-shopify-web-push.md.

/* ============================== push ============================== */

-- Every device that can receive push. Web tokens rot; these columns let us
-- tell a live subscriber from a dead one without sending to it.
CREATE TABLE devices (
  id                 BIGSERIAL PRIMARY KEY,
  identity_id        UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  fcm_token          TEXT NOT NULL UNIQUE,
  platform           TEXT NOT NULL CHECK (platform IN ('WEB','ANDROID','IOS_APP','IOS_WEB')),
  browser            TEXT,                -- chrome | edge | firefox | samsung | safari
  origin             TEXT NOT NULL,       -- https://brand.in
  sw_version         TEXT,
  permission_source  TEXT NOT NULL,       -- add_to_cart | notify_me | thank_you | settings
  consent_copy_ver   TEXT NOT NULL,
  active             BOOLEAN NOT NULL DEFAULT true,
  deactivated_reason TEXT,               -- rotated | unregistered | sender_mismatch | invalid_token | dormant | user_off
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_refreshed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_sent_at       TIMESTAMPTZ,
  last_clicked_at    TIMESTAMPTZ,
  CHECK (active OR deactivated_reason IS NOT NULL)
);
CREATE INDEX devices_identity_idx ON devices (identity_id) WHERE active;
-- Staleness sweep (30 days without refresh) scans by this.
CREATE INDEX devices_refresh_idx  ON devices (last_refreshed_at) WHERE active;

-- Soft-ask funnel. Without it you cannot tell a traffic problem from a prompt problem.
CREATE TABLE push_prompt_events (
  id          BIGSERIAL PRIMARY KEY,
  anon_id     TEXT NOT NULL,
  identity_id UUID REFERENCES identities(id) ON DELETE SET NULL,
  surface     TEXT NOT NULL,              -- add_to_cart | notify_me | thank_you | cart
  step        TEXT NOT NULL CHECK (step IN (
                'soft_shown','soft_accepted','soft_dismissed',
                'native_granted','native_denied','native_dismissed',
                'token_minted','token_failed','ios_redirected_to_whatsapp')),
  browser     TEXT,
  platform    TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX push_prompt_funnel_idx ON push_prompt_events (surface, step, created_at);

/* ======================= carts, checkouts, orders ======================= */

-- cart_token is normalised: everything from the first '?' is stripped,
-- because /cart.js can return a "?key=" suffix the webhook does not carry.
CREATE TABLE carts (
  cart_token     TEXT PRIMARY KEY CHECK (position('?' IN cart_token) = 0),
  identity_id    UUID REFERENCES identities(id) ON DELETE SET NULL,
  item_count     INT    NOT NULL DEFAULT 0,
  total_paise    BIGINT,
  currency       TEXT   NOT NULL DEFAULT 'INR',
  lines          JSONB  NOT NULL DEFAULT '[]',  -- [{variant_id,title,size,qty,price_paise,image}]
  checkout_token TEXT,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  converted_at   TIMESTAMPTZ,
  order_id       TEXT
);
CREATE INDEX carts_identity_idx ON carts (identity_id) WHERE converted_at IS NULL;

-- From checkouts/create + checkouts/update. This is where the phone arrives
-- server-side (HMAC-verified), and it is the join target for Razorpay
-- payment.failed (V5) and for web-pixel stall events.
CREATE TABLE checkouts (
  token          TEXT PRIMARY KEY,           -- Shopify checkout token
  cart_token     TEXT,
  identity_id    UUID REFERENCES identities(id) ON DELETE SET NULL,
  phone          TEXT,                       -- normalised 91XXXXXXXXXX
  -- contact = the buyer's own number; shipping = the address phone, which may
  -- be a gift recipient's (see V6 trust levels).
  phone_source   TEXT CHECK (phone_source IN ('contact', 'shipping')),
  email          TEXT CHECK (email = lower(email)),
  total_paise    BIGINT,
  recovery_url   TEXT,                       -- Shopify abandoned_checkout_url
  note_attributes JSONB NOT NULL DEFAULT '{}',
  last_step      TEXT,                       -- from web pixel: contact | shipping | payment
  last_step_at   TIMESTAMPTZ,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at   TIMESTAMPTZ,
  order_id       TEXT
);
-- Razorpay join without a checkout reference: same phone, recent. Not partial
-- on completed_at: a failure followed by a successful retry must still link.
CREATE INDEX checkouts_phone_idx ON checkouts (phone, updated_at DESC);
CREATE INDEX checkouts_cart_idx ON checkouts (cart_token);

CREATE TABLE orders (
  id              TEXT PRIMARY KEY,          -- Shopify order id
  order_number    TEXT NOT NULL,             -- "#1042"
  identity_id     UUID REFERENCES identities(id) ON DELETE SET NULL,
  cart_token      TEXT,
  checkout_token  TEXT,
  phone           TEXT,
  phone_source    TEXT CHECK (phone_source IN ('contact', 'shipping')),
  email           TEXT CHECK (email = lower(email)),
  total_paise     BIGINT NOT NULL,
  financial_status TEXT,
  gateway_names   TEXT[] NOT NULL DEFAULT '{}',
  note_attributes JSONB NOT NULL DEFAULT '{}',  -- carries _engage_wa_optin from the cart
  created_at      TIMESTAMPTZ NOT NULL,
  received_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX orders_identity_idx ON orders (identity_id, created_at DESC);

-- Thank-you-page opt-ins can arrive before orders/create. Held here, applied
-- when the order webhook lands (phase-2 §6.2).
CREATE TABLE pending_optins (
  order_id     TEXT PRIMARY KEY,
  shop         TEXT NOT NULL,
  channel      channel NOT NULL DEFAULT 'whatsapp',
  copy_version TEXT NOT NULL,
  received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  applied_at   TIMESTAMPTZ
);

/* ============================ inventory ============================ */

-- Size-variant waitlist. Product-level waitlists notify people whose size is
-- still out of stock, which teaches them to ignore you.
CREATE TABLE stock_waitlist (
  identity_id    UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  variant_id     TEXT NOT NULL,
  product_handle TEXT NOT NULL,
  size_label     TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  notified_at    TIMESTAMPTZ,
  PRIMARY KEY (identity_id, variant_id)
);
CREATE INDEX stock_waitlist_pending_idx ON stock_waitlist (variant_id, created_at) WHERE notified_at IS NULL;

-- Last known total availability, so restocks fire only on 0 -> positive.
CREATE TABLE inventory_state (
  inventory_item_id TEXT PRIMARY KEY,
  variant_id        TEXT NOT NULL,
  available         INT  NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
