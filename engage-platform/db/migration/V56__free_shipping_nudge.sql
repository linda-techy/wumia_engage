-- V56: the cart-recovery push tells a small bag how far it is from free
-- shipping (owner, 2026-10-30). The storefront's bar uses the same line
-- (theme: window.FREE_SHIPPING_THRESHOLD = 60000); change both together.

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('journey.cart_recovery.free_shipping_paise', 'JOURNEY', 'INT', 'Cart push: free shipping from (paise)',
  'Below this, the cart push says "Add ₹X more for FREE shipping." Keep it equal to the website''s free-shipping bar. 0 = no shipping line.', 'SAFE', 105,
  '{"type":"integer","minimum":0,"maximum":10000000}')
ON CONFLICT (key) DO NOTHING;

UPDATE config_keys SET default_value = '60000'::jsonb
 WHERE key = 'journey.cart_recovery.free_shipping_paise' AND default_value IS NULL;
