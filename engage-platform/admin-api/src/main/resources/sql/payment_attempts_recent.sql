-- Recent attempts, newest first. No parameters. No contact details: the
-- customer screen (masked, audited reveal) is where a person is looked at.
SELECT gateway_payment_id, status, amount_paise, method, error_source, error_reason,
       match_method, (checkout_token IS NOT NULL) AS joined_to_checkout, received_at
  FROM payment_attempts
 ORDER BY received_at DESC
 LIMIT 100
