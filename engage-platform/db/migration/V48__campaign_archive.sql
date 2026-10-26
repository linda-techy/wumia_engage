-- V48: archive finished campaigns (tests, past festivals) out of the list
-- without deleting their sends, report or audit. Null = shown.
ALTER TABLE campaigns ADD COLUMN archived_at TIMESTAMPTZ;
