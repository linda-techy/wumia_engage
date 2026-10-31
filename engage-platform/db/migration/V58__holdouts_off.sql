-- V58: no control groups for now (owner, 2026-10-31). At this store's volume
-- (a few abandoned checkouts a day) a 10% journey holdout and a 5% global one
-- would take months to reach the 30 people per group a lift reading needs, and
-- every held-out shopper is a cart or checkout not reminded. Measured instead
-- by the revenue credited to messages (dashboard, Analytics), as most Indian
-- D2C brands do. Turn a journey holdout back on (about 5-10%, cart recovery
-- first) at around 1,000 abandoned carts a month to prove lift.
--
-- Defaults only: an operator's value set in the console still wins.

UPDATE config_keys SET default_value = '0'::jsonb WHERE key IN ('holdout.journey_pct', 'holdout.global_pct');
UPDATE config_keys SET help_text = 'Share of everyone kept out of all marketing, to measure what marketing adds overall. 0 = off. Worth it only with enough volume: at least 30 people per group.'
 WHERE key = 'holdout.global_pct';
UPDATE config_keys SET help_text = 'Share of identities held out of this journey to measure its lift. 0 = off. Worth it from about 1,000 entries a month.'
 WHERE key = 'holdout.journey_pct';
