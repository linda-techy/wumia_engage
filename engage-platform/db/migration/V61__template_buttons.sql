-- V61: the labels of a WhatsApp template's buttons, so the message log can show what the customer saw.
ALTER TABLE templates ADD COLUMN buttons TEXT[] NOT NULL DEFAULT '{}';
