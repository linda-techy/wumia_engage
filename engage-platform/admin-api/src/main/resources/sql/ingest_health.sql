-- Inbox health by source and topic. No parameters.
-- dead = gave up after 10 attempts (InboxRepository.MAX_ATTEMPTS); needs a human.
SELECT source,
       topic,
       count(*)                                                                 AS total,
       count(*) FILTER (WHERE processed_at IS NULL)                             AS pending,
       count(*) FILTER (WHERE processed_at IS NULL AND last_error IS NOT NULL)  AS failed,
       count(*) FILTER (WHERE processed_at IS NULL AND attempts >= 10)          AS dead,
       COALESCE(EXTRACT(EPOCH FROM (now() - min(received_at)
                 FILTER (WHERE processed_at IS NULL)))::bigint, 0)              AS oldest_pending_age_seconds,
       max(received_at)                                                         AS last_received_at
  FROM webhook_inbox
 GROUP BY source, topic
 ORDER BY source, topic
