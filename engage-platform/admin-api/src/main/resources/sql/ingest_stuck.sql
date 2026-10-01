-- Items still unprocessed after at least one failed attempt, oldest first. No parameters.
SELECT source, delivery_id, topic, attempts, last_error, received_at, next_attempt_at
  FROM webhook_inbox
 WHERE processed_at IS NULL AND last_error IS NOT NULL
 ORDER BY received_at
 LIMIT 50
