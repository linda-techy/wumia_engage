-- Exact identity lookup. Parameter 1: lower-cased email or normalised phone.
-- Deliberately no LIKE and no partial match: the console must not be able to
-- enumerate the customer base. Two rows = ambiguous: the controller answers 404.
SELECT DISTINCT k.identity_id
  FROM identity_keys k
  JOIN identities i ON i.id = k.identity_id AND i.merged_into IS NULL
 WHERE k.kind IN ('email', 'phone')
   AND k.value = ?
 LIMIT 2
