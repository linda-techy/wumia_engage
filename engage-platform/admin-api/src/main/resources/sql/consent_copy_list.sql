-- Registered wordings with how many grants each one carries. No parameters.
SELECT v.version, v.channel::text AS channel, v.surface, v.text,
       array_to_string(v.purposes, ',') AS purposes, v.created_at,
       (SELECT count(*) FROM consents c WHERE c.copy_version = v.version AND c.state = 'granted') AS grants
  FROM consent_copy_versions v
 ORDER BY v.created_at DESC, v.version
