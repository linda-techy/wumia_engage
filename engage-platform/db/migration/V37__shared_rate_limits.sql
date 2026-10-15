-- V37: storefront rate limits shared across ingest pods (P7-T06). Production
-- runs 3–10 ingest pods; per-pod counters would let N times the limit through.
-- UNLOGGED: a crash loses the current windows, which only forgives a few
-- requests; in exchange every hit is a cheap write with no WAL.
CREATE UNLOGGED TABLE rate_windows (
  bucket_key   TEXT        NOT NULL,   -- "<bucket>:<key>", key hashed when long
  window_start TIMESTAMPTZ NOT NULL,   -- aligned to the window length
  hits         INT         NOT NULL DEFAULT 1,
  PRIMARY KEY (bucket_key, window_start)
);
CREATE INDEX rate_windows_age_idx ON rate_windows (window_start);
