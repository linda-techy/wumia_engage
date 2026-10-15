-- V36: privacy (P7-T05). DPDP erasure and Shopify's compliance webhooks.
--
-- erase_identity() removes a person: the identity, everything merged into it,
-- and every copy of their phone, email and order data outside the identity
-- graph (orders, checkouts, payments, raw webhook payloads, courier events,
-- exports). What must still add up afterwards (spend, conversions) is moved to
-- a tombstone identity that identifies nobody. audit_log and pii_unmask_log are
-- append-only and keep the bare identity id, which resolves to no one once the
-- identity is gone.

-- Proof that a withdrawal was honoured, without saying whose it was.
CREATE TABLE consent_erasure_proofs (
  id          BIGSERIAL PRIMARY KEY,
  request_ref TEXT NOT NULL,
  channel     channel NOT NULL,
  purpose     purpose NOT NULL,
  state       consent_state NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  erased_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX consent_erasure_proofs_ref_idx ON consent_erasure_proofs (request_ref);

-- Sends and conversions of erased people. Never merged, never erased, never messaged
-- (no keys, no consent).
INSERT INTO identities (id) VALUES ('00000000-0000-0000-0000-000000000000') ON CONFLICT DO NOTHING;

-- Privacy requests: Shopify's customers/data_request, customers/redact and
-- shop/redact, and erasures from the console. subject holds the identifiers a
-- data request needs until the Owner has answered it, then it is emptied;
-- result holds counts, never an identifier.
CREATE TABLE privacy_requests (
  id           BIGSERIAL PRIMARY KEY,
  kind         TEXT NOT NULL CHECK (kind IN ('data_request', 'customer_redact', 'shop_redact', 'erase')),
  source       TEXT NOT NULL,                 -- shopify:<webhook id> | console
  external_ref TEXT,                          -- Shopify's data_request.id
  subject      JSONB NOT NULL DEFAULT '{}',   -- {customer_id, email, phone} while open
  status       TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'done')),
  result       JSONB NOT NULL DEFAULT '{}',
  reason       TEXT,
  created_by   UUID REFERENCES operators(id),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_by  UUID REFERENCES operators(id),
  resolved_at  TIMESTAMPTZ,
  note         TEXT,
  CHECK (status = 'open' OR resolved_at IS NOT NULL),
  CHECK (status = 'open' OR subject = '{}')
);
CREATE INDEX privacy_requests_open_idx ON privacy_requests (created_at) WHERE status = 'open';

ALTER TABLE pii_unmask_log DROP CONSTRAINT pii_unmask_log_field_check;
ALTER TABLE pii_unmask_log ADD CONSTRAINT pii_unmask_log_field_check
  CHECK (field IN ('phone', 'email', 'address', 'support_reply', 'data_request'));

-- '%value%' LIKE patterns, lower-cased, with LIKE's wildcards escaped. Values
-- shorter than 6 characters are dropped: they would match unrelated rows.
CREATE OR REPLACE FUNCTION privacy_like_patterns(p_values TEXT[]) RETURNS TEXT[]
LANGUAGE sql IMMUTABLE AS $$
  SELECT COALESCE(array_agg(DISTINCT '%' || replace(replace(replace(lower(v), '\', '\\'), '%', '\%'), '_', '\_') || '%'), '{}')
    FROM unnest(p_values) v
   WHERE v IS NOT NULL AND length(v) >= 6
$$;

-- Text as the patterns are matched against: lower-cased, with spaces and hyphens
-- between digits removed, so "+91 98765 43210" and "98765-43210" both contain 9876543210.
CREATE OR REPLACE FUNCTION privacy_text(p TEXT) RETURNS TEXT
LANGUAGE sql IMMUTABLE AS $$
  SELECT lower(regexp_replace(p, '(\d)[ -](?=\d)', '\1', 'g'))
$$;

-- Raw webhook payloads that mention any of the values. Processed rows lose
-- their payload; an unprocessed one is also marked processed (with the reason
-- in last_error), so it cannot re-create the person.
CREATE OR REPLACE FUNCTION privacy_scrub_inbox(p_values TEXT[], p_request_ref TEXT) RETURNS INT
LANGUAGE plpgsql AS $$
DECLARE
  v_patterns TEXT[] := privacy_like_patterns(p_values);
  n INT;
BEGIN
  IF cardinality(v_patterns) = 0 THEN RETURN 0; END IF;
  UPDATE webhook_inbox
     SET payload = jsonb_build_object('erased', p_request_ref),
         processed_at = COALESCE(processed_at, now()),
         last_error = CASE WHEN processed_at IS NULL THEN 'erased before processing: ' || p_request_ref ELSE last_error END
   WHERE privacy_text(payload::text) LIKE ANY (v_patterns)
     AND NOT payload ? 'erased';
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END;
$$;

-- An order's personal data: phone, email and note attributes on the order, the
-- courier's raw events for its shipments, and its raw webhooks. The order
-- itself (number, total, lines) stays for revenue reporting.
CREATE OR REPLACE FUNCTION privacy_scrub_orders(p_order_ids TEXT[], p_request_ref TEXT) RETURNS INT
LANGUAGE plpgsql AS $$
DECLARE
  n INT;
BEGIN
  IF p_order_ids IS NULL OR cardinality(p_order_ids) = 0 THEN RETURN 0; END IF;
  UPDATE orders SET identity_id = NULL, phone = NULL, phone_source = NULL, email = NULL, note_attributes = '{}'
   WHERE id = ANY (p_order_ids);
  GET DIAGNOSTICS n = ROW_COUNT;
  UPDATE shipment_events SET raw = jsonb_build_object('erased', p_request_ref)
   WHERE shipment_id IN (SELECT id FROM shipments WHERE order_id = ANY (p_order_ids))
     AND NOT raw ? 'erased';
  PERFORM privacy_scrub_inbox(p_order_ids, p_request_ref);
  RETURN n;
END;
$$;

CREATE OR REPLACE FUNCTION erase_identity(p_identity UUID, p_request_ref TEXT, p_actor UUID DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql AS $$
DECLARE
  c_tombstone CONSTANT UUID := '00000000-0000-0000-0000-000000000000';
  v_root    UUID := p_identity;
  v_next    UUID;
  v_ids     UUID[];
  v_emails  TEXT[];
  v_phones  TEXT[];
  v_anon    TEXT[];
  v_values  TEXT[];
  v_orders  TEXT[];
  v_patterns TEXT[];
  v_result  JSONB;
  n_proofs INT; n_orders INT; n_checkouts INT; n_payments INT; n_sends INT; n_conversions INT;
  n_inbox INT; n_events INT; n_exports INT;
BEGIN
  IF p_identity IS NULL OR p_identity = c_tombstone THEN
    RAISE EXCEPTION 'erase_identity: % is not erasable', p_identity;
  END IF;
  IF p_request_ref IS NULL OR btrim(p_request_ref) = '' THEN
    RAISE EXCEPTION 'erase_identity: a request reference is required';
  END IF;

  INSERT INTO identities (id) VALUES (c_tombstone) ON CONFLICT DO NOTHING;

  -- The person is the surviving identity and everything ever merged into it.
  FOR i IN 1..50 LOOP
    SELECT merged_into INTO v_next FROM identities WHERE id = v_root;
    EXIT WHEN v_next IS NULL;
    v_root := v_next;
  END LOOP;
  WITH RECURSIVE tree AS (
    SELECT id FROM identities WHERE id = v_root
    UNION
    SELECT i.id FROM identities i JOIN tree t ON i.merged_into = t.id
  )
  SELECT array_agg(id) INTO v_ids FROM tree;
  IF v_ids IS NULL THEN
    RETURN jsonb_build_object('identities', 0);
  END IF;
  IF c_tombstone = ANY (v_ids) THEN
    RAISE EXCEPTION 'erase_identity: the tombstone identity is not erasable';
  END IF;

  SELECT COALESCE(array_agg(DISTINCT value) FILTER (WHERE kind = 'email'), '{}'),
         COALESCE(array_agg(DISTINCT value) FILTER (WHERE kind IN ('phone', 'wa_id')), '{}'),
         COALESCE(array_agg(DISTINCT value) FILTER (WHERE kind = 'anon'), '{}'),
         -- A phone appears in payloads as +91…, 91… or the bare 10 digits.
         COALESCE(array_agg(DISTINCT CASE WHEN kind IN ('phone', 'wa_id') THEN right(value, 10) ELSE value END), '{}')
    INTO v_emails, v_phones, v_anon, v_values
    FROM identity_keys WHERE identity_id = ANY (v_ids);

  SELECT COALESCE(array_agg(id), '{}') INTO v_orders FROM orders
   WHERE identity_id = ANY (v_ids) OR phone = ANY (v_phones) OR email = ANY (v_emails);
  v_values := v_values || v_orders;
  v_patterns := privacy_like_patterns(v_values);

  INSERT INTO consent_erasure_proofs (request_ref, channel, purpose, state, occurred_at)
  SELECT p_request_ref, channel, purpose, state, occurred_at
    FROM consents WHERE identity_id = ANY (v_ids) AND state = 'withdrawn';
  GET DIAGNOSTICS n_proofs = ROW_COUNT;

  n_orders := privacy_scrub_orders(v_orders, p_request_ref);

  UPDATE checkouts SET identity_id = NULL, phone = NULL, phone_source = NULL, email = NULL,
                       recovery_url = NULL, note_attributes = '{}'
   WHERE identity_id = ANY (v_ids) OR phone = ANY (v_phones) OR email = ANY (v_emails);
  GET DIAGNOSTICS n_checkouts = ROW_COUNT;

  UPDATE payment_attempts SET identity_id = NULL, contact_raw = NULL, phone = NULL, email = NULL, notes = '{}'
   WHERE identity_id = ANY (v_ids) OR phone = ANY (v_phones) OR email = ANY (v_emails);
  GET DIAGNOSTICS n_payments = ROW_COUNT;

  UPDATE carts SET identity_id = NULL WHERE identity_id = ANY (v_ids);

  -- Spend and attribution still add up; nothing in them points back to the person.
  UPDATE sends SET identity_id = c_tombstone,
                   idempotency_key = 'erased:' || id,
                   decision = jsonb_strip_nulls(jsonb_build_object('reason', decision->'reason', 'erased', true))
   WHERE identity_id = ANY (v_ids);
  GET DIAGNOSTICS n_sends = ROW_COUNT;
  UPDATE conversions SET identity_id = c_tombstone WHERE identity_id = ANY (v_ids);
  GET DIAGNOSTICS n_conversions = ROW_COUNT;

  n_inbox := privacy_scrub_inbox(v_values, p_request_ref);

  -- Anonymous events from before they were identified (pixel events can carry an email).
  DELETE FROM events WHERE identity_id IS NULL AND cardinality(v_patterns) > 0
                       AND privacy_text(props::text) LIKE ANY (v_patterns);
  GET DIAGNOSTICS n_events = ROW_COUNT;
  DELETE FROM push_prompt_events WHERE identity_id = ANY (v_ids) OR anon_id = ANY (v_anon);

  WITH hit AS (
    SELECT export_id FROM export_files
     WHERE cardinality(v_patterns) > 0 AND privacy_text(convert_from(content, 'UTF8')) LIKE ANY (v_patterns)
  ), gone AS (
    DELETE FROM export_files WHERE export_id IN (SELECT export_id FROM hit) RETURNING export_id
  )
  UPDATE exports SET status = 'expired' WHERE id IN (SELECT export_id FROM gone);
  GET DIAGNOSTICS n_exports = ROW_COUNT;

  UPDATE privacy_requests SET subject = '{}'
   WHERE subject <> '{}' AND cardinality(v_patterns) > 0 AND privacy_text(subject::text) LIKE ANY (v_patterns);

  -- Everything else cascades: keys, profile, consents, devices, waitlist, journeys,
  -- holdouts, capability, WhatsApp inbound, support requests, campaign recipients.
  DELETE FROM identities WHERE id = ANY (v_ids);

  v_result := jsonb_build_object(
    'identities', cardinality(v_ids), 'consent_proofs', n_proofs, 'orders', n_orders,
    'checkouts', n_checkouts, 'payments', n_payments, 'sends', n_sends, 'conversions', n_conversions,
    'webhooks', n_inbox, 'events', n_events, 'exports', n_exports);
  INSERT INTO audit_log (actor_id, actor_kind, action, entity_type, entity_id, after)
  VALUES (p_actor, CASE WHEN p_actor IS NULL THEN 'system' ELSE 'operator' END,
          'privacy.erase', 'privacy_request', p_request_ref, v_result);
  RETURN v_result;
END;
$$;
