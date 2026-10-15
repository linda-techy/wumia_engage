-- V33: support requests (owner's decision 2026-10-15: failed deliveries are
-- handled by staff, not forwarded to the courier automatically). Our WhatsApp
-- runs on the Cloud API, which has no inbox: a customer's reply only reaches
-- us, so staff see it here.
--
--   delivery        a WhatsApp reply within 72 h of the "delivery did not go
--                   through" message (ndr_raised_v1), or its reschedule button.
--                   One open request per parcel; later replies are appended.
--   after_delivery  a "Need help" tap on the review message (post_purchase).
--
-- message_text is kept only here, for staff to act on (it often holds an
-- address), and cleared 30 days after the request is resolved. Reading it
-- in the console goes through PiiAccess (logged, counted per operator).

CREATE TABLE support_requests (
  id            BIGSERIAL PRIMARY KEY,
  identity_id   UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  kind          TEXT NOT NULL CHECK (kind IN ('delivery', 'after_delivery')),
  order_id      TEXT,
  shipment_id   BIGINT,
  message_text  TEXT,
  message_ids   TEXT[] NOT NULL DEFAULT '{}',
  status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'done')),
  note          TEXT,
  resolved_by   UUID REFERENCES operators(id),
  resolved_at   TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX support_requests_status_idx ON support_requests (status, created_at DESC);
CREATE UNIQUE INDEX support_requests_open_parcel ON support_requests (shipment_id)
  WHERE status = 'open' AND kind = 'delivery';
