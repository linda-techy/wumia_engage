-- V12: register the Thank you page WhatsApp opt-in copy (phase-2 §6.2, P2-T05).
--
-- The text is what the block shows above its button, byte for byte
-- (extensions/thankyou-whatsapp). It covers transactional messages only:
-- dispatch, delivery and exchange updates. Offers need their own version
-- that names them. Rows are immutable (V4): new words = a new version.
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface)
VALUES ('ty_wa_v1', 'whatsapp',
        'Get dispatch, delivery and exchange updates from WUMIKA on WhatsApp.',
        '{transactional}', 'thank_you')
ON CONFLICT (version) DO NOTHING;
