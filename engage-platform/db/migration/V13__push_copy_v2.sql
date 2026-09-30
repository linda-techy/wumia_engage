-- V13: push soft-ask copy v2 (asked early: 2nd page view or ~20 s on site,
-- as well as at add-to-cart and notify-me). The browser's own "Allow" still
-- has to follow the tap, so push is never assumed.
--
-- v2 names order updates, so a grant covers transactional and marketing.
-- push_v1 stays registered: themes that saved its text keep working. The text
-- must match the theme embed's "Push soft-ask text" byte for byte.
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
VALUES ('push_v2', 'push',
        'Get order updates, and an alert when your size is back or your bag price drops.',
        '{transactional,marketing}', 'soft_ask')
ON CONFLICT (version) DO NOTHING;
