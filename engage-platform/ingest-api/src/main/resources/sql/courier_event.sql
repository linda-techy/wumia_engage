-- Extract a courier aggregator webhook (Shiprocket, ADR-005).
-- Parameter 1: delivery_id. Field names are Shiprocket's; the adapter maps
-- status and time. The NDR reason is the latest scan's activity text.
SELECT NULLIF(btrim(p->>'awb'), '')                AS awb,
       NULLIF(p->>'courier_name', '')              AS courier_name,
       COALESCE(NULLIF(p->>'shipment_status', ''),
                NULLIF(p->>'current_status', ''))  AS status_label,
       COALESCE(p->>'shipment_status_id',
                p->>'current_status_id')           AS status_code,
       NULLIF(p->>'current_timestamp', '')         AS status_time,
       (SELECT s->>'activity'
          FROM jsonb_array_elements(CASE WHEN jsonb_typeof(p->'scans') = 'array'
                                         THEN p->'scans' ELSE '[]'::jsonb END) s
         ORDER BY s->>'date' DESC NULLS LAST LIMIT 1) AS last_activity,
       (SELECT s->>'date'
          FROM jsonb_array_elements(CASE WHEN jsonb_typeof(p->'scans') = 'array'
                                         THEN p->'scans' ELSE '[]'::jsonb END) s
         ORDER BY s->>'date' DESC NULLS LAST LIMIT 1) AS last_scan_time,
       jsonb_build_object('awb', p->'awb', 'courier_name', p->'courier_name',
                          'shipment_status', p->'shipment_status', 'shipment_status_id', p->'shipment_status_id',
                          'current_status', p->'current_status', 'current_timestamp', p->'current_timestamp',
                          'is_return', p->'is_return')::text AS summary
  FROM webhook_inbox i
 CROSS JOIN LATERAL (SELECT i.payload AS p) x
 WHERE i.source = 'courier'
   AND i.delivery_id = ?
