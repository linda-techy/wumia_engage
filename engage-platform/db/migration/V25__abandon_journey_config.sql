-- V25: abandoned checkout and cart recovery on WhatsApp (P5-T03/T04, the
-- owner's choice of 2026-10-10: do what most Indian D2C brands do). A
-- WhatsApp reminder first, push as the free fallback and second nudge, and a
-- last WhatsApp with a discount code only if an operator has set one. No SMS
-- (CLAUDE.md §3.2: no promotional SMS).
--
-- Takes the cart and checkout keys from the planned P5-T01 journey migration;
-- that migration keeps the rest.

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('journey.checkout_abandon.stall_minutes', 'JOURNEY', 'INT', 'Checkout reminder after (min)',
  'Minutes after the last checkout activity before the first reminder. Any later activity restarts the wait.', 'GUARDED', 101,
  '{"type":"integer","minimum":10,"maximum":240}'),
 ('journey.checkout_abandon.wa_min_cart_paise', 'JOURNEY', 'INT', 'Checkout reminder: WhatsApp min cart (paise)',
  'Below this, the checkout reminders go by push only. 0 = every cart. A marketing WhatsApp costs about ₹0.86 when delivered.', 'CRITICAL', 102,
  '{"type":"integer","minimum":0,"maximum":10000000}'),
 ('journey.cart_recovery.start_delay_minutes', 'JOURNEY', 'INT', 'Cart reminder after (min)',
  'Minutes after the last cart change. Every change restarts the wait.', 'GUARDED', 103,
  '{"type":"integer","minimum":15,"maximum":720}'),
 ('journey.cart_recovery.wa_min_cart_paise', 'JOURNEY', 'INT', 'Cart reminder: WhatsApp min cart (paise)',
  'Below this, the cart reminder goes by push only. 0 = every cart.', 'CRITICAL', 104,
  '{"type":"integer","minimum":0,"maximum":10000000}'),
 ('journey.abandon.discount_code', 'JOURNEY', 'STRING', 'Abandoned checkout: discount code',
  'A code created in Shopify admin (Discounts). Sent in the last WhatsApp reminder, about 24 h after the checkout. Empty = no discount message.', 'CRITICAL', 108,
  '{"type":"string","pattern":"^[A-Za-z0-9_-]{0,30}$"}'),
 ('journey.abandon.discount_label', 'JOURNEY', 'STRING', 'Abandoned checkout: what the code gives',
  'Shown with the code, e.g. "10% off". Must match the code in Shopify.', 'CRITICAL', 109,
  '{"type":"string","maxLength":30}')
ON CONFLICT (key) DO NOTHING;

UPDATE config_keys SET default_value = v.val FROM (VALUES
  ('journey.checkout_abandon.stall_minutes',     '30'::jsonb),
  ('journey.checkout_abandon.wa_min_cart_paise', '0'),
  ('journey.cart_recovery.start_delay_minutes',  '45'),
  ('journey.cart_recovery.wa_min_cart_paise',    '0'),
  ('journey.abandon.discount_code',              '""'),
  ('journey.abandon.discount_label',             '""')
) AS v(key, val) WHERE config_keys.key = v.key AND config_keys.default_value IS NULL;
