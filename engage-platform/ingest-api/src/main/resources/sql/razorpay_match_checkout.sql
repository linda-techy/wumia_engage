-- Join a Razorpay payment to the Shopify checkout it belongs to.
--
-- Parameters:
--   1  notes_json     (text, JSON object)  — payment notes
--   2  notes_json     (text, JSON object)  — same value, used for cart tokens
--   3  phone          (text, 91XXXXXXXXXX or NULL)
--   4  amount_paise   (bigint)
--   5  paid_at        (timestamptz)        — payment created_at
--   6  window_minutes (int)
--   7  paid_at        (timestamptz)
--
-- Strategy, best first:
--   notes_ref           a notes value equals a known checkout or cart token.
--                       Exact. Which note key Razorpay's Shopify app uses (if any)
--                       is what the Phase 0 spike finds out, so every value is
--                       compared rather than guessing a key name.
--   phone_amount_window same phone, amount within ₹1, checkout touched in the
--                       window before the payment. Good, not perfect: two open
--                       checkouts for the same phone and amount resolve to the
--                       most recent one.
-- Completed checkouts are included on purpose: a failure followed by a
-- successful retry should still be linked, so the spike's match rate is honest.
-- Journeys decide separately whether to message (they cancel on completion).
WITH notes AS (
  SELECT value FROM jsonb_each_text(?::jsonb)
), by_notes AS (
  SELECT c.token, 'notes_ref'::text AS method, 1 AS rank, c.updated_at
    FROM checkouts c
   WHERE c.token IN (SELECT value FROM notes)
      OR c.cart_token IN (SELECT value FROM jsonb_each_text(?::jsonb))
), by_window AS (
  SELECT c.token, 'phone_amount_window'::text AS method, 2 AS rank, c.updated_at
    FROM checkouts c
   WHERE c.phone = ?
     AND abs(c.total_paise - ?) <= 100
     AND c.updated_at >= ?::timestamptz - make_interval(mins => ?)
     AND c.updated_at <= ?::timestamptz + interval '5 minutes'
)
SELECT token, method
  FROM (SELECT * FROM by_notes UNION ALL SELECT * FROM by_window) m
 ORDER BY rank, updated_at DESC
 LIMIT 1
