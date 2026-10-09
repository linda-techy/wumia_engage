-- The order's line items into order_lines (segments: bought_product, bought_size,
-- bought_product_type). Every order topic carries the full order, so whichever
-- arrives first writes them; the rest conflict and do nothing.
-- Parameters: 1 order_id, 2 delivery_id (X-Shopify-Webhook-Id)
INSERT INTO order_lines (order_id, line_no, line_id, product_id, variant_id, title, variant_title, sku, quantity, price_paise)
SELECT ?, e.n, e.l->>'id', NULLIF(e.l->>'product_id', ''), NULLIF(e.l->>'variant_id', ''),
       NULLIF(e.l->>'title', ''), NULLIF(e.l->>'variant_title', ''), NULLIF(e.l->>'sku', ''),
       COALESCE((e.l->>'quantity')::int, 1), round((e.l->>'price')::numeric * 100)::bigint
  FROM webhook_inbox i
 CROSS JOIN LATERAL jsonb_array_elements(
         CASE WHEN jsonb_typeof(i.payload->'line_items') = 'array' THEN i.payload->'line_items' ELSE '[]' END)
       WITH ORDINALITY AS e(l, n)
 WHERE i.source = 'shopify' AND i.delivery_id = ?
ON CONFLICT DO NOTHING
