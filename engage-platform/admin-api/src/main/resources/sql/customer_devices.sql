-- Push devices (no token), with the device_health state. Parameter 1: identity_id.
SELECT d.id, d.platform, d.browser, h.state, d.deactivated_reason, d.permission_source,
       d.consent_copy_ver, d.created_at, d.last_refreshed_at, d.last_clicked_at
  FROM devices d JOIN device_health h ON h.id = d.id
 WHERE d.identity_id = ? ORDER BY d.created_at DESC LIMIT 20
