-- V47: the welcome series (owner's playbook 2026-10-22, §3): after an opt-in,
-- before a first order, day 0, day 2 and day 5 with a first-order offer when
-- an operator sets a code. Holdouts apply as to every marketing journey.
INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('journey.welcome.discount_code', 'JOURNEY', 'STRING', 'Welcome series: first-order code',
  'A code created in Shopify admin (Discounts), e.g. the one the push prompt offers. Sent on day 5 (push) and day 6 (WhatsApp) to new subscribers who have not bought. Empty = no offer step.', 'CRITICAL', 130,
  '{"type":"string","pattern":"^[A-Za-z0-9_-]{0,30}$"}'),
 ('journey.welcome.discount_label', 'JOURNEY', 'STRING', 'Welcome series: what the code gives',
  'Shown with the code, e.g. "10% off". Must match the code in Shopify.', 'CRITICAL', 131,
  '{"type":"string","maxLength":30}')
ON CONFLICT (key) DO NOTHING;

UPDATE config_keys SET default_value = v.val FROM (VALUES
  ('journey.welcome.discount_code',  '""'::jsonb),
  ('journey.welcome.discount_label', '""')
) AS v(key, val) WHERE config_keys.key = v.key AND config_keys.default_value IS NULL;
