-- Extract a Shopify refunds/create payload.
-- Parameter 1: delivery_id (X-Shopify-Webhook-Id)
--
-- Refunded money = the successful refund transactions, as decimal strings;
-- the handler converts them with Paise.ofRupees (exact, never a double).
-- A refund that only restocks items has no transactions and refunds nothing.
SELECT p->>'id'                                   AS refund_id,
       p->>'order_id'                             AS order_id,
       (p->>'created_at')::timestamptz            AS created_at,
       ARRAY(SELECT t->>'amount'
               FROM jsonb_array_elements(
                      CASE WHEN jsonb_typeof(p->'transactions') = 'array'
                           THEN p->'transactions' ELSE '[]'::jsonb END) t
              WHERE t->>'kind' = 'refund' AND t->>'status' = 'success'
                AND NULLIF(t->>'amount', '') IS NOT NULL) AS amounts
  FROM webhook_inbox i
 CROSS JOIN LATERAL (SELECT i.payload AS p) x
 WHERE i.source = 'shopify'
   AND i.delivery_id = ?
