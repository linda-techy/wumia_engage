-- V59: Meta's marketing message experiment (error 130472, owner 2026-10-31).
-- A number in it gets no business's marketing templates for now, so Engage
-- stops marketing WhatsApp to it for 30 days (CapabilityService) and the
-- journey moves straight to push. The backoff now holds marketing only
-- (PolicyEngine): order updates and replies always go.
--
-- The 130472 failures before this release get the same 30 days.

INSERT INTO channel_capability (identity_id, channel, backoff_until)
SELECT s.identity_id, 'whatsapp', max(s.created_at) + interval '30 days'
  FROM sends s
 WHERE s.channel = 'whatsapp' AND s.failed_reason = 'wa_130472'
 GROUP BY s.identity_id
ON CONFLICT (identity_id, channel) DO UPDATE
   SET backoff_until = GREATEST(channel_capability.backoff_until, EXCLUDED.backoff_until);
