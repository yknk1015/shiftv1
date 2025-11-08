-- Add work/off pattern fields to employee_rules
ALTER TABLE employee_rules ADD COLUMN IF NOT EXISTS work_off_pattern VARCHAR(255);
ALTER TABLE employee_rules ADD COLUMN IF NOT EXISTS pattern_anchor_date DATE;
ALTER TABLE employee_rules ADD COLUMN IF NOT EXISTS pattern_strict BOOLEAN DEFAULT FALSE;

