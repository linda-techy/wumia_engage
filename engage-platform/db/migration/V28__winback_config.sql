-- V28: win-back, "long time no see" (P5-T08; owner's decision 2026-10-11:
-- do what most Indian D2C brands do). A customer whose last order is
-- lapsed_days old and who has not been on the site for no_visit_days gets a
-- warm "here's what's new" WhatsApp, a push a week later, and an offer a week
-- after that only if a discount is set. Once per lapse; any order ends it.
-- Takes the winback keys from the planned P5-T01 migration.

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('journey.winback.lapsed_days', 'JOURNEY', 'INT', 'Win-back: days since last order',
  'Jewellery is bought every few months; 90 days suits most customers. The message goes once per lapse.', 'GUARDED', 105,
  '{"type":"integer","minimum":30,"maximum":365}'),
 ('journey.winback.no_visit_days', 'JOURNEY', 'INT', 'Win-back: only if no visit for (days)',
  'Someone browsing recently gets the browse and cart messages instead.', 'GUARDED', 115,
  '{"type":"integer","minimum":1,"maximum":365}'),
 ('journey.winback.link_path', 'JOURNEY', 'STRING', 'Win-back: page the message opens',
  'A path on the store, e.g. collections/new-arrivals. collections/all always exists.', 'SAFE', 116,
  '{"type":"string","pattern":"^[a-z0-9][a-z0-9/_-]{0,80}$"}'),
 ('journey.winback.discount_mode', 'JOURNEY', 'STRING', 'Win-back: discount codes',
  'shared: the one code set below. unique: a single-use code per customer, made in Shopify (needs write_discounts).', 'CRITICAL', 117,
  '{"type":"string","enum":["shared","unique"]}'),
 ('journey.winback.discount_code', 'JOURNEY', 'STRING', 'Win-back: shared discount code',
  'Created in Shopify admin. Empty (and shared mode) = no offer message.', 'CRITICAL', 118,
  '{"type":"string","pattern":"^[A-Za-z0-9_-]{0,30}$"}'),
 ('journey.winback.discount_label', 'JOURNEY', 'STRING', 'Win-back: what the shared code gives',
  'e.g. "10% off". Must match the code in Shopify.', 'CRITICAL', 119,
  '{"type":"string","maxLength":30}'),
 ('journey.winback.discount_percent', 'JOURNEY', 'INT', 'Win-back unique codes: percent off', NULL, 'CRITICAL', 120,
  '{"type":"integer","minimum":1,"maximum":50}'),
 ('journey.winback.discount_min_order_paise', 'JOURNEY', 'INT', 'Win-back unique codes: minimum order (paise)',
  '0 = no minimum.', 'CRITICAL', 121,
  '{"type":"integer","minimum":0,"maximum":10000000}'),
 ('journey.winback.discount_valid_hours', 'JOURNEY', 'INT', 'Win-back unique codes: valid for (hours)', NULL, 'GUARDED', 122,
  '{"type":"integer","minimum":1,"maximum":168}')
ON CONFLICT (key) DO NOTHING;

UPDATE config_keys SET default_value = v.val FROM (VALUES
  ('journey.winback.lapsed_days',              '90'::jsonb),
  ('journey.winback.no_visit_days',            '30'),
  ('journey.winback.link_path',                '"collections/all"'),
  ('journey.winback.discount_mode',            '"shared"'),
  ('journey.winback.discount_code',            '""'),
  ('journey.winback.discount_label',           '""'),
  ('journey.winback.discount_percent',         '10'),
  ('journey.winback.discount_min_order_paise', '0'),
  ('journey.winback.discount_valid_hours',     '48')
) AS v(key, val) WHERE config_keys.key = v.key AND config_keys.default_value IS NULL;
