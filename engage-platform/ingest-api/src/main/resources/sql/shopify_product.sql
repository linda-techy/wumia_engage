-- Extract a Shopify products/update payload: the product, then its variants
-- as parallel arrays in payload order.
-- Parameter 1: delivery_id (X-Shopify-Webhook-Id)
--
-- Prices stay decimal strings here; the handler converts them with
-- Paise.ofRupees, which refuses more than two decimals.
SELECT p->>'id'                                    AS product_id,
       NULLIF(p->>'title', '')                     AS product_title,
       NULLIF(p->>'handle', '')                    AS product_handle,
       (p->>'updated_at')::timestamptz             AS updated_at,
       ARRAY(SELECT v->>'id'    FROM jsonb_array_elements(vs.list) WITH ORDINALITY AS e(v, n) ORDER BY n) AS variant_ids,
       ARRAY(SELECT v->>'title' FROM jsonb_array_elements(vs.list) WITH ORDINALITY AS e(v, n) ORDER BY n) AS variant_titles,
       ARRAY(SELECT v->>'price' FROM jsonb_array_elements(vs.list) WITH ORDINALITY AS e(v, n) ORDER BY n) AS prices
  FROM webhook_inbox i
 CROSS JOIN LATERAL (SELECT i.payload AS p) x
 CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(p->'variants') = 'array'
                                 THEN p->'variants' ELSE '[]'::jsonb END AS list) vs
 WHERE i.source = 'shopify'
   AND i.delivery_id = ?
