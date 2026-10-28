-- V54: the WhatsApp number capture before checkout (owner 2026-10-28), as
-- Indian D2C stores run it with click-to-WhatsApp opt-in widgets: "Get 10%
-- off: tap to get your code on WhatsApp". The shopper sends the first message
-- themselves (a real number, their own opt-in, free replies); a one-time ref
-- in that message ties the WhatsApp number to their browsing session, so an
-- abandoned cart or checkout can be recovered on WhatsApp even when they never
-- typed a number at checkout.

CREATE TABLE wa_link_tokens (
  ref         TEXT PRIMARY KEY,                -- 6 characters, no 0/O/1/I/L
  anon_id     TEXT NOT NULL,                   -- the storefront's first-party id
  cart_token  TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  used_at     TIMESTAMPTZ,
  used_by     UUID REFERENCES identities(id) ON DELETE SET NULL
);
CREATE INDEX wa_link_tokens_created_idx ON wa_link_tokens (created_at);

-- The words under the button, verbatim, and what they grant.
INSERT INTO consent_copy_versions (version, channel, text, purposes, surface) VALUES
 ('wa_offer_v1', 'whatsapp',
  'Opens WhatsApp with a message to WUMIKA. By sending it you agree to get your code, order updates and offers on WhatsApp. Reply STOP anytime.',
  '{transactional,marketing}', 'wa_thread');

-- The number the storefront sends shoppers to: the one Engage sends from.
INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('whatsapp.display_number', 'GLOBAL', 'STRING', 'WhatsApp number shoppers message',
  'Digits with the country code, e.g. 917306140086. Used by the storefront''s "Get my code on WhatsApp" and "Get updates on WhatsApp". Must be the number Engage sends from.',
  'SAFE', 143, '{"type":"string","pattern":"^[0-9]{10,15}$"}')
ON CONFLICT (key) DO NOTHING;
UPDATE config_keys SET default_value = '"15557963126"'::jsonb
 WHERE key = 'whatsapp.display_number' AND default_value IS NULL;

-- Merging two identities now carries the WhatsApp inbox (V49) and the tables
-- added after V6, so a customer's chat follows them when their records join
-- (a checkout with the same phone, a WhatsApp opt-in ref). Unchanged otherwise.
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
  UPDATE wa_messages        SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE wa_inbound         SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE support_requests   SET identity_id = p_winner WHERE identity_id = p_loser;
  UPDATE wa_link_tokens     SET used_by     = p_winner WHERE used_by     = p_loser;

  -- One campaign row per person: the winner's stands.
  UPDATE campaign_recipients r SET identity_id = p_winner
   WHERE r.identity_id = p_loser
     AND NOT EXISTS (SELECT 1 FROM campaign_recipients w WHERE w.campaign_id = r.campaign_id AND w.identity_id = p_winner);
  DELETE FROM campaign_recipients WHERE identity_id = p_loser;

  -- One inbox thread per person: the loser's folds into the winner's.
  INSERT INTO wa_threads (identity_id, last_message_at, last_inbound_at, last_preview, unread, status, updated_at)
  SELECT p_winner, last_message_at, last_inbound_at, last_preview, unread, status, now()
    FROM wa_threads WHERE identity_id = p_loser
  ON CONFLICT (identity_id) DO UPDATE
     SET last_preview    = CASE WHEN EXCLUDED.last_message_at > wa_threads.last_message_at
                                THEN EXCLUDED.last_preview ELSE wa_threads.last_preview END,
         last_message_at = GREATEST(wa_threads.last_message_at, EXCLUDED.last_message_at),
         last_inbound_at = GREATEST(wa_threads.last_inbound_at, EXCLUDED.last_inbound_at),
         unread          = wa_threads.unread + EXCLUDED.unread,
         status          = CASE WHEN wa_threads.status = 'open' OR EXCLUDED.status = 'open' THEN 'open' ELSE 'done' END,
         updated_at      = now();
  DELETE FROM wa_threads WHERE identity_id = p_loser;

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
