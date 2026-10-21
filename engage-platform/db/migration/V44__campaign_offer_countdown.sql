-- V44: offer countdown on push campaigns (2026-10-22, owner: "like Myntra,
-- the offer timer running out"). Web push cannot tick like a native app's
-- notification, so a countdown campaign does what Indian D2C sites do on the web:
--
--   ends_at              the offer's end. "{time_left}" in the title or text is
--                        filled when each push goes (e.g. "2h 15m"); nothing is
--                        delivered after it (the push TTL never outlives the offer).
--   countdown_poster     the poster is served with an "OFFER ENDS IN HH:MM:SS"
--                        band, drawn when the phone fetches it (ingest-api
--                        /media/countdown/<id>), so it shows the time left at display.
--   countdown_reminders  "last chance" pushes at 24 h, 3 h and 1 h before the
--                        end, to those who have not tapped yet; each replaces the
--                        previous notification (same tag), so it counts down.

ALTER TABLE campaigns
  ADD COLUMN ends_at TIMESTAMPTZ,
  ADD COLUMN countdown_poster BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN countdown_reminders BOOLEAN NOT NULL DEFAULT false,
  ADD CONSTRAINT campaigns_countdown_needs_end CHECK (
    (NOT countdown_poster AND NOT countdown_reminders) OR ends_at IS NOT NULL),
  ADD CONSTRAINT campaigns_countdown_push_only CHECK (
    (NOT countdown_poster AND NOT countdown_reminders) OR channel = 'push'),
  -- The reminders are the follow-up: one or the other.
  ADD CONSTRAINT campaigns_countdown_or_follow_up CHECK (
    NOT countdown_reminders OR follow_up_channel IS NULL);
