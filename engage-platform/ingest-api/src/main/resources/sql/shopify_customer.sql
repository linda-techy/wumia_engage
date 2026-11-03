-- Extract a Shopify customers/create or customers/update payload.
-- Parameter 1: delivery_id (X-Shopify-Webhook-Id)
--
-- email_marketing_consent is Shopify's native "Email me with news and offers".
-- It becomes the email marketing grant in the consent ledger. sms_marketing_consent
-- is Shopify's phone checkbox: once relabelled to name WhatsApp and SMS (V42,
-- SHOPIFY_PHONE_MARKETING_SINCE) it grants or withdraws both; before that, nothing.
-- whatsapp_marketing_consent is Shopify's WhatsApp setting on the phone number;
-- it grants or withdraws WhatsApp marketing. orders_count / last_order_at /
-- total_spent are Shopify's lifetime history, older than Engage's orders table
-- (sent by the shopify-customers-sync command; absent from most webhooks).
SELECT p->>'id'                                                  AS customer_id,
       lower(NULLIF(p->>'email', ''))                            AS email,
       NULLIF(p->>'phone', '')                                   AS phone_raw,
       NULLIF(p->>'first_name', '')                              AS first_name,
       p->>'tags'                                                AS tags,   -- "vip, ethnic"
       NULLIF(p->'email_marketing_consent'->>'state', '')        AS email_consent_state,
       NULLIF(p->'sms_marketing_consent'->>'state', '')          AS sms_consent_state,
       NULLIF(lower(p->'whatsapp_marketing_consent'->>'state'), '') AS wa_consent_state,
       NULLIF(p->'whatsapp_marketing_consent'->>'consent_collected_from', '') AS wa_consent_collected_from,
       COALESCE((p->'whatsapp_marketing_consent'->>'consent_updated_at')::timestamptz,
                (p->>'updated_at')::timestamptz,
                now())                                           AS wa_consent_at,
       CASE WHEN p->>'orders_count' ~ '^[0-9]+$' THEN (p->>'orders_count')::int END AS orders_count,
       (NULLIF(p->>'last_order_at', ''))::timestamptz            AS last_order_at,
       CASE WHEN p->>'total_spent' ~ '^[0-9]+(\.[0-9]+){0,1}$'
            THEN round((p->>'total_spent')::numeric * 100)::bigint END AS total_spent_paise,
       NULLIF(p#>>'{default_address,city}', '')                  AS city,
       NULLIF(p#>>'{default_address,province_code}', '')         AS state_code,
       -- The time of the choice, not of processing (see shopify_order.sql).
       COALESCE((p->'email_marketing_consent'->>'consent_updated_at')::timestamptz,
                (p->>'updated_at')::timestamptz,
                now())                                           AS email_consent_at,
       COALESCE((p->'sms_marketing_consent'->>'consent_updated_at')::timestamptz,
                (p->>'updated_at')::timestamptz,
                now())                                           AS sms_consent_at
  FROM webhook_inbox i
 CROSS JOIN LATERAL (SELECT i.payload AS p) x
 WHERE i.source = 'shopify'
   AND i.delivery_id = ?
