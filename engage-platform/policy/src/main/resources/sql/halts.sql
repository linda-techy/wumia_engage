-- Kill switches, uncached. Params: channel label, journey key (may be NULL).
-- Exact selector beats '*'; no version falls back to config_keys.default_value.
WITH p AS (SELECT CAST(? AS text) AS channel, CAST(? AS text) AS journey),
v AS (
  SELECT DISTINCT ON (c.key) c.key, c.value
    FROM config_current c, p
   WHERE (c.key = 'halt.channel'   AND c.selector IN (p.channel, '*'))
      OR (c.key = 'halt.marketing' AND c.selector = '*')
      OR (c.key = 'halt.journey'   AND c.selector IN (p.journey, '*'))
   ORDER BY c.key, c.selector = '*'
),
e AS (
  SELECT k.key, COALESCE(v.value, k.default_value) = 'true'::jsonb AS halted
    FROM config_keys k LEFT JOIN v ON v.key = k.key
   WHERE k.key IN ('halt.channel', 'halt.marketing', 'halt.journey')
)
SELECT COALESCE(bool_or(halted) FILTER (WHERE key = 'halt.channel'),   false) AS channel_halted,
       COALESCE(bool_or(halted) FILTER (WHERE key = 'halt.marketing'), false) AS marketing_halted,
       COALESCE(bool_or(halted) FILTER (WHERE key = 'halt.journey'),   false) AS journey_halted
  FROM e
