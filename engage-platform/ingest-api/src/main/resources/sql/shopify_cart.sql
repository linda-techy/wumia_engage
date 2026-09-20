-- Extract a Shopify carts/create or carts/update payload.
-- Parameter 1: delivery_id (X-Shopify-Webhook-Id)
--
-- Totals are summed from line_price (falling back to price * quantity) and
-- converted to paise here, once.
SELECT COALESCE(NULLIF(p->>'token', ''), p->>'id')                      AS cart_token_raw,
       COALESCE(sum((li->>'quantity')::int), 0)::int                    AS item_count,
       COALESCE(sum(round(COALESCE((li->>'line_price')::numeric,
                                   (li->>'price')::numeric * (li->>'quantity')::int) * 100)), 0)::bigint
                                                                         AS total_paise,
       COALESCE(jsonb_agg(jsonb_build_object(
                  'variant_id',  li->>'variant_id',
                  'title',       li->>'title',
                  'size',        li->>'variant_title',
                  'qty',         (li->>'quantity')::int,
                  'price_paise', round((li->>'price')::numeric * 100)::bigint)
                ) FILTER (WHERE li IS NOT NULL), '[]'::jsonb)::text      AS lines_json
  FROM webhook_inbox i
  LEFT JOIN LATERAL jsonb_array_elements(
         CASE WHEN jsonb_typeof(i.payload->'line_items') = 'array'
              THEN i.payload->'line_items' ELSE '[]'::jsonb END) li ON true
 CROSS JOIN LATERAL (SELECT i.payload AS p) x
 WHERE i.source = 'shopify'
   AND i.delivery_id = ?
 GROUP BY p
