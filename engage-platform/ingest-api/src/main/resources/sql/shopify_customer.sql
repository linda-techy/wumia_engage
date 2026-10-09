-- Extract a Shopify customers/create or customers/update payload.
-- Parameter 1: delivery_id (X-Shopify-Webhook-Id)
--
-- email_marketing_consent is Shopify's native "Email me with news and offers".
-- It becomes the email marketing grant in the consent ledger. SMS consent is
-- read but NOT reused for WhatsApp: different channel, different legal basis.
SELECT p->>'id'                                                  AS customer_id,
       lower(NULLIF(p->>'email', ''))                            AS email,
       NULLIF(p->>'phone', '')                                   AS phone_raw,
       NULLIF(p->>'first_name', '')                              AS first_name,
       p->>'tags'                                                AS tags,   -- "vip, ethnic"
       NULLIF(p->'email_marketing_consent'->>'state', '')        AS email_consent_state,
       NULLIF(p->'sms_marketing_consent'->>'state', '')          AS sms_consent_state,
       -- The time of the choice, not of processing (see shopify_order.sql).
       COALESCE((p->'email_marketing_consent'->>'consent_updated_at')::timestamptz,
                (p->>'updated_at')::timestamptz,
                now())                                           AS email_consent_at
  FROM webhook_inbox i
 CROSS JOIN LATERAL (SELECT i.payload AS p) x
 WHERE i.source = 'shopify'
   AND i.delivery_id = ?
