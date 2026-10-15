-- V41: which message an order is credited to, for the Analytics reports.
--
-- Last touch, two windows, both settings:
--   click: the latest message the customer clicked in the attribution.click_days
--          before the order (push clicks are tracked);
--   view:  otherwise the latest message delivered or read in the
--          attribution.view_hours before it (WhatsApp buttons are not tracked,
--          so a read WhatsApp is credited this way).
-- Messages that only inform about an order already placed (order updates,
-- delivery acknowledgements, the STOP confirmation) are never credited.
-- Lift against the holdout (Reports) stays the honest measure of what a
-- journey adds; this is the familiar "revenue from messages" view.

ALTER TABLE conversions
  ADD COLUMN attribution TEXT CHECK (attribution IN ('click', 'view')),
  ADD CONSTRAINT conversions_attribution_needs_send CHECK ((attribution IS NULL) = (last_send_id IS NULL));
CREATE INDEX conversions_occurred_idx ON conversions (occurred_at);
CREATE INDEX conversions_send_idx ON conversions (last_send_id) WHERE last_send_id IS NOT NULL;

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('attribution.click_days', 'GLOBAL', 'INT', 'Revenue credit: after a click (days)',
  'An order is credited to the latest message clicked within this many days before it.', 'GUARDED', 70,
  '{"type":"integer","minimum":1,"maximum":30}'),
 ('attribution.view_hours', 'GLOBAL', 'INT', 'Revenue credit: after a delivered or read message (hours)',
  'Without a click, an order is credited to the latest message delivered or read within this many hours. 0 = clicks only.',
  'GUARDED', 71, '{"type":"integer","minimum":0,"maximum":72}')
ON CONFLICT (key) DO NOTHING;
UPDATE config_keys SET default_value = '7'::jsonb WHERE key = 'attribution.click_days' AND default_value IS NULL;
UPDATE config_keys SET default_value = '24'::jsonb WHERE key = 'attribution.view_hours' AND default_value IS NULL;

-- A global integer setting as it stands now.
CREATE OR REPLACE FUNCTION config_int(p_key TEXT) RETURNS INT
LANGUAGE sql STABLE AS $$
  SELECT COALESCE((SELECT value FROM config_current WHERE key = p_key AND selector = '*'),
                  (SELECT default_value FROM config_keys WHERE key = p_key))::text::int
$$;

-- Credits one order. Idempotent: an order already credited keeps its credit.
CREATE OR REPLACE FUNCTION attribute_conversion(p_order_id TEXT) RETURNS TEXT
LANGUAGE plpgsql AS $$
DECLARE
  v_identity UUID;
  v_at       TIMESTAMPTZ;
  v_send     BIGINT;
  v_kind     TEXT;
  c_informational CONSTANT TEXT[] := ARRAY['order_tracking', 'delivery_ack', 'wa_stop_confirm'];
BEGIN
  SELECT identity_id, occurred_at INTO v_identity, v_at
    FROM conversions WHERE order_id = p_order_id AND last_send_id IS NULL;
  IF v_identity IS NULL THEN RETURN NULL; END IF;

  SELECT s.id INTO v_send FROM sends s
   WHERE s.identity_id = v_identity
     AND s.clicked_at IS NOT NULL
     AND s.clicked_at <= v_at AND s.clicked_at > v_at - make_interval(days => config_int('attribution.click_days'))
     AND NOT (COALESCE(s.intent_key, '') = ANY (c_informational))
   ORDER BY s.clicked_at DESC LIMIT 1;
  IF v_send IS NOT NULL THEN
    v_kind := 'click';
  ELSIF config_int('attribution.view_hours') > 0 THEN
    SELECT s.id INTO v_send FROM sends s
     WHERE s.identity_id = v_identity
       AND COALESCE(s.read_at, s.delivered_at) IS NOT NULL
       AND COALESCE(s.read_at, s.delivered_at) <= v_at
       AND COALESCE(s.read_at, s.delivered_at) > v_at - make_interval(hours => config_int('attribution.view_hours'))
       AND NOT (COALESCE(s.intent_key, '') = ANY (c_informational))
     ORDER BY COALESCE(s.read_at, s.delivered_at) DESC LIMIT 1;
    IF v_send IS NOT NULL THEN v_kind := 'view'; END IF;
  END IF;

  IF v_send IS NOT NULL THEN
    UPDATE conversions SET last_send_id = v_send, attribution = v_kind WHERE order_id = p_order_id;
  END IF;
  RETURN v_kind;
END;
$$;

-- Orders placed before this release.
SELECT attribute_conversion(order_id) FROM conversions WHERE last_send_id IS NULL;
