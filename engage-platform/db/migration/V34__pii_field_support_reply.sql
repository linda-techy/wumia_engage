-- V34: reading a support request's reply text (V33) in the console is a PII
-- access like a phone or an address: it is logged in pii_unmask_log as
-- field 'support_reply' and counts toward the operator's daily limit.

ALTER TABLE pii_unmask_log DROP CONSTRAINT pii_unmask_log_field_check;
ALTER TABLE pii_unmask_log ADD CONSTRAINT pii_unmask_log_field_check
  CHECK (field IN ('phone', 'email', 'address', 'support_reply'));
