-- V42: Shopify's own phone marketing checkbox as WhatsApp and SMS marketing
-- consent (decided 2026-10-22, conversion: what Indian D2C stores do).
--
-- The checkbox at checkout and on the account ("Text me with news and offers")
-- must be relabelled to exactly this text in Shopify (Settings → Checkout →
-- language / Customer accounts). From SHOPIFY_PHONE_MARKETING_SINCE, ticking
-- it grants WhatsApp and SMS marketing (an affirmative action, unlike the
-- checkout notice) and unticking or unsubscribing withdraws both.
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
VALUES ('shopify_phone_marketing_v1', 'whatsapp',
        'Send me offers on WhatsApp and SMS',
        '{marketing}', 'shopify_marketing_checkbox')
ON CONFLICT (version) DO NOTHING;
