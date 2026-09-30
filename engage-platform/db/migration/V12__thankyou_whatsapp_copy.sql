-- V12: WhatsApp consent copy for checkout (P2-T05). Rows are immutable (V4):
-- new words = a new version, registered before the storefront shows them.
--
-- Two bases, as most Indian D2C stores run it:
--
-- checkout_notice_v1: order and delivery updates only. The notice is the
--   checkout phone field's label (Shopify admin -> checkout language), shown
--   before the number is typed; nobody ticks anything. DPDP Act s.7(a): a
--   number given for an order may be used for that order. Recorded per order
--   from CHECKOUT_NOTICE_SINCE, never over a withdrawal, and STOP withdraws it.
--
-- ty_wa_v1: the Thank you page block, an explicit tap. It names offers, so it
--   covers marketing as well. Marketing is never assumed from a notice or a
--   pre-ticked box: DPDP s.6 needs a clear affirmative action.
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
VALUES ('checkout_notice_v1', 'whatsapp',
        'Phone (for order and delivery updates on WhatsApp/SMS)',
        '{transactional}', 'checkout_notice'),
       ('ty_wa_v1', 'whatsapp',
        'Get order updates, new arrivals and offers from WUMIKA on WhatsApp. Reply STOP anytime to opt out.',
        '{transactional,marketing}', 'thank_you')
ON CONFLICT (version) DO NOTHING;
