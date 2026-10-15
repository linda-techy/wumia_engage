-- V26: who may get the abandoned-checkout discount (owner's decision,
-- 2026-10-10). A shopper who learns that leaving checkout earns a code waits
-- for it every time, so the discount goes at most once per customer per
-- cooldown, and optionally only to people who have never ordered. Pair it
-- with "Limit to one use per customer" on the code in Shopify admin.

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('journey.abandon.discount_cooldown_days', 'JOURNEY', 'INT', 'Abandoned checkout: discount at most once per (days)',
  'A customer who got the discount message gets no other within this many days. The reminders still go.', 'GUARDED', 110,
  '{"type":"integer","minimum":1,"maximum":365}'),
 ('journey.abandon.discount_new_customers_only', 'JOURNEY', 'BOOL', 'Abandoned checkout: discount for first-time buyers only',
  'On: customers with a past order get the reminders but never the discount.', 'GUARDED', 111,
  '{"type":"boolean"}')
ON CONFLICT (key) DO NOTHING;

UPDATE config_keys SET default_value = v.val FROM (VALUES
  ('journey.abandon.discount_cooldown_days',      '60'::jsonb),
  ('journey.abandon.discount_new_customers_only', 'false')
) AS v(key, val) WHERE config_keys.key = v.key AND config_keys.default_value IS NULL;
