-- V2: admin console — operators, RBAC, versioned config, campaigns, audit.
--
-- Builds on V1 (identities, profiles, consents, sends, journey_runs, ...).
--
-- The one non-obvious decision in this file: config is APPEND-ONLY and
-- versioned, exactly like the consent ledger. Settings that govern whether a
-- message may be sent are evidence, not preferences. When someone asks "why
-- did this customer get a marketing WhatsApp at 22:40", the answer must be the
-- cap and quiet-hours values *as they were at that instant* — not whatever the
-- row happens to say today. Mutable settings rows destroy that answer.

-- No extensions required. Emails are stored lowercased and the database
-- enforces it, so "Nithin@x.com" and "nithin@x.com" can never become two
-- operator accounts with different roles. (citext would also work, but it is a
-- contrib module that some minimal local Postgres installs do not ship.)

/* =====================================================================
   1. OPERATORS + RBAC
   ===================================================================== */

CREATE TABLE operators (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email           TEXT NOT NULL UNIQUE CHECK (email = lower(email)),
  full_name       TEXT   NOT NULL,
  -- Argon2id. Never bcrypt for new systems; never store the raw secret.
  password_hash   TEXT   NOT NULL,
  password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- TOTP secret, encrypted at rest with the app's data key (pgcrypto or KMS).
  mfa_secret_enc  BYTEA,
  mfa_enrolled_at TIMESTAMPTZ,
  status          TEXT   NOT NULL DEFAULT 'invited'
                  CHECK (status IN ('invited','active','suspended','disabled')),
  failed_logins   INT    NOT NULL DEFAULT 0,
  locked_until    TIMESTAMPTZ,
  last_login_at   TIMESTAMPTZ,
  created_by      UUID REFERENCES operators(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Roles are fixed in code (an enum), not user-editable. Editable role
-- definitions are a permission-escalation surface and nobody needs them.
CREATE TABLE operator_roles (
  operator_id UUID NOT NULL REFERENCES operators(id) ON DELETE CASCADE,
  role        TEXT NOT NULL CHECK (role IN (
                'VIEWER',        -- read dashboards, campaigns, sends
                'ANALYST',       -- + export, incrementality, saved segments
                'CAMPAIGN_EDIT', -- + create/edit campaigns and templates (no send)
                'CAMPAIGN_SEND', -- + execute/approve sends
                'CONFIG_ADMIN',  -- + change policy config (caps, budgets, quiet hours)
                'OWNER'          -- + manage operators, API keys, destructive ops
              )),
  granted_by  UUID REFERENCES operators(id),
  granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (operator_id, role)
);

-- Refresh tokens are opaque and stored hashed, so a database leak does not
-- hand over live sessions. Rotation on every use, with reuse detection:
-- presenting a token that has already been rotated means it leaked, and the
-- whole family is revoked.
CREATE TABLE operator_sessions (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  operator_id    UUID NOT NULL REFERENCES operators(id) ON DELETE CASCADE,
  family_id      UUID NOT NULL,
  refresh_hash   BYTEA NOT NULL UNIQUE,     -- sha256(token), not the token
  user_agent     TEXT,
  ip             INET,
  issued_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at     TIMESTAMPTZ NOT NULL,
  rotated_at     TIMESTAMPTZ,
  revoked_at     TIMESTAMPTZ,
  revoked_reason TEXT
);
CREATE INDEX ON operator_sessions (operator_id) WHERE revoked_at IS NULL;
CREATE INDEX ON operator_sessions (family_id);

-- Machine access for CI, the storefront SDK signing key, internal services.
CREATE TABLE api_keys (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name        TEXT NOT NULL,
  key_prefix  TEXT NOT NULL UNIQUE,         -- shown in UI: "ek_live_7f2a…"
  key_hash    BYTEA NOT NULL,
  scopes      TEXT[] NOT NULL DEFAULT '{}',
  created_by  UUID REFERENCES operators(id),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ,
  expires_at  TIMESTAMPTZ,
  revoked_at  TIMESTAMPTZ
);

/* =====================================================================
   2. VERSIONED CONFIG
   ===================================================================== */

-- A config key is declared in code with its type, validation and blast radius.
-- This table is the registry the UI renders from, so adding a setting is a
-- migration plus an enum entry, never a free-text row someone invents at 2am.
CREATE TABLE config_keys (
  key          TEXT PRIMARY KEY,
  scope        TEXT NOT NULL CHECK (scope IN ('GLOBAL','CHANNEL','JOURNEY','TEMPLATE')),
  value_type   TEXT NOT NULL CHECK (value_type IN ('INT','DECIMAL','BOOL','STRING','ENUM','JSON','TIME_RANGE')),
  json_schema  JSONB,                        -- validated server-side on write
  label        TEXT NOT NULL,
  help_text    TEXT,
  -- SAFE: cosmetic. GUARDED: changes who receives messages. CRITICAL: changes
  -- spend or compliance posture; requires a second approver.
  risk         TEXT NOT NULL DEFAULT 'GUARDED'
               CHECK (risk IN ('SAFE','GUARDED','CRITICAL')),
  min_role     TEXT NOT NULL DEFAULT 'CONFIG_ADMIN',
  sort_order   INT  NOT NULL DEFAULT 100,
  deprecated   BOOLEAN NOT NULL DEFAULT false
);

-- Append-only. One row per (key, selector) per change. Never UPDATE.
-- `selector` narrows a key to its scope target: 'whatsapp', 'cart_abandon',
-- or '*' for global.
CREATE TABLE config_versions (
  id           BIGSERIAL PRIMARY KEY,
  key          TEXT NOT NULL REFERENCES config_keys(key),
  selector     TEXT NOT NULL DEFAULT '*',
  value        JSONB NOT NULL,
  -- Effective-dated so a festive-season cap can be staged in advance and roll
  -- back automatically instead of relying on someone remembering.
  effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
  effective_to   TIMESTAMPTZ,
  changed_by   UUID NOT NULL REFERENCES operators(id),
  approved_by  UUID REFERENCES operators(id),
  reason       TEXT NOT NULL,               -- mandatory. "why" is the whole point
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX ON config_versions (key, selector, effective_from DESC);

-- CRITICAL changes wait here, NOT in config_versions. Only on approval by a
-- second operator is a config_versions row INSERTED (approved_by set). Keeping
-- unapproved values out of config_versions matters: config_current resolves by
-- effective_from alone, so an unapproved future-dated row would silently take
-- effect when its start time arrived.
CREATE TABLE config_proposals (
  id             BIGSERIAL PRIMARY KEY,
  key            TEXT NOT NULL REFERENCES config_keys(key),
  selector       TEXT NOT NULL DEFAULT '*',
  value          JSONB NOT NULL,
  effective_from TIMESTAMPTZ,              -- NULL = on approval
  effective_to   TIMESTAMPTZ,
  reason         TEXT NOT NULL,
  proposed_by    UUID NOT NULL REFERENCES operators(id),
  status         TEXT NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
  decided_by     UUID REFERENCES operators(id),
  decided_at     TIMESTAMPTZ,
  applied_version_id BIGINT REFERENCES config_versions(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- Four eyes, enforced by the database as well as the service.
  CHECK (decided_by IS NULL OR decided_by <> proposed_by)
);
CREATE INDEX ON config_proposals (status, created_at) WHERE status = 'PENDING';

-- Resolution order: exact selector beats '*', later effective_from wins.
CREATE OR REPLACE VIEW config_current AS
SELECT DISTINCT ON (key, selector)
       id, key, selector, value, effective_from, changed_by, approved_by, reason
  FROM config_versions
 WHERE effective_from <= now()
   AND (effective_to IS NULL OR effective_to > now())
 ORDER BY key, selector, effective_from DESC, id DESC;

-- A snapshot is the immutable set of config version ids in force at a moment.
-- `sends.config_snapshot_id` points here, which is what makes a send
-- reconstructable years later.
CREATE TABLE config_snapshots (
  id           BIGSERIAL PRIMARY KEY,
  fingerprint  BYTEA NOT NULL UNIQUE,        -- sha256 of the sorted version ids
  version_ids  BIGINT[] NOT NULL,
  resolved     JSONB NOT NULL,               -- flattened key/selector -> value
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE sends ADD COLUMN config_snapshot_id BIGINT REFERENCES config_snapshots(id);

/* =====================================================================
   3. CAMPAIGNS
   ===================================================================== */

CREATE TABLE segments (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name        TEXT NOT NULL UNIQUE,
  description TEXT,
  -- A safe DSL compiled server-side to SQL. Never raw SQL from the browser:
  -- the admin UI must not be a remote query console into customer data.
  definition  JSONB NOT NULL,
  last_size   INT,
  last_sized_at TIMESTAMPTZ,
  created_by  UUID REFERENCES operators(id),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE campaigns (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name           TEXT NOT NULL,
  channel        channel NOT NULL,
  template_key   TEXT NOT NULL REFERENCES templates(key),
  segment_id     UUID REFERENCES segments(id),
  vars           JSONB NOT NULL DEFAULT '{}',
  -- DRAFT -> ESTIMATING -> READY -> PENDING_APPROVAL -> SCHEDULED
  --       -> RUNNING -> COMPLETED | PAUSED | CANCELLED | FAILED
  status         TEXT NOT NULL DEFAULT 'DRAFT',
  scheduled_at   TIMESTAMPTZ,
  -- Throttle protects the Meta quality rating: a 200k blast in ten minutes is
  -- how a High rating becomes Low overnight.
  send_rate_per_minute INT NOT NULL DEFAULT 600,
  holdout_pct    NUMERIC(5,2) NOT NULL DEFAULT 0,
  -- Filled by the dry run. The UI refuses to arm a campaign without it.
  estimate       JSONB,
  estimated_at   TIMESTAMPTZ,
  estimated_cost_paise BIGINT,
  -- Hard ceiling. The executor stops here even mid-run.
  budget_cap_paise BIGINT,
  created_by     UUID NOT NULL REFERENCES operators(id),
  approved_by    UUID REFERENCES operators(id),
  approved_at    TIMESTAMPTZ,
  config_snapshot_id BIGINT REFERENCES config_snapshots(id),
  started_at     TIMESTAMPTZ,
  finished_at    TIMESTAMPTZ,
  stats          JSONB NOT NULL DEFAULT '{}',
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (status IN ('DRAFT','ESTIMATING','READY','PENDING_APPROVAL','SCHEDULED',
                    'RUNNING','PAUSED','COMPLETED','CANCELLED','FAILED'))
);
CREATE INDEX ON campaigns (status, scheduled_at);

-- Materialised audience. Resolving the segment once and freezing it means the
-- run is resumable and reportable; re-running a live query mid-send gives you
-- a moving target and duplicate or missed recipients.
CREATE TABLE campaign_recipients (
  campaign_id  UUID NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
  identity_id  UUID NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
  bucket       TEXT NOT NULL DEFAULT 'treatment',
  state        TEXT NOT NULL DEFAULT 'pending'
               CHECK (state IN ('pending','claimed','sent','blocked','failed','skipped')),
  send_id      BIGINT REFERENCES sends(id),
  block_reason TEXT,
  attempts     SMALLINT NOT NULL DEFAULT 0,
  processed_at TIMESTAMPTZ,
  PRIMARY KEY (campaign_id, identity_id)
);
CREATE INDEX ON campaign_recipients (campaign_id, state)
  WHERE state IN ('pending','claimed');

/* =====================================================================
   4. AUDIT
   ===================================================================== */

-- Every state-changing admin action. Written in the same transaction as the
-- change itself, so an action cannot exist without its audit row.
CREATE TABLE audit_log (
  id          BIGSERIAL PRIMARY KEY,
  actor_id    UUID REFERENCES operators(id),
  actor_kind  TEXT NOT NULL DEFAULT 'operator'
              CHECK (actor_kind IN ('operator','api_key','system')),
  action      TEXT NOT NULL,                 -- config.update, campaign.approve, ...
  entity_type TEXT NOT NULL,
  entity_id   TEXT,
  before      JSONB,
  after       JSONB,
  ip          INET,
  user_agent  TEXT,
  request_id  TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON audit_log (entity_type, entity_id, created_at DESC);
CREATE INDEX ON audit_log (actor_id, created_at DESC);

-- Append-only enforced in the database, not just in the service layer. An ORM
-- bug or a stray psql session should not be able to rewrite history.
--
-- A trigger that RAISES, not a rule that does nothing. `DO INSTEAD NOTHING`
-- makes a buggy UPDATE report success while silently discarding it, which is
-- worse than failing: the code believes history was changed, the table says
-- otherwise, and nobody finds out until an audit.
CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% on % is forbidden: table is append-only', TG_OP, TG_TABLE_NAME
    USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER audit_log_append_only
  BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER config_versions_append_only
  BEFORE UPDATE OR DELETE ON config_versions
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

/* =====================================================================
   5. SEED
   ===================================================================== */

INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order) VALUES
 ('quiet_hours',              'GLOBAL',  'TIME_RANGE','Quiet hours (IST)',
  'Marketing is deferred to the next open window. Order-critical utility templates are exempt.','GUARDED',10),
 ('cap.whatsapp.marketing.1d','CHANNEL','INT','WhatsApp marketing / day',
  'Keep below Meta''s own per-user limit so you never hit it.','CRITICAL',20),
 ('cap.whatsapp.marketing.7d','CHANNEL','INT','WhatsApp marketing / week', NULL,'CRITICAL',21),
 ('cap.push.marketing.1d',    'CHANNEL','INT','Push / day', NULL,'GUARDED',22),
 ('cap.email.marketing.7d',   'CHANNEL','INT','Email / week', NULL,'GUARDED',23),
 ('budget.whatsapp.marketing.daily_paise','CHANNEL','INT','Daily WhatsApp marketing budget (paise)',
  'Hard stop. A runaway journey costs you a day, not a quarter.','CRITICAL',30),
 ('rate.whatsapp.marketing_paise','CHANNEL','INT','Meta marketing rate (paise)',
  'Used for pre-send estimates. Reconcile against Meta billing monthly.','GUARDED',31),
 ('rate.whatsapp.utility_paise','CHANNEL','INT','Meta utility rate (paise)', NULL,'GUARDED',32),
 ('holdout.global_pct',       'GLOBAL', 'DECIMAL','Global holdout %',
  'Never measured, never messaged. Keep this on permanently.','CRITICAL',40),
 ('journey.enabled',          'JOURNEY','BOOL','Journey enabled', NULL,'GUARDED',50),
 ('approval.required_above_paise','GLOBAL','INT','Second approver required above (paise)',
  'Four-eyes on any campaign whose estimated spend exceeds this.','CRITICAL',60)
ON CONFLICT (key) DO NOTHING;
