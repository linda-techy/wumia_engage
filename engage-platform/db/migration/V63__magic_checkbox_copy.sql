-- V63: the consent checkbox on Razorpay Magic Checkout's contact step.
--
-- Read from the live checkout on 2026-11-10: a checkbox that is already ticked,
-- next to the words "Send me offers and order updates". It names no channel
-- (not WhatsApp, not SMS). That is too thin to record as consent to WhatsApp
-- offers, so the copy is registered for order updates only. A Magic buyer who
-- left it ticked ('Yes') gets WhatsApp transactional (order confirmation,
-- shipping and delivery news); marketing and abandoned-checkout WhatsApp need a
-- stronger basis (cart tick, Thank-you page, WhatsApp opt-in, an earlier order).
-- If Razorpay lets the wording name WhatsApp, register a new version for it.
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
VALUES ('magic_checkbox_v1', 'whatsapp',
        'Send me offers and order updates',
        '{transactional}', 'magic_checkout')
ON CONFLICT (version) DO NOTHING;
