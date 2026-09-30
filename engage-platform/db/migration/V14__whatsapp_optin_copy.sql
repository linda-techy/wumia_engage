-- V14: the remaining WhatsApp opt-in copy (P2-T07). Both are explicit
-- actions by the shopper, so both cover offers as well as order updates;
-- order updates alone come from the checkout notice (checkout_notice_v1, V12).
--
-- wa_v1: the cart page checkbox (app block "Engage WhatsApp opt-in"), never
--   pre-ticked. The label is the theme embed's "WhatsApp opt-in text".
-- wa_inthread_v1: the message the shopper sends us from the wa.me link
--   (iOS Safari and in-app browsers). Recorded when it arrives (P4 inbound).
-- Text must match what is shown or sent, byte for byte; rows are immutable (V4).
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
VALUES ('wa_v1', 'whatsapp',
        'Send me order updates and offers from WUMIKA on WhatsApp',
        '{transactional,marketing}', 'cart'),
       ('wa_inthread_v1', 'whatsapp',
        'Yes, send me order updates, size-back-in-stock alerts and offers on WhatsApp.',
        '{transactional,marketing}', 'wa_thread')
ON CONFLICT (version) DO NOTHING;
