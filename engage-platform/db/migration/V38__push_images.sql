-- V38: pictures in push notifications.
--
-- products.image_url: the product's main photo from products/update, on
-- Shopify's CDN (public). Journey pushes show it as the large image.
ALTER TABLE products ADD COLUMN image_url TEXT CHECK (image_url LIKE 'https://%');

-- Posters uploaded in the console for campaign pushes, served publicly by
-- ingest-api at /media/<id>.<ext>. The id is the SHA-256 of the bytes, so an
-- upload is immutable and the same poster uploaded twice is stored once.
CREATE TABLE media (
  id           TEXT PRIMARY KEY CHECK (id ~ '^[0-9a-f]{64}$'),
  content_type TEXT NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
  bytes        BYTEA NOT NULL,
  width        INT NOT NULL CHECK (width > 0),
  height       INT NOT NULL CHECK (height > 0),
  size_bytes   INT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 1048576),
  name         TEXT,
  created_by   UUID REFERENCES operators(id) ON DELETE SET NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX media_created_idx ON media (created_at DESC);
