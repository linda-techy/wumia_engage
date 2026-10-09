-- V23: P4-T03 WhatsApp number health, and the WhatsApp config keys.
-- (Planned as the P4 number-health migration; built after V22.)

-- Each business phone number's quality rating and messaging tier, current
-- and as history: a High → Medium drop is an incident (06-admin-ui), and the
-- dashboard shows the 30-day trend.
CREATE TABLE wa_phone_numbers (
  phone_number_id TEXT PRIMARY KEY,
  display_number  TEXT NOT NULL,
  verified_name   TEXT,
  quality         TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (quality IN ('GREEN','YELLOW','RED','UNKNOWN')),
  messaging_tier  TEXT,                          -- TIER_250 | TIER_1K | TIER_10K | TIER_100K | TIER_UNLIMITED
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE wa_phone_number_history (
  phone_number_id TEXT NOT NULL REFERENCES wa_phone_numbers,
  quality         TEXT NOT NULL,
  messaging_tier  TEXT,
  event           TEXT NOT NULL,                 -- FLAGGED | UNFLAGGED | UPGRADE | DOWNGRADE | sync
  observed_at     TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (phone_number_id, observed_at, event)
);

-- wa_templates is keyed by Meta's identity for a template, (name, language):
-- the English and Hinglish copies of one template are two Meta templates in
-- language "en" ("order_shipped_v1", "order_shipped_v1_hi"), both with our
-- key "order_shipped_v1".
ALTER TABLE wa_templates DROP CONSTRAINT wa_templates_pkey;
ALTER TABLE wa_templates ADD PRIMARY KEY (provider_name, language);
CREATE INDEX wa_templates_key_idx ON wa_templates (key);

-- Phase-4 config keys (planned values; the code uses the same defaults).
INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('wa.service_window_hours', 'CHANNEL', 'INT', 'WhatsApp service window (h)', 'Meta: 24.', 'SAFE', 80,
  '{"type":"integer","minimum":1,"maximum":24}'),
 ('wa.backoff_131049_hours', 'CHANNEL', 'INT', 'Back off after 131049 (h)',
  'Meta chose not to deliver a marketing message: skip WhatsApp for this person this long.', 'GUARDED', 81,
  '{"type":"integer","minimum":1,"maximum":168}'),
 ('wa.incapable_recheck_days', 'CHANNEL', 'INT', 'Re-check INCAPABLE after (days)',
  'People install WhatsApp; a number written off is tried again after this.', 'GUARDED', 82,
  '{"type":"integer","minimum":30,"maximum":365}')
ON CONFLICT (key) DO NOTHING;
UPDATE config_keys SET default_value = v.val FROM (VALUES
  ('wa.service_window_hours', '24'::jsonb), ('wa.backoff_131049_hours', '24'), ('wa.incapable_recheck_days', '120')
) AS v(key, val) WHERE config_keys.key = v.key;
