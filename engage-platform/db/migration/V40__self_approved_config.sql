-- V40: one login can make a CRITICAL settings change (owner's decision
-- 2026-10-15). It still goes through a proposal (the review step), and the
-- proposer may approve it themselves while approval.four_eyes is off. The
-- database keeps the four-eyes rule unless the decision is explicitly marked
-- self-approved, which the service does only when the setting allows it.
ALTER TABLE config_proposals ADD COLUMN self_approved BOOLEAN NOT NULL DEFAULT false;

DO $$
DECLARE c TEXT;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
            WHERE conrelid = 'config_proposals'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%decided_by <> proposed_by%' LOOP
    EXECUTE format('ALTER TABLE config_proposals DROP CONSTRAINT %I', c);
  END LOOP;
END $$;

ALTER TABLE config_proposals ADD CONSTRAINT config_proposals_four_eyes
  CHECK (decided_by IS NULL OR decided_by <> proposed_by OR self_approved);

UPDATE config_keys
   SET label = 'Second approver for campaigns and critical settings',
       help_text = 'Off: one login is enough; you review and approve your own campaigns and critical setting changes. On: someone else must approve them.'
 WHERE key = 'approval.four_eyes';
