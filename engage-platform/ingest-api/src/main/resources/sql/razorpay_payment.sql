-- Extract one Razorpay payment event from the webhook inbox.
-- Parameter 1: delivery_id (x-razorpay-event-id)
--
-- Notes on Razorpay's payload, handled here so Java never sees them:
--   * amount is already in paise for INR. It is NOT divided by 100.
--   * notes can arrive as an empty JSON ARRAY ([]) instead of an object;
--     it is normalised to {} so downstream code can treat it as a map.
--   * error fields arrive as "" on some events; empty strings become NULL.
SELECT i.payload->>'event'                                     AS event,
       e->>'id'                                                AS payment_id,
       NULLIF(e->>'order_id', '')                              AS gateway_order_id,
       e->>'status'                                            AS status,
       (e->>'amount')::bigint                                  AS amount_paise,
       e->>'currency'                                          AS currency,
       NULLIF(e->>'method', '')                                AS method,
       NULLIF(e->>'contact', '')                               AS contact_raw,
       lower(NULLIF(e->>'email', ''))                          AS email,
       (CASE WHEN jsonb_typeof(e->'notes') = 'object' THEN e->'notes'
             ELSE '{}'::jsonb END)::text                       AS notes_json,
       NULLIF(e->>'error_code', '')                            AS error_code,
       NULLIF(e->>'error_description', '')                     AS error_description,
       NULLIF(e->>'error_source', '')                          AS error_source,
       NULLIF(e->>'error_step', '')                            AS error_step,
       NULLIF(e->>'error_reason', '')                          AS error_reason,
       to_timestamp((e->>'created_at')::bigint)                AS gateway_created_at
  FROM webhook_inbox i
 CROSS JOIN LATERAL (SELECT i.payload->'payload'->'payment'->'entity' AS e) x
 WHERE i.source = 'razorpay'
   AND i.delivery_id = ?
