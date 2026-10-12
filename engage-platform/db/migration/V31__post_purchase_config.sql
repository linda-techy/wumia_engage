-- V31: post_purchase (P5-T06): how many days after delivery the "how do you
-- like it?" WhatsApp goes (at 11:00 IST).

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('journey.post_purchase.delay_days', 'JOURNEY', 'INT', 'After delivery: "how do you like it?" after (days)',
  'Long enough to have worn it, soon enough to remember. Once per order.', 'GUARDED', 123,
  '{"type":"integer","minimum":1,"maximum":30}')
ON CONFLICT (key) DO NOTHING;
UPDATE config_keys SET default_value = '3'::jsonb
 WHERE key = 'journey.post_purchase.delay_days' AND default_value IS NULL;
