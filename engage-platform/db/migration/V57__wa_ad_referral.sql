-- V57: which ad a WhatsApp chat came from (owner, 2026-10-31). A shopper who
-- taps the WhatsApp button on a Facebook or Instagram ad ("click to WhatsApp")
-- arrives with Meta's referral on their first message: the ad's id, link,
-- text, media and click id (ctwa_clid). Kept as Meta sent it, so staff see the
-- product they asked about ("Size?"), and later the orders can be credited to
-- the ad.

ALTER TABLE wa_messages ADD COLUMN referral JSONB;

-- The chats from ads before this: Meta's webhooks are kept 7 days (InboxHousekeeping).
UPDATE wa_messages w
   SET referral = m.v->'referral'
  FROM webhook_inbox i
 CROSS JOIN LATERAL jsonb_path_query(i.payload, '$.entry[*].changes[*].value.messages[*]') AS m(v)
 WHERE i.source = 'meta' AND m.v ? 'referral' AND w.wamid = m.v->>'id' AND w.referral IS NULL;
