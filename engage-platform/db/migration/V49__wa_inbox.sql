-- V49: the WhatsApp inbox (owner 2026-10-27: the support number +91 73…86
-- moves from the WhatsApp Business app into Engage, so staff read and answer
-- customers in the console instead of on the phone).
--
-- wa_messages: every message on the number, both ways: what customers send
-- (text, captions, button taps, media ids), Engage's own automated messages
-- (send_id) and staff replies (sent_by). Media stays at Meta; the console
-- fetches it through admin-api by media_id.
-- wa_threads: one row per customer: the last message, unread count, the 24 h
-- reply window, and open/done for the team.
--
-- Erasure (V36 erase_identity) deletes the identity; both tables cascade.

CREATE TABLE wa_messages (
  id            BIGSERIAL PRIMARY KEY,
  identity_id   UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  direction     TEXT NOT NULL CHECK (direction IN ('in', 'out')),
  wamid         TEXT UNIQUE,
  kind          TEXT NOT NULL,           -- text | image | video | audio | document | sticker | location | button | interactive | reaction | template | other
  body          TEXT,                    -- text, caption, button title, or the rendered template
  media_id      TEXT,
  media_mime    TEXT,
  media_name    TEXT,
  template_key  TEXT,
  send_id       BIGINT,                  -- sends.id: one of Engage's automated messages
  sent_by       UUID REFERENCES operators(id) ON DELETE SET NULL,   -- a staff reply
  status        TEXT CHECK (status IN ('sent', 'delivered', 'read', 'failed')),
  failed_reason TEXT,
  context_wamid TEXT,                    -- the message this one answers
  at            TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX wa_messages_thread_idx ON wa_messages (identity_id, at DESC);

CREATE TABLE wa_threads (
  identity_id     UUID PRIMARY KEY REFERENCES identities(id) ON DELETE CASCADE,
  last_message_at TIMESTAMPTZ NOT NULL,
  last_inbound_at TIMESTAMPTZ,
  last_preview    TEXT,
  unread          INT NOT NULL DEFAULT 0 CHECK (unread >= 0),
  status          TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'done')),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX wa_threads_recent_idx ON wa_threads (status, last_message_at DESC);

-- Opening a conversation shows the customer's number and messages; a staff
-- reply is contact too. Both are logged like any other contact-detail view.
ALTER TABLE pii_unmask_log DROP CONSTRAINT pii_unmask_log_field_check;
ALTER TABLE pii_unmask_log ADD CONSTRAINT pii_unmask_log_field_check
  CHECK (field IN ('phone', 'email', 'address', 'support_reply', 'data_request', 'whatsapp_inbox', 'whatsapp_reply'));
