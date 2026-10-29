-- V55: why a push permission was not granted (owner, 2026-10-29: four shoppers
-- tapped Allow on our card and their browser blocked it). The storefront sends
-- 'auto' when the browser answered before a person could (a phone or browser
-- setting, a private window) and 'user' when the shopper tapped Block; it sent
-- the reason before, but nothing kept it.
ALTER TABLE push_prompt_events ADD COLUMN reason TEXT;
