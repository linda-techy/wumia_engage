-- Everything the policy engine needs about one person, in one round trip.
-- Params: identity id, now, stale-before (now - push.campaign_stale_days).
-- Devices come back as parallel arrays in id order.
WITH p AS (SELECT CAST(? AS uuid) AS id, CAST(? AS timestamptz) AS now, CAST(? AS timestamptz) AS stale_before)
SELECT
  ARRAY(SELECT cc.channel::text || ':' || cc.purpose::text
          FROM consent_current cc WHERE cc.identity_id = p.id AND cc.state = 'granted') AS grants,
  ARRAY(SELECT s.channel::text || ':' || s.reason
          FROM suppressions s
         WHERE s.identity_id = p.id AND (s.until IS NULL OR s.until > p.now)
         ORDER BY s.created_at) AS suppressions,
  d.ids AS device_ids, d.tokens AS device_tokens, d.platforms AS device_platforms, d.stale AS device_stale,
  ARRAY(SELECT cap.channel::text || ':' || cap.state
          FROM channel_capability cap WHERE cap.identity_id = p.id) AS capability,
  (SELECT cap.backoff_until FROM channel_capability cap
    WHERE cap.identity_id = p.id AND cap.channel = 'whatsapp') AS wa_backoff_until,
  pr.wa_window_until,
  pr.attrs->>'locale' AS locale,
  (SELECT k.value FROM identity_keys k WHERE k.identity_id = p.id AND k.kind = 'phone'
    ORDER BY k.verified DESC, k.last_seen DESC LIMIT 1) AS phone,
  (SELECT k.value FROM identity_keys k WHERE k.identity_id = p.id AND k.kind = 'email'
    ORDER BY k.verified DESC, k.last_seen DESC LIMIT 1) AS email
FROM p
LEFT JOIN profiles pr ON pr.identity_id = p.id
CROSS JOIN LATERAL (
  SELECT array_agg(dv.id ORDER BY dv.id)                                   AS ids,
         array_agg(dv.fcm_token ORDER BY dv.id)                            AS tokens,
         array_agg(dv.platform ORDER BY dv.id)                             AS platforms,
         array_agg(dv.last_refreshed_at < p.stale_before ORDER BY dv.id)   AS stale
    FROM devices dv WHERE dv.identity_id = p.id AND dv.active
) d
