-- V39: every campaign is reviewed before it can go out (owner's decision
-- 2026-10-15). After its dry run a campaign waits for review; an operator with
-- sending rights checks the preview, the audience and the cost and approves it,
-- and only then can it be armed. One login is enough: the author may approve
-- their own campaign unless approval.four_eyes is switched on.
INSERT INTO config_keys (key, scope, value_type, label, help_text, risk, sort_order, json_schema) VALUES
 ('approval.four_eyes', 'GLOBAL', 'BOOL', 'Campaigns need a second approver',
  'Off: the author may approve their own campaign after reviewing it (one login is enough). On: someone else must approve it.',
  'GUARDED', 61, '{"type":"boolean"}')
ON CONFLICT (key) DO NOTHING;
UPDATE config_keys SET default_value = 'false'::jsonb WHERE key = 'approval.four_eyes' AND default_value IS NULL;

-- Every campaign is reviewed now, whatever it costs, so the spend threshold no longer decides anything.
UPDATE config_keys
   SET deprecated = true,
       help_text = 'No longer used (2026-10-15): every campaign is reviewed; approval.four_eyes decides who may approve.'
 WHERE key = 'approval.required_above_paise';
