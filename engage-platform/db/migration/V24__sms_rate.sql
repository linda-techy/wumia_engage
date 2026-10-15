-- V24: P4-T06 SMS (MSG91, DLT). What one transactional SMS costs, so policy
-- books it in spend_ledger at send time (SendRepository.markSent) and a daily
-- budget can cap it. Delivery reports land in webhook_inbox as source 'msg91'.

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('rate.sms.utility_paise', 'CHANNEL', 'INT', 'SMS transactional rate (paise)',
  'MSG91 price per 160-character part, DLT scrubbing included. Verify against your MSG91 rate card.', 'GUARDED', 33,
  '{"type":"integer","minimum":0,"maximum":1000}')
ON CONFLICT (key) DO NOTHING;
UPDATE config_keys SET default_value = '20'::jsonb      -- ≈ ₹0.20; verify before go-live
 WHERE key = 'rate.sms.utility_paise' AND default_value IS NULL;
