-- Extract a Shopify fulfillments/create or fulfillments/update payload.
-- Parameter 1: delivery_id. The AWB is the tracking number; Shopify's
-- shipment_status carries courier states when the shipping app posts them.
SELECT p->>'id'                                          AS fulfillment_id,
       p->>'order_id'                                    AS order_id,
       NULLIF(p->>'status', '')                          AS status,
       NULLIF(p->>'shipment_status', '')                 AS shipment_status,
       NULLIF(btrim(COALESCE(p->>'tracking_number', p#>>'{tracking_numbers,0}')), '') AS awb,
       NULLIF(p->>'tracking_company', '')                AS carrier,
       NULLIF(COALESCE(p->>'tracking_url', p#>>'{tracking_urls,0}'), '') AS tracking_url,
       (NULLIF(p->>'created_at', ''))::timestamptz       AS created_at,
       (NULLIF(p->>'updated_at', ''))::timestamptz       AS updated_at
  FROM webhook_inbox i
 CROSS JOIN LATERAL (SELECT i.payload AS p) x
 WHERE i.source = 'shopify'
   AND i.delivery_id = ?
