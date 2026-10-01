-- Razorpay payment attempts, newest first. Parameter 1: identity_id.
SELECT gateway_payment_id, status, amount_paise, method, error_reason, match_method, received_at
  FROM payment_attempts WHERE identity_id = ? ORDER BY received_at DESC LIMIT 50
