-- V4: orchestrator — cascades, WhatsApp capability, consent copy registry,
-- Meta template state, campaign rate buckets.
-- See CLAUDE.md §3–4 and docs/technical/phase-4, phase-5.

/* ===================== consent copy registry ===================== */

-- A grant covers exactly what the shopper was shown. Each copy version
-- declares its purposes; the policy engine never infers coverage from anything
-- else. Text is immutable: new words = new version.
CREATE TABLE consent_copy_versions (
  version    TEXT PRIMARY KEY,            -- wa_v1, ty_wa_v1, push_v1
  channel    channel NOT NULL,
  text       TEXT NOT NULL,               -- verbatim, as displayed
  purposes   purpose[] NOT NULL CHECK (cardinality(purposes) > 0),
  surface    TEXT NOT NULL,               -- cart | thank_you | soft_ask | wa_thread
  created_by UUID REFERENCES operators(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER consent_copy_versions_immutable
  BEFORE UPDATE ON consent_copy_versions
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

ALTER TABLE consents
  ADD CONSTRAINT consents_copy_version_fk
  FOREIGN KEY (copy_version) REFERENCES consent_copy_versions(version);

-- Consents are append-only. Two deliberate exceptions:
--   * DELETE, so DPDP erasure can cascade from identities (erasure keeps an
--     anonymised withdrawal record elsewhere as proof it was honoured);
--   * UPDATE of identity_id ALONE, so an identity merge can carry consent
--     history to the surviving identity. Changing anything else — state,
--     purpose, evidence, timestamps — is rewriting history and raises.
CREATE OR REPLACE FUNCTION consents_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.id, NEW.channel, NEW.purpose, NEW.state, NEW.source, NEW.copy_version,
      NEW.evidence, NEW.occurred_at)
     IS DISTINCT FROM
     (OLD.id, OLD.channel, OLD.purpose, OLD.state, OLD.source, OLD.copy_version,
      OLD.evidence, OLD.occurred_at) THEN
    RAISE EXCEPTION 'consents is append-only: only identity_id may change (identity merge)'
      USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER consents_append_only
  BEFORE UPDATE ON consents
  FOR EACH ROW EXECUTE FUNCTION consents_guard();

/* ======================= channel capability ======================= */

-- There is no API to ask whether a number has WhatsApp. Capability is learned
-- from delivery outcomes, with corroboration, because error 131026 is a
-- catch-all (CLAUDE.md §4).
CREATE TABLE channel_capability (
  identity_id   UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  channel       channel NOT NULL,
  state         TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (state IN ('UNKNOWN','CAPABLE','INCAPABLE')),
  strike_days   DATE[] NOT NULL DEFAULT '{}',   -- distinct IST days with a qualifying failure
  evidence      JSONB NOT NULL DEFAULT '{}',
  backoff_until TIMESTAMPTZ,                    -- 131049: Meta throttled; skip WhatsApp until then
  decided_at    TIMESTAMPTZ,
  recheck_after TIMESTAMPTZ,
  PRIMARY KEY (identity_id, channel),
  CHECK (state <> 'INCAPABLE' OR recheck_after IS NOT NULL)
);
CREATE INDEX channel_capability_recheck_idx ON channel_capability (recheck_after)
  WHERE state = 'INCAPABLE';

/* ============================ cascades ============================ */

CREATE TABLE cascade_runs (
  id           BIGSERIAL PRIMARY KEY,
  intent_key   TEXT NOT NULL,
  identity_id  UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  subject_key  TEXT NOT NULL,                 -- cart token / checkout token / order id / pay_xxx
  priority     SMALLINT NOT NULL,             -- 1 transactional .. 4 low intent (phase-5 §5)
  step_index   INT NOT NULL DEFAULT 0,
  vars         JSONB NOT NULL DEFAULT '{}',
  status       TEXT NOT NULL DEFAULT 'active'
               CHECK (status IN ('active','waiting','succeeded','exhausted','cancelled','failed')),
  outcome      TEXT,
  next_step_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deferrals    SMALLINT NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- One live cascade per intent per subject: the dedupe guarantee.
CREATE UNIQUE INDEX cascade_runs_live_uniq ON cascade_runs (intent_key, subject_key)
  WHERE status IN ('active','waiting');
CREATE INDEX cascade_runs_due_idx ON cascade_runs (next_step_at)
  WHERE status IN ('active','waiting');
-- HIGHER_PRIORITY_ACTIVE check: live runs for a person, by priority.
CREATE INDEX cascade_runs_identity_live_idx ON cascade_runs (identity_id, priority)
  WHERE status IN ('active','waiting');

CREATE TABLE cascade_attempts (
  id         BIGSERIAL PRIMARY KEY,
  run_id     BIGINT NOT NULL REFERENCES cascade_runs(id) ON DELETE CASCADE,
  step_index INT NOT NULL,
  channel    channel NOT NULL,
  send_id    BIGINT REFERENCES sends(id),
  result     TEXT NOT NULL CHECK (result IN ('sent','skipped','blocked','deferred','failed')),
  reason     TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX cascade_attempts_run_idx ON cascade_attempts (run_id, step_index);

ALTER TABLE sends ADD COLUMN cascade_run_id BIGINT REFERENCES cascade_runs(id) ON DELETE SET NULL;

/* ===================== WhatsApp template state ===================== */

CREATE TABLE wa_templates (
  key               TEXT NOT NULL,
  language          TEXT NOT NULL,
  provider_name     TEXT NOT NULL,
  requested_category msg_category NOT NULL,
  -- What Meta APPROVED. Can differ from requested; a utility template approved
  -- as marketing costs ~7x per send, so the sync job alerts on any mismatch.
  approved_category msg_category,
  status            TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','APPROVED','REJECTED','PAUSED','DISABLED')),
  quality           TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (quality IN ('GREEN','YELLOW','RED','UNKNOWN')),
  rejected_reason   TEXT,
  synced_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (key, language)
);

CREATE VIEW wa_template_category_mismatch AS
SELECT key, language, provider_name, requested_category, approved_category
  FROM wa_templates
 WHERE approved_category IS NOT NULL AND approved_category <> requested_category;

/* ====================== campaign rate buckets ====================== */

-- Token bucket shared across worker pods (docs/05-campaigns.md §Rate limiting).
CREATE TABLE campaign_rate_buckets (
  campaign_id       UUID PRIMARY KEY REFERENCES campaigns(id) ON DELETE CASCADE,
  capacity          INT NOT NULL,
  refill_per_second NUMERIC(10,3) NOT NULL,
  tokens            NUMERIC(12,3) NOT NULL,
  refilled_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
