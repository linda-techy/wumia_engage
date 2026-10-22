-- V45: cart recovery as in the owner's playbook (2026-10-22): the first
-- message (now a push, free) 30 minutes after the cart was left, not 45.
-- Operators who already set their own delay keep it (config_versions).
UPDATE config_keys SET default_value = '30'::jsonb
 WHERE key = 'journey.cart_recovery.start_delay_minutes';
