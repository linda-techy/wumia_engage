-- Profile. Parameter 1: identity_id.
SELECT i.created_at, p.attrs::text AS attrs, p.computed::text AS computed
  FROM identities i LEFT JOIN profiles p ON p.identity_id = i.id
 WHERE i.id = ?
