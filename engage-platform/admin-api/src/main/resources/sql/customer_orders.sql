-- Orders, newest first. Parameter 1: identity_id.
SELECT id, order_number, total_paise, financial_status, cancelled_at, refunded_paise, created_at
  FROM orders WHERE identity_id = ? ORDER BY created_at DESC LIMIT 50
