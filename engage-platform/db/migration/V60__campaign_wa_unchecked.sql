-- V60: WhatsApp campaigns may send to numbers not yet checked (owner, 2026-11-05).
-- A number is "checked" once a message to it was delivered or the person wrote in.
-- The number went live on 2026-10-30, so almost every past customer is still
-- unchecked: with the old rule (never) the first win-back reached 1 of 99. Most
-- Indian D2C brands send the first campaign to the whole opted-in list, and Meta
-- charges only for messages it delivers; a number without WhatsApp fails once
-- (131026) and is counted as a strike by the capability tracker. INCAPABLE
-- numbers stay excluded. Switch off to go back to "known numbers only".

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order) VALUES
 ('campaign.whatsapp.allow_unchecked', 'GLOBAL', 'BOOL', 'WhatsApp campaigns: send to numbers not yet checked',
  'On: a campaign also goes to opted-in numbers Engage has not yet confirmed are on WhatsApp. A number that turns out not to be on WhatsApp fails once and is counted by the capability tracker. Off: campaigns reach only numbers already known to be on WhatsApp.', 'GUARDED', 150)
ON CONFLICT (key) DO NOTHING;

UPDATE config_keys SET default_value = 'true'::jsonb WHERE key = 'campaign.whatsapp.allow_unchecked' AND default_value IS NULL;
