-- Operator with roles, by id. Parameter 1: operator id.
SELECT o.id, o.email, o.full_name, o.password_hash, o.mfa_secret_enc,
       (o.mfa_enrolled_at IS NOT NULL) AS mfa_enrolled, o.status,
       o.failed_logins, o.locked_until, o.password_changed_at,
       COALESCE(array_agg(r.role ORDER BY r.role) FILTER (WHERE r.role IS NOT NULL), '{}') AS roles
  FROM operators o
  LEFT JOIN operator_roles r ON r.operator_id = o.id
 WHERE o.id = ?
 GROUP BY o.id
