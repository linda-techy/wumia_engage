-- V15: checkout notice v2 also names offers (decided 2026-10-01, the common
-- Indian D2C approach: WhatsApp marketing to every buyer by default, opt-out).
--
-- The checkout phone field's label must read exactly this text. From
-- CHECKOUT_NOTICE_SINCE, an order with a phone records WhatsApp order updates
-- and offers on this notice: evidence says basis 'notice_opt_out' for offers
-- (no affirmative action was taken) and is never written over a STOP.
-- checkout_notice_v1 (order updates only) stays for orders it already covers.
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
VALUES ('checkout_notice_v2', 'whatsapp',
        'Phone (for order updates and offers on WhatsApp/SMS)',
        '{transactional,marketing}', 'checkout_notice')
ON CONFLICT (version) DO NOTHING;
