-- All keys for one identity. Parameter 1: identity_id.
SELECT kind::text AS kind, value, verified, first_seen, last_seen
  FROM identity_keys WHERE identity_id = ? ORDER BY kind, value
