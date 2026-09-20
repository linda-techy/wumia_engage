-- Extract a Shopify orders/create payload.
-- Parameter 1: delivery_id (X-Shopify-Webhook-Id)
--
-- note_attributes carries the cart-drawer WhatsApp opt-in
-- (_engage_wa_optin / _engage_wa_copy / _engage_wa_at) into the order.
SELECT p->>'id'                                                         AS order_id,
       p->>'name'                                                       AS order_number,
       NULLIF(p->'customer'->>'id', '')                                 AS customer_id,
       NULLIF(p->'customer'->>'first_name', '')                         AS first_name,
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
                      NULLIF(p->>'contact_email', ''),
                      NULLIF(p->'customer'->>'email', '')))             AS email,
       p->>'total_price'                                                AS total_price,
       NULLIF(p->>'financial_status', '')                               AS financial_status,
       COALESCE(ARRAY(SELECT jsonb_array_elements_text(
                        CASE WHEN jsonb_typeof(p->'payment_gateway_names') = 'array'
                             THEN p->'payment_gateway_names' ELSE '[]'::jsonb END)),
                '{}')                                                   AS gateway_names,
       NULLIF(p->>'cart_token', '')                                     AS cart_token,
       NULLIF(p->>'checkout_token', '')                                 AS checkout_token,
       COALESCE((SELECT jsonb_object_agg(a->>'name', a->>'value')
                   FROM jsonb_array_elements(
                          CASE WHEN jsonb_typeof(p->'note_attributes') = 'array'
                               THEN p->'note_attributes' ELSE '[]'::jsonb END) a),
                '{}'::jsonb)::text                                       AS note_attributes_json,
       (p->>'created_at')::timestamptz                                  AS created_at,
       NULLIF(p->'shipping_address'->>'city', '')                       AS city,
       NULLIF(p->'shipping_address'->>'province_code', '')              AS state_code,
       NULLIF(p->'customer'->'email_marketing_consent'->>'state', '')   AS email_consent_state,
       -- When the shopper made that choice. Consent rows are stamped with this,
       -- not with processing time, so a late or replayed webhook can never
       -- override a newer choice (e.g. re-subscribe someone who unsubscribed).
       COALESCE((p->'customer'->'email_marketing_consent'->>'consent_updated_at')::timestamptz,
                (p->>'created_at')::timestamptz)                         AS email_consent_at
  FROM webhook_inbox i
 CROSS JOIN LATERAL (SELECT i.payload AS p) x
 WHERE i.source = 'shopify'
   AND i.delivery_id = ?
