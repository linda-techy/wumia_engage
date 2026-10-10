-- V27: single-use abandoned-checkout codes (owner's decision, 2026-10-11).
-- In unique mode the worker creates one Shopify code per customer when the
-- discount WhatsApp goes out: one use, valid discount_valid_hours. Needs the
-- write_discounts scope on the store's app; without it the discount message
-- is skipped and the reminders still go. Shared mode keeps the operator's
-- code (journey.abandon.discount_code / _label).

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('journey.abandon.discount_mode', 'JOURNEY', 'STRING', 'Abandoned checkout: discount codes',
  'shared: the one code set below. unique: a single-use code per customer, made by the platform in Shopify (needs write_discounts on the app).', 'CRITICAL', 107,
  '{"type":"string","enum":["shared","unique"]}'),
 ('journey.abandon.discount_percent', 'JOURNEY', 'INT', 'Unique codes: percent off',
  'Percentage off the whole order for each single-use code.', 'CRITICAL', 112,
  '{"type":"integer","minimum":1,"maximum":50}'),
 ('journey.abandon.discount_min_order_paise', 'JOURNEY', 'INT', 'Unique codes: minimum order (paise)',
  'The code only works on an order of at least this subtotal. 0 = no minimum.', 'CRITICAL', 113,
  '{"type":"integer","minimum":0,"maximum":10000000}'),
 ('journey.abandon.discount_valid_hours', 'JOURNEY', 'INT', 'Unique codes: valid for (hours)',
  'How long each code works after the message is sent.', 'GUARDED', 114,
  '{"type":"integer","minimum":1,"maximum":168}')
ON CONFLICT (key) DO NOTHING;

UPDATE config_keys SET default_value = v.val FROM (VALUES
  ('journey.abandon.discount_mode',           '"shared"'::jsonb),
  ('journey.abandon.discount_percent',        '10'),
  ('journey.abandon.discount_min_order_paise', '0'),
  ('journey.abandon.discount_valid_hours',    '48')
) AS v(key, val) WHERE config_keys.key = v.key AND config_keys.default_value IS NULL;
