-- V6: identity resolution and merge, as database functions.
--
-- Why in the database: resolution must be atomic across concurrent webhooks.
-- Shopify sends checkouts/update and orders/create for the same shopper within
-- milliseconds, often to different pods. Advisory locks on the keys serialise
-- exactly the resolutions that touch the same phone/customer/email, and nothing
-- else, so two pods can never mint two identities for one shopper.
--
-- Merge policy (docs/technical/phase-1-core-platform.md §2). Each key carries
-- a trust level:
--
--   STRONG   verified shopify_customer / wa_id / phone. Proves who this is.
--   SESSION  anon, fcm_token, cart_token, checkout_token. Belong to the browser
--            session that sent them, so they travel WITH the strong key.
--   BUYER    unverified phone/email from the buyer's OWN contact field
--            ("trust":"buyer"). May absorb a SOFT identity — one that owns no
--            verified customer/WhatsApp key (e.g. created from an earlier
--            Razorpay contact or a guest checkout) — but never a HARD one.
--   WEAK     anything else unverified, above all the SHIPPING-ADDRESS phone.
--            Never merges anything; attached only if nobody owns it yet.
--
-- Identities merge only when the call carries a STRONG key.
--
-- Two failure modes this balances, both real in India:
--   * Gifts. A customer ships to their mother and checkout carries the
--     MOTHER's phone in the shipping address. Merging on it would fuse two
--     people and send one person's messages to the other's WhatsApp. Shipping
--     phones are therefore WEAK.
--   * Fragmentation. A shopper's phone is first seen on a failed Razorpay
--     payment, then they log in and order. Refusing every unverified merge
--     would leave consent on one identity and the phone on another, and order
--     updates would never reach them. The buyer's contact phone is therefore
--     BUYER, allowed to absorb that soft identity.
-- A verified customer's identity is never absorbed through an unverified key.

/* ------------------------------------------------------------------ */
/* merge_identity(winner, loser): move everything from loser to winner */
/* ------------------------------------------------------------------ */
CREATE OR REPLACE FUNCTION merge_identity(p_winner uuid, p_loser uuid) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
  IF p_winner = p_loser THEN RETURN; END IF;

  UPDATE identity_keys      SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE events             SET identity_id = p_winner WHERE identity_id = p_loser;
  -- consents_guard permits changing identity_id alone (history is preserved).
  UPDATE consents           SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE devices            SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE push_prompt_events SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE carts              SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE checkouts          SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE orders             SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE sends              SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE conversions        SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE payment_attempts   SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE cascade_runs       SET identity_id = p_winner WHERE identity_id = p_loser;

  -- Tables keyed by (identity, x): keep the winner's row on conflict.
  INSERT INTO stock_waitlist (identity_id, variant_id, product_handle, size_label, created_at, notified_at)
  SELECT p_winner, variant_id, product_handle, size_label, created_at, notified_at
    FROM stock_waitlist WHERE identity_id = p_loser
  ON CONFLICT (identity_id, variant_id) DO NOTHING;
  DELETE FROM stock_waitlist WHERE identity_id = p_loser;

  INSERT INTO suppressions (identity_id, channel, reason, until, created_at)
  SELECT p_winner, channel, reason, until, created_at
    FROM suppressions WHERE identity_id = p_loser
  ON CONFLICT (identity_id, channel, reason) DO NOTHING;
  DELETE FROM suppressions WHERE identity_id = p_loser;

  -- Holdout: the winner's bucket stands, so measurement stays consistent.
  INSERT INTO holdouts (identity_id, experiment, bucket, assigned_at)
  SELECT p_winner, experiment, bucket, assigned_at
    FROM holdouts WHERE identity_id = p_loser
  ON CONFLICT (identity_id, experiment) DO NOTHING;
  DELETE FROM holdouts WHERE identity_id = p_loser;

  -- Capability: proof of delivery (CAPABLE) on either side wins; otherwise
  -- the winner's state stands.
  UPDATE channel_capability w
     SET state = 'CAPABLE', recheck_after = NULL, strike_days = '{}', decided_at = now()
    FROM channel_capability l
   WHERE w.identity_id = p_winner AND l.identity_id = p_loser
     AND w.channel = l.channel AND l.state = 'CAPABLE' AND w.state <> 'CAPABLE';
  INSERT INTO channel_capability (identity_id, channel, state, strike_days, evidence, backoff_until, decided_at, recheck_after)
  SELECT p_winner, channel, state, strike_days, evidence, backoff_until, decided_at, recheck_after
    FROM channel_capability WHERE identity_id = p_loser
  ON CONFLICT (identity_id, channel) DO NOTHING;
  DELETE FROM channel_capability WHERE identity_id = p_loser;

  -- Profile: winner's values win key-by-key; the loser fills gaps.
  INSERT INTO profiles (identity_id) VALUES (p_winner) ON CONFLICT DO NOTHING;
  UPDATE profiles w
     SET attrs    = l.attrs || w.attrs,
         computed = l.computed || w.computed,
         wa_window_until = GREATEST(w.wa_window_until, l.wa_window_until),
         updated_at = now()
    FROM profiles l
   WHERE w.identity_id = p_winner AND l.identity_id = p_loser;
  DELETE FROM profiles WHERE identity_id = p_loser;

  UPDATE identities SET merged_into = p_winner WHERE id = p_loser;
END;
$$;

/* ------------------------------------------------------------------ */
/* resolve_identity(keys): find-or-create, merging only on verified keys */
/* ------------------------------------------------------------------ */
-- p_keys: [{"kind":"phone","value":"919876543210","verified":false}, ...]
-- Values must already be normalised by the caller (Msisdn, lowercased email,
-- CartTokens). Blank values are ignored.
CREATE OR REPLACE FUNCTION resolve_identity(p_keys jsonb) RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE
  k         record;
  v_merge   uuid[];   -- identities reached via STRONG or SESSION keys
  v_all     uuid[];   -- every identity reached by any key
  v_winner  uuid;
  v_loser   uuid;
  v_strong  boolean;
BEGIN
  -- Lock every incoming key, in a fixed order, so concurrent resolutions of
  -- overlapping key sets serialise instead of racing or deadlocking.
  FOR k IN
    SELECT DISTINCT e->>'kind' AS kind, e->>'value' AS value
      FROM jsonb_array_elements(p_keys) e
     WHERE COALESCE(e->>'value', '') <> ''
     ORDER BY 1, 2
  LOOP
    PERFORM pg_advisory_xact_lock(hashtextextended(k.kind || ':' || k.value, 0));
  END LOOP;

  SELECT EXISTS (SELECT 1 FROM jsonb_array_elements(p_keys) e
                  WHERE COALESCE((e->>'verified')::boolean, false)
                    AND e->>'kind' IN ('shopify_customer', 'wa_id', 'phone')
                    AND COALESCE(e->>'value', '') <> '')
    INTO v_strong;

  SELECT array_agg(DISTINCT ik.identity_id),
         array_agg(DISTINCT ik.identity_id) FILTER (
           WHERE (COALESCE((e->>'verified')::boolean, false)                 -- STRONG
                  AND e->>'kind' IN ('shopify_customer', 'wa_id', 'phone'))
              OR (ik.verified AND ik.kind IN ('shopify_customer', 'wa_id'))  -- matched a proven key
              OR e->>'kind' IN ('anon', 'fcm_token', 'cart_token', 'checkout_token')  -- SESSION
              OR (e->>'trust' = 'buyer'                                       -- BUYER -> soft only
                  AND NOT EXISTS (SELECT 1 FROM identity_keys h
                                   WHERE h.identity_id = ik.identity_id AND h.verified
                                     AND h.kind IN ('shopify_customer', 'wa_id'))))
    INTO v_all, v_merge
    FROM identity_keys ik
    JOIN jsonb_array_elements(p_keys) e
      ON ik.kind = (e->>'kind')::key_kind AND ik.value = e->>'value';

  -- New identity when nothing matched, OR when the caller proved who this is
  -- (strong key) but nothing matched through a strong/session key. In the
  -- second case the only matches are PERSON keys belonging to someone else:
  -- a first-time customer shipping a gift to a phone that is already the
  -- recipient's must not be attached to the recipient.
  IF v_all IS NULL OR (v_strong AND v_merge IS NULL) THEN
    INSERT INTO identities DEFAULT VALUES RETURNING id INTO v_winner;
  ELSE
    -- Winner: prefer an identity reached through a strong/session key that
    -- owns a verified customer/WhatsApp key; then the oldest. If only PERSON
    -- keys matched, the oldest of those (no merge happens below).
    SELECT i.id INTO v_winner
      FROM identities i
     WHERE i.id = ANY (COALESCE(v_merge, v_all))
     ORDER BY EXISTS (SELECT 1 FROM identity_keys x
                       WHERE x.identity_id = i.id AND x.verified
                         AND x.kind IN ('shopify_customer', 'wa_id')) DESC,
              i.created_at, i.id
     LIMIT 1;

    IF v_strong AND v_merge IS NOT NULL THEN
      FOREACH v_loser IN ARRAY v_merge LOOP
        IF v_loser <> v_winner THEN PERFORM merge_identity(v_winner, v_loser); END IF;
      END LOOP;
    END IF;
  END IF;

  INSERT INTO profiles (identity_id) VALUES (v_winner) ON CONFLICT DO NOTHING;

  -- Attach keys. A key that already belongs to someone else stays where it is
  -- (no silent re-assignment); a verified observation upgrades the flag.
  INSERT INTO identity_keys (identity_id, kind, value, verified)
  SELECT v_winner, (e->>'kind')::key_kind, e->>'value',
         COALESCE((e->>'verified')::boolean, false)
    FROM jsonb_array_elements(p_keys) e
   WHERE COALESCE(e->>'value', '') <> ''
  ON CONFLICT (kind, value) DO UPDATE
     SET last_seen = now(),
         verified  = identity_keys.verified OR EXCLUDED.verified;

  RETURN v_winner;
END;
$$;
