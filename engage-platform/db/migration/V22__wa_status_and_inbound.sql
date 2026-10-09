-- V22: P4-T04 WhatsApp statuses and inbound messages.

-- Spend is booked from the status webhook, once per message: pricing.category
-- is what Meta actually billed, which can differ from what we sent when Meta
-- re-classifies a template. billed_at marks the booking so a replayed status
-- books nothing.
ALTER TABLE sends
  ADD COLUMN billed_at       TIMESTAMPTZ,
  ADD COLUMN billed_category msg_category;

-- One row per inbound WhatsApp message id: the dedupe that makes a replayed
-- STOP one withdrawal set and one confirmation. No message text is kept.
CREATE TABLE wa_inbound (
  message_id  TEXT PRIMARY KEY,
  identity_id UUID REFERENCES identities(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL,          -- text | button | interactive | other
  action      TEXT,                   -- stop | start | optin | stop_promotions | NULL
  sent_at     TIMESTAMPTZ NOT NULL,   -- the customer's timestamp
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX wa_inbound_identity_idx ON wa_inbound (identity_id, sent_at DESC);
