-- V19: P6-T05 data the segment predicates read that nothing stored before.
--
-- order_lines: what each order contained (bought_product, bought_size,
--   bought_product_type). Written by ingest from order payloads.
-- products:    product type, tags and which option is the size, from
--   products/update. The order payload carries none of these.
-- Customer tags go into profiles.attrs.shopify_tags (ingest; no DDL).
--
-- Backfilled below from the payloads webhook_inbox still holds (7 days of
-- processed rows); older orders have no lines. Collection membership is in
-- no webhook payload: bought_collection waits for an Admin API sync.

CREATE TABLE products (
  product_id    TEXT PRIMARY KEY,
  handle        TEXT,
  title         TEXT,
  product_type  TEXT,                       -- lower case
  tags          TEXT[] NOT NULL DEFAULT '{}', -- lower case, trimmed
  size_position SMALLINT CHECK (size_position BETWEEN 1 AND 3),  -- option named Size, if any
  updated_at    TIMESTAMPTZ NOT NULL         -- Shopify's, so a late webhook cannot win
);
CREATE INDEX products_type_idx ON products (product_type);

CREATE TABLE order_lines (
  order_id      TEXT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  line_no       INT  NOT NULL,              -- position in the payload
  line_id       TEXT,
  product_id    TEXT,
  variant_id    TEXT,
  title         TEXT,
  variant_title TEXT,                       -- "M / Red": options joined by " / "
  sku           TEXT,
  quantity      INT NOT NULL DEFAULT 1,
  price_paise   BIGINT,
  PRIMARY KEY (order_id, line_no)
);
CREATE INDEX order_lines_product_idx ON order_lines (product_id);

/* ------------------------------- backfill ------------------------------- */

INSERT INTO products (product_id, handle, title, product_type, tags, size_position, updated_at)
SELECT DISTINCT ON (p->>'id')
       p->>'id', NULLIF(p->>'handle', ''), NULLIF(p->>'title', ''),
       NULLIF(lower(btrim(p->>'product_type')), ''),
       COALESCE(ARRAY(SELECT lower(btrim(t)) FROM unnest(string_to_array(p->>'tags', ',')) t
                       WHERE btrim(t) <> ''), '{}'),
       (SELECT (o->>'position')::smallint
          FROM jsonb_array_elements(CASE WHEN jsonb_typeof(p->'options') = 'array' THEN p->'options' ELSE '[]' END) o
         WHERE lower(btrim(o->>'name')) IN ('size', 'sizes') LIMIT 1),
       (p->>'updated_at')::timestamptz
  FROM webhook_inbox i CROSS JOIN LATERAL (SELECT i.payload AS p) x
 WHERE i.source = 'shopify' AND i.topic IN ('products/create', 'products/update')
   AND p->>'id' IS NOT NULL AND p->>'updated_at' IS NOT NULL
 ORDER BY p->>'id', (p->>'updated_at')::timestamptz DESC;

INSERT INTO order_lines (order_id, line_no, line_id, product_id, variant_id, title, variant_title, sku, quantity, price_paise)
SELECT o.id, e.n, e.l->>'id', NULLIF(e.l->>'product_id', ''), NULLIF(e.l->>'variant_id', ''),
       NULLIF(e.l->>'title', ''), NULLIF(e.l->>'variant_title', ''), NULLIF(e.l->>'sku', ''),
       COALESCE((e.l->>'quantity')::int, 1), round((e.l->>'price')::numeric * 100)::bigint
  FROM webhook_inbox i
  JOIN orders o ON o.id = i.payload->>'id'
 CROSS JOIN LATERAL jsonb_array_elements(
         CASE WHEN jsonb_typeof(i.payload->'line_items') = 'array' THEN i.payload->'line_items' ELSE '[]' END)
       WITH ORDINALITY AS e(l, n)
 WHERE i.source = 'shopify' AND i.topic = 'orders/create'
ON CONFLICT DO NOTHING;
