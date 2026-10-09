-- V18: P6-T04 pii_unmask_log, needed by the journey inspector. Planned for the
-- P6 admin-security migration, which keeps exports and recovery codes (P6-T08).
--
-- One row per lookup or unmask of a customer identifier by an operator: who,
-- whose, which field and why. It is also what the 50-per-operator-per-day
-- unmask limit (07-api-contract) counts. A lookup that matched nobody is
-- logged too (identity_id NULL): probing numbers is the pattern to catch.
CREATE TABLE pii_unmask_log (
    id          BIGSERIAL PRIMARY KEY,
    operator_id UUID NOT NULL REFERENCES operators(id),
    identity_id UUID,
    field       TEXT NOT NULL CHECK (field IN ('phone', 'email', 'address')),
    reason      TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX pii_unmask_log_operator_idx ON pii_unmask_log (operator_id, at DESC);

CREATE TRIGGER pii_unmask_log_append_only
  BEFORE UPDATE OR DELETE ON pii_unmask_log
  FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
