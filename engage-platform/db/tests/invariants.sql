-- Database invariant tests. Runs inside a transaction and ROLLS BACK: safe on any DB.
-- Usage: psql "$DB_URL_PSQL" -f db/tests/invariants.sql
\set ON_ERROR_STOP 1
BEGIN;
-- fixtures
INSERT INTO operators (id,email,full_name,password_hash,status) VALUES
 ('00000000-0000-0000-0000-00000000000a','priya@brand.in','Priya','x','active'),
 ('00000000-0000-0000-0000-00000000000b','arun@brand.in','Arun','x','active');
INSERT INTO identities (id) VALUES ('11111111-1111-1111-1111-111111111111');
INSERT INTO consent_copy_versions VALUES ('wa_v1','whatsapp','Send me order updates and offers from BRAND on WhatsApp','{transactional,marketing}','cart',NULL,now());

-- T1 consent_current returns the latest state
INSERT INTO consents (identity_id,channel,purpose,state,source,copy_version,occurred_at) VALUES
 ('11111111-1111-1111-1111-111111111111','whatsapp','marketing','granted','cart_attr','wa_v1',now()-interval '2 days'),
 ('11111111-1111-1111-1111-111111111111','whatsapp','marketing','withdrawn','wa_stop_reply',NULL,now());
DO $$ BEGIN ASSERT (SELECT state FROM consent_current WHERE channel='whatsapp' AND purpose='marketing')='withdrawn', 'T1 consent_current'; END $$;

-- T2 consents cannot be UPDATEd
DO $$ BEGIN
  BEGIN UPDATE consents SET state='granted'; RAISE EXCEPTION 'T2 update allowed';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- T3 config_versions append-only raises (not silent)
INSERT INTO config_versions (key,value,changed_by,reason) VALUES
 ('cap.whatsapp.marketing.1d','1','00000000-0000-0000-0000-00000000000a','initial');
DO $$ BEGIN
  BEGIN UPDATE config_versions SET value='5'; RAISE EXCEPTION 'T3 update allowed';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN DELETE FROM config_versions; RAISE EXCEPTION 'T3 delete allowed';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
DO $$ BEGIN ASSERT (SELECT value::int FROM config_current WHERE key='cap.whatsapp.marketing.1d')=1, 'T3 config_current'; END $$;

-- T4 four eyes: proposer cannot decide their own proposal
INSERT INTO config_proposals (id,key,value,reason,proposed_by) VALUES
 (1,'cap.whatsapp.marketing.1d','2','Diwali','00000000-0000-0000-0000-00000000000a');
DO $$ BEGIN
  BEGIN UPDATE config_proposals SET status='APPROVED', decided_by='00000000-0000-0000-0000-00000000000a' WHERE id=1;
        RAISE EXCEPTION 'T4 self-approval allowed';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;
UPDATE config_proposals SET status='APPROVED', decided_by='00000000-0000-0000-0000-00000000000b', decided_at=now() WHERE id=1;

-- T5 a pending proposal never affects config_current
DO $$ BEGIN ASSERT (SELECT value::int FROM config_current WHERE key='cap.whatsapp.marketing.1d')=1, 'T5 proposal leaked'; END $$;

-- T6 audit_log append-only
INSERT INTO audit_log (actor_id,action,entity_type) VALUES ('00000000-0000-0000-0000-00000000000a','config.propose','config');
DO $$ BEGIN
  BEGIN DELETE FROM audit_log; RAISE EXCEPTION 'T6 delete allowed';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- T7 one live cascade per intent+subject; a finished one does not block a new one
INSERT INTO cascade_runs (intent_key,identity_id,subject_key,priority) VALUES ('cart_recovery','11111111-1111-1111-1111-111111111111','cart_abc',3);
DO $$ BEGIN
  BEGIN INSERT INTO cascade_runs (intent_key,identity_id,subject_key,priority) VALUES ('cart_recovery','11111111-1111-1111-1111-111111111111','cart_abc',3);
        RAISE EXCEPTION 'T7 duplicate live cascade';
  EXCEPTION WHEN unique_violation THEN NULL; END;
END $$;
UPDATE cascade_runs SET status='succeeded' WHERE subject_key='cart_abc';
INSERT INTO cascade_runs (intent_key,identity_id,subject_key,priority) VALUES ('cart_recovery','11111111-1111-1111-1111-111111111111','cart_abc',3);

-- T8 cart tokens must be normalised
DO $$ BEGIN
  BEGIN INSERT INTO carts (cart_token) VALUES ('Z2NwLXVz?key=abc'); RAISE EXCEPTION 'T8 raw token accepted';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;

-- T9 lowercase emails enforced
DO $$ BEGIN
  BEGIN INSERT INTO operators (email,full_name,password_hash) VALUES ('Nithin@Brand.in','N','x'); RAISE EXCEPTION 'T9 mixed-case email';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;

-- T10 INCAPABLE must carry a recheck date (no permanent write-offs)
DO $$ BEGIN
  BEGIN INSERT INTO channel_capability (identity_id,channel,state) VALUES ('11111111-1111-1111-1111-111111111111','whatsapp','INCAPABLE');
        RAISE EXCEPTION 'T10 permanent incapable';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;

-- T11 Razorpay: duplicate event for same payment+status rejected; match report works
INSERT INTO checkouts (token,phone,total_paise,updated_at) VALUES ('chk_1','919876543210',129900,now());
INSERT INTO payment_attempts (gateway_payment_id,status,amount_paise,currency,method,phone,error_source,error_reason,checkout_token,match_method,gateway_created_at)
 VALUES ('pay_A','failed',129900,'INR','upi','919876543210','customer','payment_timed_out','chk_1','phone_amount_window',now()-interval '4 seconds');
DO $$ BEGIN
  BEGIN INSERT INTO payment_attempts (gateway_payment_id,status,amount_paise,currency,gateway_created_at) VALUES ('pay_A','failed',129900,'INR',now());
        RAISE EXCEPTION 'T11 dup payment';
  EXCEPTION WHEN unique_violation THEN NULL; END;
END $$;
DO $$ BEGIN ASSERT (SELECT failures FROM payment_failure_match_report WHERE match_method='phone_amount_window')=1, 'T11 report'; END $$;

-- T12 SKIP LOCKED claim query is valid and returns due work
DO $$ DECLARE n int; BEGIN
  WITH due AS (SELECT id FROM cascade_runs WHERE status IN ('active','waiting') AND next_step_at <= now()
               ORDER BY next_step_at LIMIT 200 FOR UPDATE SKIP LOCKED)
  UPDATE cascade_runs r SET status='active', updated_at=now() FROM due WHERE r.id=due.id;
  GET DIAGNOSTICS n = ROW_COUNT; ASSERT n=1, 'T12 claim';
END $$;

-- T13 DPDP erasure: deleting an identity cascades through consents despite the UPDATE guard
DELETE FROM identities WHERE id='11111111-1111-1111-1111-111111111111';
DO $$ BEGIN ASSERT (SELECT count(*) FROM consents WHERE identity_id='11111111-1111-1111-1111-111111111111')=0
               AND (SELECT count(*) FROM cascade_runs WHERE identity_id='11111111-1111-1111-1111-111111111111')=0,
               'T13 erasure'; END $$;

-- T14 category mismatch view flags a utility template approved as marketing
INSERT INTO wa_templates (key,language,provider_name,requested_category,approved_category,status)
 VALUES ('order_shipped','en','order_shipped_v1','utility','marketing','APPROVED');
DO $$ BEGIN ASSERT (SELECT count(*) FROM wa_template_category_mismatch)=1, 'T14 mismatch view'; END $$;

-- T15 identity merge may move consent history (identity_id only)...
INSERT INTO identities (id) VALUES ('22222222-2222-2222-2222-222222222222'), ('33333333-3333-3333-3333-333333333333');
INSERT INTO consents (identity_id,channel,purpose,state,source) VALUES
 ('33333333-3333-3333-3333-333333333333','push','marketing','granted','soft_ask:add_to_cart');
UPDATE consents SET identity_id='22222222-2222-2222-2222-222222222222'
 WHERE identity_id='33333333-3333-3333-3333-333333333333';
DO $$ BEGIN ASSERT (SELECT count(*) FROM consents WHERE identity_id='22222222-2222-2222-2222-222222222222')=1, 'T15 merge'; END $$;

-- T16 ...but changing identity_id AND state together is still rejected
DO $$ BEGIN
  BEGIN UPDATE consents SET identity_id='33333333-3333-3333-3333-333333333333', state='withdrawn';
        RAISE EXCEPTION 'T16 history rewritten during merge';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- T17 webhook inbox dedupes provider retries on (source, delivery_id)
INSERT INTO webhook_inbox (source,delivery_id,topic,payload) VALUES ('razorpay','evt_1','payment.failed','{}');
INSERT INTO webhook_inbox (source,delivery_id,topic,payload) VALUES ('razorpay','evt_1','payment.failed','{}')
  ON CONFLICT DO NOTHING;
DO $$ BEGIN ASSERT (SELECT count(*) FROM webhook_inbox WHERE delivery_id='evt_1')=1, 'T17 inbox dedupe'; END $$;

ROLLBACK;
\echo ALL 17 INVARIANT TESTS PASSED
