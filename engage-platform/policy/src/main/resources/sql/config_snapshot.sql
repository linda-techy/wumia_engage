-- The config in force now, recorded as a config_snapshots row (reused when
-- identical), returned with the resolved values in one statement.
--
-- Resolved = every config_current row, plus config_keys.default_value as the
-- '*' value of any key with no '*' version. The fingerprint covers values as
-- well as version ids, so changing a default in a migration makes a new
-- snapshot instead of silently reusing one that recorded the old default.
WITH r AS (
  SELECT c.key, c.selector, c.id, c.value
    FROM config_current c
  UNION ALL
  SELECT k.key, '*', NULL, k.default_value
    FROM config_keys k
   WHERE k.default_value IS NOT NULL
     AND NOT EXISTS (SELECT 1 FROM config_current c WHERE c.key = k.key AND c.selector = '*')
),
agg AS (
  SELECT sha256(convert_to(
           string_agg(r.key || '|' || r.selector || '|' || COALESCE(r.id::text, 'default') || '|' || r.value::text,
                      E'\n' ORDER BY r.key, r.selector), 'UTF8')) AS fingerprint,
         COALESCE(array_agg(r.id ORDER BY r.id) FILTER (WHERE r.id IS NOT NULL), '{}') AS version_ids,
         COALESCE(jsonb_object_agg(r.key || '|' || r.selector, r.value), '{}') AS resolved
    FROM r
),
ins AS (
  INSERT INTO config_snapshots (fingerprint, version_ids, resolved)
  SELECT fingerprint, version_ids, resolved FROM agg
  ON CONFLICT (fingerprint) DO UPDATE SET fingerprint = EXCLUDED.fingerprint
  RETURNING id
)
SELECT ins.id AS snapshot_id, r.key, r.selector, r.value #>> '{}' AS val
  FROM ins LEFT JOIN r ON true
