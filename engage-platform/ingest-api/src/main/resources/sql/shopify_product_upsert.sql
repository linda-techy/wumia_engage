-- products/update → products (segments: product type, size option). Shopify's
-- updated_at decides: a late or replayed webhook never overwrites a newer one.
-- Tags and type are lower-cased so segment values match regardless of case.
-- Parameter 1: delivery_id (X-Shopify-Webhook-Id)
INSERT INTO products (product_id, handle, title, product_type, tags, size_position, updated_at)
SELECT p->>'id', NULLIF(p->>'handle', ''), NULLIF(p->>'title', ''),
       NULLIF(lower(btrim(p->>'product_type')), ''),
       COALESCE(ARRAY(SELECT lower(btrim(t)) FROM unnest(string_to_array(p->>'tags', ',')) t
                       WHERE btrim(t) <> ''), '{}'),
       (SELECT (o->>'position')::smallint
          FROM jsonb_array_elements(CASE WHEN jsonb_typeof(p->'options') = 'array' THEN p->'options' ELSE '[]' END) o
         WHERE lower(btrim(o->>'name')) IN ('size', 'sizes') LIMIT 1),
       (p->>'updated_at')::timestamptz
  FROM webhook_inbox i CROSS JOIN LATERAL (SELECT i.payload AS p) x
 WHERE i.source = 'shopify' AND i.delivery_id = ?
   AND p->>'id' IS NOT NULL AND p->>'updated_at' IS NOT NULL
ON CONFLICT (product_id) DO UPDATE
   SET handle = EXCLUDED.handle, title = EXCLUDED.title, product_type = EXCLUDED.product_type,
       tags = EXCLUDED.tags, size_position = EXCLUDED.size_position, updated_at = EXCLUDED.updated_at
 WHERE products.updated_at <= EXCLUDED.updated_at
