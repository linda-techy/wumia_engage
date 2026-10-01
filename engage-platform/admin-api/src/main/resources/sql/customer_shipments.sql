-- Shipments of this person's orders, newest first. Parameter 1: identity_id.
SELECT s.order_id, s.awb, s.carrier, s.status, s.status_at, s.ndr_attempts, s.ndr_reason
  FROM shipments s JOIN orders o ON o.id = s.order_id
 WHERE o.identity_id = ? ORDER BY s.created_at DESC LIMIT 50
