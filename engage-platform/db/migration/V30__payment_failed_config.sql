-- V30: payment_failed (P5-T02). How long after a gateway-confirmed failure
-- the first WhatsApp goes: most UPI failures are retried at once, and a
-- message too early reads as the brand not knowing. Taken from the planned
-- P5-T01 journey migration.

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('journey.payment_failed.first_delay_minutes', 'JOURNEY', 'INT', 'Payment failed: first message after (min)',
  'Most UPI failures are retried at once. Too early reads as the brand not knowing.', 'GUARDED', 100,
  '{"type":"integer","minimum":1,"maximum":60}')
ON CONFLICT (key) DO NOTHING;
UPDATE config_keys SET default_value = '5'::jsonb
 WHERE key = 'journey.payment_failed.first_delay_minutes' AND default_value IS NULL;
