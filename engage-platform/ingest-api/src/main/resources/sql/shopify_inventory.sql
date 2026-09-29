-- Extract a Shopify inventory_levels/update payload.
-- Parameter 1: delivery_id (X-Shopify-Webhook-Id)
--
-- available is NULL when the item does not track inventory; the handler skips those.
SELECT p->>'inventory_item_id'              AS inventory_item_id,
       p->>'location_id'                    AS location_id,
       (p->>'available')::int               AS available,
       (p->>'updated_at')::timestamptz      AS updated_at
  FROM webhook_inbox i
 CROSS JOIN LATERAL (SELECT i.payload AS p) x
 WHERE i.source = 'shopify'
   AND i.delivery_id = ?
