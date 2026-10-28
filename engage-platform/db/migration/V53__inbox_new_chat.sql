-- V53: staff can open a WhatsApp chat with any customer from the inbox
-- (owner 2026-10-28), not only those who wrote. Opening a customer who never
-- wrote is a lookup like any other (it shows their number), so it is logged as
-- 'whatsapp_start' and counts toward the 50-a-day limit; a conversation the
-- customer started ('whatsapp_inbox') does not.
ALTER TABLE pii_unmask_log DROP CONSTRAINT pii_unmask_log_field_check;
ALTER TABLE pii_unmask_log ADD CONSTRAINT pii_unmask_log_field_check
  CHECK (field IN ('phone', 'email', 'address', 'support_reply', 'data_request', 'whatsapp_inbox', 'whatsapp_reply',
                   'whatsapp_start'));
