-- V62: Razorpay Magic Checkout as a second checkout source (ADR D3, phase-5 §1
-- CheckoutSignalSource). Since 2026-11-07 the storefront's checkout button opens
-- Magic, so Shopify sends no checkouts/* webhooks for most shoppers and
-- checkout_abandon had nothing to work on.
--
-- checkouts.source  which system the row came from. A Magic row's token is the
--                   Magic session token; its recovery_url is a cart permalink.
-- orders.gateway_order_id  razorpay_order_id from a Magic order's note
--                   attributes: joins the order to its payment attempts exactly.

ALTER TABLE checkouts
  ADD COLUMN source TEXT NOT NULL DEFAULT 'shopify' CHECK (source IN ('shopify', 'razorpay_magic'));

ALTER TABLE orders ADD COLUMN gateway_order_id TEXT;

-- A Magic order carries no checkout token, so its checkout is closed by phone.
CREATE INDEX checkouts_open_phone_idx ON checkouts (phone, updated_at DESC)
  WHERE completed_at IS NULL AND order_id IS NULL;

CREATE INDEX payment_attempts_gateway_order_idx ON payment_attempts (gateway_order_id)
  WHERE gateway_order_id IS NOT NULL;
CREATE INDEX orders_gateway_order_idx ON orders (gateway_order_id)
  WHERE gateway_order_id IS NOT NULL;

-- A Magic retry under the same Razorpay order inherits the checkout an earlier
-- attempt was matched to.
ALTER TABLE payment_attempts DROP CONSTRAINT payment_attempts_match_method_check;
ALTER TABLE payment_attempts ADD CONSTRAINT payment_attempts_match_method_check
  CHECK (match_method IN ('notes_ref', 'phone_amount_window', 'gateway_order', 'none'));
