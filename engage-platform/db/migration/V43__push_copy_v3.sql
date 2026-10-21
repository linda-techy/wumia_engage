-- V43: push soft-ask copy v3 (2026-10-22, conversion: what Indian D2C stores
-- promise for the Allow). Asked at intent moments: 2nd page view or ~20 s,
-- add-to-cart, notify-me, a 2nd or long look at a product, desktop exit
-- intent, and the bell after "Later". The headline and an optional
-- first-order code are presentation; this sentence is the consent text and
-- must match the theme embed's "Push soft-ask text" byte for byte.
-- push_v1 and push_v2 stay registered for themes that saved their text.
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
VALUES ('push_v3', 'push',
        'Get new drops first, price-drop and back-in-stock alerts, offers, and live order updates.',
        '{transactional,marketing}', 'soft_ask')
ON CONFLICT (version) DO NOTHING;
