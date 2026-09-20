-- Extract a Shopify checkouts/create or checkouts/update payload.
-- Parameter 1: delivery_id (X-Shopify-Webhook-Id)
--
-- Contact phone and shipping phone are returned separately: the first is the
-- buyer's, the second may be a gift recipient's (V6 trust levels).
SELECT p->>'token'                                                      AS token,
       NULLIF(p->>'cart_token', '')                                     AS cart_token,
       NULLIF(p->'customer'->>'id', '')                                 AS customer_id,
       -- The BUYER's own number (contact field or their account). Trusted to
       -- absorb a soft identity during resolution (V6, trust "buyer").
       COALESCE(NULLIF(p->>'phone', ''),
                NULLIF(p->'customer'->>'phone', ''))                    AS contact_phone_raw,
       -- Possibly a gift RECIPIENT's number. Never merges identities (WEAK).
       -- Most Indian checkouts only collect phone here, so it is still used as
       -- the order's phone when no contact phone exists.
       COALESCE(NULLIF(p->'shipping_address'->>'phone', ''),
                NULLIF(p->'billing_address'->>'phone', ''))             AS shipping_phone_raw,
       lower(COALESCE(NULLIF(p->>'email', ''),
                      NULLIF(p->'customer'->>'email', '')))             AS email,
       NULLIF(p->>'total_price', '')                                    AS total_price,
       NULLIF(p->>'abandoned_checkout_url', '')                         AS recovery_url,
       (p->>'completed_at')::timestamptz                                AS completed_at,
       COALESCE((p->>'updated_at')::timestamptz, now())                 AS updated_at,
       COALESCE((SELECT jsonb_object_agg(a->>'name', a->>'value')
                   FROM jsonb_array_elements(
                          CASE WHEN jsonb_typeof(p->'note_attributes') = 'array'
                               THEN p->'note_attributes' ELSE '[]'::jsonb END) a),
                '{}'::jsonb)::text                                       AS note_attributes_json
  FROM webhook_inbox i
 CROSS JOIN LATERAL (SELECT i.payload AS p) x
 WHERE i.source = 'shopify'
   AND i.delivery_id = ?
