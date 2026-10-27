-- V51: the store's Admin API token from the Shopify authorization step
-- (owner's choice 2026-10-28). The client-credentials grant only works for a
-- store in the app's own Dev Dashboard organization, and the live store is
-- not (Shopify answers shop_not_permitted), so the owner approves Engage once
-- at /shopify/oauth/start and Shopify returns an offline token. Used for
-- order look-ups from the WhatsApp inbox and back-in-stock mapping.

CREATE TABLE shopify_tokens (
  shop        TEXT PRIMARY KEY,                -- e4bac4-ef.myshopify.com
  token_enc   BYTEA NOT NULL,                  -- AES-256-GCM (SecretBox); key derived from the app secret
  scopes      TEXT NOT NULL,                   -- what the owner granted
  granted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One-time "state" values that tie Shopify's callback to a start we issued.
CREATE TABLE shopify_oauth_states (
  state       TEXT PRIMARY KEY,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
