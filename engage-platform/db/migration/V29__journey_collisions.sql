-- V29: journeys collide by priority (P5-T01, phase-5 §5). A step is blocked
-- HIGHER_PRIORITY_ACTIVE while the same person has a more important journey
-- (priority 2 or 3) live, or ended less than suppress_hours ago. Order
-- updates (priority 1) never block and are never blocked.

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('journey.collision.suppress_hours', 'GLOBAL', 'INT', 'Quiet time after a more important journey (h)',
  'After a checkout or back-in-stock journey ends, cart, browse and win-back messages wait this long. Order updates are never held.', 'GUARDED', 107,
  '{"type":"integer","minimum":0,"maximum":168}')
ON CONFLICT (key) DO NOTHING;
UPDATE config_keys SET default_value = '12'::jsonb
 WHERE key = 'journey.collision.suppress_hours' AND default_value IS NULL;

-- The check also reads runs that ended recently, which the live-only index does not cover.
CREATE INDEX cascade_runs_identity_recent_idx ON cascade_runs (identity_id, updated_at DESC);
