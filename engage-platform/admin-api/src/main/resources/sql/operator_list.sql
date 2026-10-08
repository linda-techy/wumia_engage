-- Every operator with roles. No parameters. Never the password hash or MFA secret.
SELECT o.id, o.email, o.full_name, o.status, (o.mfa_enrolled_at IS NOT NULL) AS mfa_enrolled,
       o.last_login_at, o.created_at,
       COALESCE(array_agg(r.role ORDER BY r.role) FILTER (WHERE r.role IS NOT NULL), '{}') AS roles
  FROM operators o
  LEFT JOIN operator_roles r ON r.operator_id = o.id
 GROUP BY o.id
 ORDER BY o.created_at
