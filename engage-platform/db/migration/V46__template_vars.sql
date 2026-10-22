-- V46: what a template needs, mirrored from templates/*.yaml by the registry
-- sync, so the console can ask for exactly those values when a campaign uses
-- it (admin-api does not load the YAML): the variables, and whether it carries
-- a photo header (a WhatsApp campaign template then needs the poster).
ALTER TABLE templates
  ADD COLUMN vars TEXT[] NOT NULL DEFAULT '{}',
  ADD COLUMN header_image BOOLEAN NOT NULL DEFAULT false;
