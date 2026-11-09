-- Extract a Razorpay Magic Checkout abandoned-cart webhook
-- (https://razorpay.com/docs/payments/magic-checkout/abandoned-cart/).
-- Parameter 1: delivery_id
--
-- The payload has no timestamp: received_at stands in for "when they left".
-- Amounts are returned raw; the handler decides rupees or paise.
-- consent_raw reads the fields Magic puts on orders, in case the abandoned
-- payload carries them too. The documented payload does not.
SELECT NULLIF(btrim(p->>'token'), '')                                   AS token,
       NULLIF(btrim(p->>'cart_token'), '')                              AS cart_token,
       NULLIF(btrim(p->>'phone'), '')                                   AS contact_phone_raw,
       COALESCE(NULLIF(btrim(p#>>'{customer,Shipping_address,Phone}'), ''),
                NULLIF(btrim(p#>>'{customer,shipping_address,phone}'), '')) AS shipping_phone_raw,
       lower(COALESCE(NULLIF(btrim(p->>'email'), ''),
                      NULLIF(btrim(p#>>'{customer,email}'), '')))       AS email,
       COALESCE(NULLIF(btrim(p#>>'{customer,first_name}'), ''),
                NULLIF(btrim(p#>>'{customer,Shipping_address,First_name}'), '')) AS first_name,
       NULLIF(btrim(p->>'line_items_total'), '')                        AS line_items_total,
       NULLIF(btrim(p->>'abandoned_checkout_url'), '')                  AS checkout_url,
       COALESCE((SELECT sum(COALESCE(NULLIF(li->>'quantity', '')::int, 1))
                   FROM jsonb_array_elements(lines) li), 0)::int        AS item_count,
       (SELECT string_agg((li->>'variant_id') || ':' || COALESCE(NULLIF(li->>'quantity', ''), '1'), ',' ORDER BY n)
          FROM jsonb_array_elements(lines) WITH ORDINALITY AS t(li, n)
         WHERE li->>'variant_id' ~ '^\d+$')                             AS permalink_items,
       COALESCE((SELECT jsonb_agg(jsonb_build_object(
                          'variant_id', li->>'variant_id',
                          'product_id', li->>'product_id',
                          'title',      COALESCE(NULLIF(li->>'title', ''), li->>'name'),
                          'size',       li->>'variant_title',
                          'qty',        COALESCE(NULLIF(li->>'quantity', '')::int, 1),
                          'price_raw',  li->>'price') ORDER BY n)
                   FROM jsonb_array_elements(lines) WITH ORDINALITY AS t(li, n)), '[]'::jsonb)::text AS lines_json,
       lower(COALESCE(p->>'whatsapp_sms_consent', p#>>'{customer,whatsapp_sms_consent}',
                      p->>'whatsapp_consent'))                          AS consent_raw,
       (SELECT string_agg(k, ',' ORDER BY k) FROM jsonb_object_keys(p) k) AS payload_keys,
       i.received_at                                                    AS received_at
  FROM webhook_inbox i
 CROSS JOIN LATERAL (SELECT i.payload AS p) x
 CROSS JOIN LATERAL (SELECT CASE WHEN jsonb_typeof(p->'line_items') = 'array'
                                 THEN p->'line_items' ELSE '[]'::jsonb END AS lines) l
 WHERE i.source = 'razorpay_magic'
   AND i.delivery_id = ?
