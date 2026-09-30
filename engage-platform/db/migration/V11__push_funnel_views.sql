-- P2 views (phase-2 §6 and §9). Planned as V10; the pixel index took V10.

-- The soft-ask funnel per IST day: tells a traffic problem from a prompt problem.
CREATE VIEW push_prompt_funnel AS
SELECT date_trunc('day', created_at AT TIME ZONE 'Asia/Kolkata')::date AS day_ist,
       surface, platform,
       count(*) FILTER (WHERE step = 'soft_shown')      AS soft_shown,
       count(*) FILTER (WHERE step = 'soft_accepted')   AS soft_accepted,
       count(*) FILTER (WHERE step = 'native_granted')  AS native_granted,
       count(*) FILTER (WHERE step = 'token_minted')    AS token_minted,
       count(*) FILTER (WHERE step = 'ios_redirected_to_whatsapp') AS ios_redirected
  FROM push_prompt_events
 GROUP BY 1, 2, 3;

-- Stale (30 days without a refresh) is a view, not a state change: stale
-- tokens stay active, because back-in-stock still reaches them.
CREATE VIEW device_health AS
SELECT id, identity_id, platform, browser, active, deactivated_reason,
       CASE WHEN NOT active THEN 'inactive'
            WHEN last_refreshed_at < now() - interval '30 days' THEN 'stale'
            ELSE 'active' END AS state
  FROM devices;
