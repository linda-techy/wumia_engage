-- V64: Magic's checkbox also counts for WhatsApp offers (owner's decision, 2026-11-10).
--
-- Same words as magic_checkbox_v1: "Send me offers and order updates". The box is
-- pre-ticked and names no channel; the owner has decided that, with the DPDP
-- consent already agreed at checkout, it covers WhatsApp order updates and offers
-- (as checkout_notice_v2 does for Shopify checkouts). Evidence on each grant says
-- basis 'magic_checkbox' for offers and 'pre_ticked' true, so the record claims no
-- affirmative action. A STOP is never overridden, and v1 stays for what it covered.
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
VALUES ('magic_checkbox_v2', 'whatsapp',
        'Send me offers and order updates',
        '{transactional,marketing}', 'magic_checkout')
ON CONFLICT (version) DO NOTHING;
