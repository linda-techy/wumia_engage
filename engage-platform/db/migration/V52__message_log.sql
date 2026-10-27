-- V52: the console's Message log (every send, every customer, newest first).
-- The template wording, mirrored from templates/*.yaml by the registry sync
-- (English copy; admin-api does not load the YAML). Wording only, never a
-- filled-in message: sends keep no rendered text, so nothing personal outlives
-- an erasure. WhatsApp's actual text comes from wa_messages, which erasure deletes.
ALTER TABLE templates
  ADD COLUMN title TEXT,
  ADD COLUMN body  TEXT;

CREATE INDEX wa_messages_send_idx ON wa_messages (send_id) WHERE send_id IS NOT NULL;
