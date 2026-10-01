-- Messages sent or blocked for this person, newest first. Parameter 1: identity_id.
SELECT id, channel::text AS channel, template_key, intent_key, status::text AS status,
       decision->>'reason' AS block_reason, failed_reason, created_at, sent_at, delivered_at, clicked_at
  FROM sends WHERE identity_id = ? ORDER BY created_at DESC LIMIT 50
