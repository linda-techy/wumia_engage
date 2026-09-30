-- browse_abandon (P3-T07) reads one browser's recent pixel events on every
-- product view to find its session (views separated by < 30 min idle).
-- Pixel events carry no identity, so the browser's client_id is the key.
CREATE INDEX events_pixel_client_idx ON events ((props->>'client_id'), occurred_at DESC)
  WHERE source = 'pixel';
