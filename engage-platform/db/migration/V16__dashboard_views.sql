-- V16: P6-T02 dashboard views. The admin dashboard reads these, never the raw
-- tables, so the numbers an operator sees during an incident are defined once,
-- here. Built before P4, so the planned P4–P7 migrations moved up by one.

-- Sends by creation time: the 24 h window below, and later the reports.
CREATE INDEX sends_created_idx ON sends (created_at);

-- The last 24 h by channel, category, status and policy reason. "Block
-- reasons, 24h" is the fastest signal that something is misconfigured.
CREATE VIEW dash_sends_24h AS
SELECT channel::text  AS channel,
       category::text AS category,
       status::text   AS status,
       decision->>'reason' AS reason,
       count(*)       AS sends
  FROM sends
 WHERE created_at > now() - interval '24 hours'
 GROUP BY 1, 2, 3, 4;

-- Today's spend (IST day, as the budget guard counts it) against every
-- budget.<channel>.<category>.daily_paise key, resolved as policy does:
-- the '*' version in force, else the key's default.
CREATE VIEW dash_spend_today AS
WITH today AS (SELECT (now() AT TIME ZONE 'Asia/Kolkata')::date AS day),
spent AS (
  SELECT l.channel::text AS channel, l.category::text AS category, l.messages, l.paise
    FROM spend_ledger l, today WHERE l.day = today.day
),
budgets AS (
  SELECT split_part(k.key, '.', 2) AS channel, split_part(k.key, '.', 3) AS category,
         (COALESCE(c.value, k.default_value) #>> '{}')::bigint AS budget_paise
    FROM config_keys k
    LEFT JOIN config_current c ON c.key = k.key AND c.selector = '*'
   WHERE k.key LIKE 'budget.%.%.daily_paise' AND NOT k.deprecated
)
SELECT COALESCE(s.channel, b.channel)   AS channel,
       COALESCE(s.category, b.category) AS category,
       COALESCE(s.messages, 0)          AS messages,
       COALESCE(s.paise, 0)             AS spent_paise,
       b.budget_paise
  FROM spent s FULL JOIN budgets b ON b.channel = s.channel AND b.category = s.category;

-- What we know about who can receive each channel. Campaigns pay only for
-- CAPABLE; a growing UNKNOWN share means order-tracking messages are not
-- reaching people.
CREATE VIEW dash_capability AS
SELECT channel::text AS channel, state, count(*) AS identities
  FROM channel_capability
 GROUP BY 1, 2;

-- Grants and withdrawals per IST day, channel, purpose and source, last 30
-- days: opt-in sources for WhatsApp health, and the opt-out trend (rising
-- opt-outs precede a quality-rating drop).
CREATE VIEW dash_consent_daily AS
SELECT (occurred_at AT TIME ZONE 'Asia/Kolkata')::date AS day_ist,
       channel::text AS channel, purpose::text AS purpose, state::text AS state, source,
       count(*) AS records
  FROM consents
 WHERE occurred_at > now() - interval '30 days'
 GROUP BY 1, 2, 3, 4, 5;

-- Per intent: runs entered and finished in the last 24 h, and live runs
-- whose next step is more than 15 minutes overdue (the worker is not
-- picking them up).
CREATE VIEW dash_journey_health AS
SELECT intent_key,
       count(*) FILTER (WHERE created_at > now() - interval '24 hours')                             AS entered_24h,
       count(*) FILTER (WHERE status = 'succeeded' AND updated_at > now() - interval '24 hours')    AS succeeded_24h,
       count(*) FILTER (WHERE status = 'exhausted' AND updated_at > now() - interval '24 hours')    AS exhausted_24h,
       count(*) FILTER (WHERE status = 'failed'    AND updated_at > now() - interval '24 hours')    AS failed_24h,
       count(*) FILTER (WHERE status IN ('active', 'waiting')
                          AND next_step_at < now() - interval '15 minutes')                         AS stalled
  FROM cascade_runs
 WHERE created_at > now() - interval '24 hours'
    OR updated_at > now() - interval '24 hours'
    OR status IN ('active', 'waiting')
 GROUP BY intent_key;

-- Push tokens by browser and health state (device_health, V11).
CREATE VIEW dash_push_devices AS
SELECT COALESCE(browser, 'unknown') AS browser, state, count(*) AS devices
  FROM device_health
 GROUP BY 1, 2;

-- WhatsApp templates by status and quality; the category mismatches have
-- their own view (wa_template_category_mismatch, V4).
CREATE VIEW dash_wa_templates AS
SELECT status, quality, count(*) AS templates
  FROM wa_templates
 GROUP BY 1, 2;
