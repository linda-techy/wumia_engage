-- V20: P6-T06 campaigns. A campaign is run as a one-off cascade
-- (intent campaign:<id>): its channel and template, then optionally a
-- follow-up on another channel some hours later to those who did not click
-- (phase-6 §4: push first, WhatsApp 6 h later).

ALTER TABLE campaigns
  ADD COLUMN ttl_seconds INT CHECK (ttl_seconds BETWEEN 60 AND 172800),   -- push: at most 48 h
  ADD COLUMN follow_up_channel channel,
  ADD COLUMN follow_up_template_key TEXT REFERENCES templates(key),
  ADD COLUMN follow_up_after_minutes INT CHECK (follow_up_after_minutes BETWEEN 60 AND 4320),
  -- Why a campaign is PAUSED: operator, halt, budget_cap. Resume checks it.
  ADD COLUMN paused_reason TEXT,
  ADD CONSTRAINT campaigns_follow_up_complete CHECK (
    (follow_up_channel IS NULL) = (follow_up_template_key IS NULL)
    AND (follow_up_channel IS NULL) = (follow_up_after_minutes IS NULL)),
  ADD CONSTRAINT campaigns_follow_up_other_channel CHECK (follow_up_channel IS DISTINCT FROM channel);

-- The executor sums a campaign's spend on every pass (budget cap).
CREATE INDEX sends_campaign_cost_idx ON sends (intent_key) INCLUDE (cost_paise, status)
  WHERE intent_key LIKE 'campaign:%';
