-- Consent ledger, newest first, with the state in force marked. Parameter 1: identity_id (twice).
SELECT c.channel::text AS channel, c.purpose::text AS purpose, c.state::text AS state, c.source,
       c.copy_version, c.occurred_at,
       (cc.occurred_at = c.occurred_at AND cc.state = c.state AND cc.source = c.source) AS in_force
  FROM consents c
  LEFT JOIN consent_current cc
         ON cc.identity_id = c.identity_id AND cc.channel = c.channel AND cc.purpose = c.purpose
 WHERE c.identity_id = ?
 ORDER BY c.occurred_at DESC, c.id DESC
 LIMIT 100
