-- V21: P6-T08 admin security: MFA recovery codes and exports.
-- (pii_unmask_log came earlier, in V18, with the journey inspector.)

-- Ten single-use codes per operator, issued when MFA is confirmed and shown
-- once. Only the sha256 is kept; a code is ~50 random bits.
CREATE TABLE operator_recovery_codes (
    operator_id UUID NOT NULL REFERENCES operators(id) ON DELETE CASCADE,
    code_hash   BYTEA NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (operator_id, code_hash)
);

CREATE TABLE exports (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requested_by UUID NOT NULL REFERENCES operators(id),
    kind         TEXT NOT NULL CHECK (kind IN ('campaign_report', 'segment_ids', 'sends')),
    params       JSONB NOT NULL,
    includes_pii BOOLEAN NOT NULL DEFAULT false,
    reason       TEXT,                           -- required when includes_pii
    row_count    INT,
    status       TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','ready','expired','failed')),
    file_ref     TEXT,                           -- export_files today; an object-store key on AWS
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL DEFAULT now() + interval '24 hours',
    CHECK (NOT includes_pii OR reason IS NOT NULL)
);
CREATE INDEX exports_requester_idx ON exports (requested_by, created_at DESC);

-- The file itself, until object storage exists (production goes to AWS).
-- Deleted when the export expires.
CREATE TABLE export_files (
    export_id    UUID PRIMARY KEY REFERENCES exports(id) ON DELETE CASCADE,
    content_type TEXT NOT NULL DEFAULT 'text/csv',
    content      BYTEA NOT NULL
);
