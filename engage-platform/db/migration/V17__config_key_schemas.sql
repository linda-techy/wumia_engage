-- V17: P6-T03 validation bounds for every config key. The config API checks
-- each write against value_type and this json_schema (a supported subset:
-- type, minimum, maximum, enum, pattern, minLength, maxLength); a keyword it
-- does not know fails the write rather than being ignored.
--
-- Platform limits are encoded as validation, not documentation
-- (02-config-and-settings.md): Meta's per-user marketing limit caps WhatsApp
-- marketing at 2 a day; FCM keeps a message at most 28 days. The rest are
-- sanity bounds that stop a typo (an extra zero) from becoming policy.
-- Built before P4, so the planned P4–P7 migrations moved up by one more.

UPDATE config_keys SET json_schema = v.schema::jsonb FROM (VALUES
  ('cap.whatsapp.marketing.1d',            '{"type":"integer","minimum":0,"maximum":2}'),
  ('cap.whatsapp.marketing.7d',            '{"type":"integer","minimum":0,"maximum":7}'),
  ('cap.push.marketing.1d',                '{"type":"integer","minimum":0,"maximum":10}'),
  ('cap.email.marketing.7d',               '{"type":"integer","minimum":0,"maximum":14}'),
  ('budget.whatsapp.marketing.daily_paise','{"type":"integer","minimum":0,"maximum":100000000}'),
  ('rate.whatsapp.marketing_paise',        '{"type":"integer","minimum":0,"maximum":1000}'),
  ('rate.whatsapp.utility_paise',          '{"type":"integer","minimum":0,"maximum":1000}'),
  ('holdout.global_pct',                   '{"type":"number","minimum":0,"maximum":50}'),
  ('holdout.journey_pct',                  '{"type":"number","minimum":0,"maximum":50}'),
  ('approval.required_above_paise',        '{"type":"integer","minimum":0,"maximum":100000000}'),
  ('push.ttl_seconds.default',             '{"type":"integer","minimum":60,"maximum":2419200}'),
  ('push.campaign_stale_days',             '{"type":"integer","minimum":1,"maximum":365}')
) AS v(key, schema) WHERE config_keys.key = v.key;

-- One pending proposal per key and selector: a second would race the first
-- to approval and the loser's reviewer would approve a stale diff.
CREATE UNIQUE INDEX config_proposals_one_pending ON config_proposals (key, selector) WHERE status = 'PENDING';
