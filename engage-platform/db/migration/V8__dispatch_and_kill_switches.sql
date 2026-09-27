-- V8: event dispatch claims, config defaults, kill switches (P3-T02).
--
-- Planned as V10. V8 and V9 (P1 gaps, P2 views) had not been built, and a
-- database at V10 would refuse a later V8 or V9, so this took the next free
-- number and those moved up (docs/implementation/README.md, Migrations).

-- Event dispatch claims by this column, not by an id cursor: ids are assigned
-- at INSERT but become visible at COMMIT, so a cursor skips late-committing rows.
ALTER TABLE events ADD COLUMN dispatched_at TIMESTAMPTZ;
CREATE INDEX events_undispatched_idx ON events (occurred_at) WHERE dispatched_at IS NULL;
-- Existing history must not be replayed into journeys on first start.
UPDATE events SET dispatched_at = now() WHERE dispatched_at IS NULL;

-- Defaults live on the key, not in config_versions: config_versions.changed_by
-- must be a real operator, and a default is not a decision anyone made.
-- Effective value = config_current row if one exists, else config_keys.default_value.
ALTER TABLE config_keys ADD COLUMN default_value JSONB;
UPDATE config_keys SET default_value = v.val FROM (VALUES
  ('quiet_hours',                          '{"from":"21:00","to":"09:00"}'::jsonb),
  ('cap.whatsapp.marketing.1d',            '1'),
  ('cap.whatsapp.marketing.7d',            '3'),
  ('cap.push.marketing.1d',                '3'),
  ('cap.email.marketing.7d',               '3'),
  ('budget.whatsapp.marketing.daily_paise','200000'),   -- ₹2,000: a cautious start; set per business before go-live
  ('rate.whatsapp.marketing_paise',        '86'),       -- ≈ ₹0.8631; verify against your rate card
  ('rate.whatsapp.utility_paise',          '12'),       -- ≈ ₹0.115
  ('holdout.global_pct',                   '5.0'),
  ('journey.enabled',                      'true'),
  ('approval.required_above_paise',        '1000000')   -- ₹10,000
) AS v(key, val) WHERE config_keys.key = v.key;

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order) VALUES
 ('halt.channel',   'CHANNEL','BOOL','Halt channel',
  'Stops every send on this channel, utility included. Use during a provider incident.','GUARDED',1),
 ('halt.marketing', 'GLOBAL', 'BOOL','Halt all marketing',
  'Stops marketing on every channel. Utility continues.','GUARDED',2),
 ('halt.journey',   'JOURNEY','BOOL','Halt journey', NULL,'GUARDED',3),
 ('push.ttl_seconds.default','CHANNEL','INT','Push TTL default (s)', NULL,'SAFE',70),
 ('push.campaign_stale_days','CHANNEL','INT','Push: token stale after (days)',
  'Stale tokens are excluded from campaigns, kept for back-in-stock.','GUARDED',71),
 -- Not in the original plan: the per-journey holdout needs a percentage to
 -- read. 0 = no journey holdout running. P5-T09 sets 10 for marketing journeys
 -- (selector = journey key) and leaves transactional ones at 0.
 ('holdout.journey_pct','JOURNEY','DECIMAL','Journey holdout %',
  'Share of identities held out of this journey to measure its lift.','CRITICAL',41)
ON CONFLICT (key) DO NOTHING;
UPDATE config_keys SET default_value = 'false' WHERE key IN ('halt.channel','halt.marketing','halt.journey');
UPDATE config_keys SET default_value = '43200' WHERE key = 'push.ttl_seconds.default';   -- 12 h
UPDATE config_keys SET default_value = '30'    WHERE key = 'push.campaign_stale_days';
UPDATE config_keys SET default_value = '0'     WHERE key = 'holdout.journey_pct';

CREATE FUNCTION notify_config_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_notify('config_changed', NEW.key);
  RETURN NEW;
END $$;
CREATE TRIGGER config_versions_notify AFTER INSERT ON config_versions
  FOR EACH ROW EXECUTE FUNCTION notify_config_changed();
