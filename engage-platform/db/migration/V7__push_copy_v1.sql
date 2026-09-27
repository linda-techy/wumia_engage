-- V7: register the storefront push soft-ask copy (phase-2 §3).
--
-- A push grant covers exactly what the shopper was shown, and the server
-- refuses a registration whose copy version (or wording) is not registered
-- here. The text must match the theme embed's "Push soft-ask text" default
-- byte for byte. New words = a new version (rows are immutable, V4); register
-- it here or in the admin console before the theme shows it.
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
VALUES ('push_v1', 'push',
        'Get an alert when your size is back or your bag price drops.',
        '{marketing}', 'soft_ask')
ON CONFLICT (version) DO NOTHING;
