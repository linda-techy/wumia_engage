-- V32: holdouts on every journey (P5-T09). Each journey's key is its holdout
-- experiment; 10% of people are kept out of each marketing journey so its
-- lift can be measured (P7). The policy engine applies it to marketing sends
-- only, so transactional journeys (order updates, payment_failed) are never
-- held out, and campaigns keep their own control group. A journey can be set
-- differently in the admin console with the journey's key as the selector.

UPDATE config_keys SET default_value = '10'::jsonb WHERE key = 'holdout.journey_pct';
