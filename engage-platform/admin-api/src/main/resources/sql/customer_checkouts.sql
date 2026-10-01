-- Checkouts, newest first. Parameter 1: identity_id.
SELECT token, total_paise, last_step, completed_at, updated_at
  FROM checkouts WHERE identity_id = ? ORDER BY updated_at DESC LIMIT 50
