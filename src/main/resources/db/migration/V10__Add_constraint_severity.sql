-- Add severity (HARD/SOFT) to employee_constraints
ALTER TABLE employee_constraints ADD COLUMN IF NOT EXISTS severity VARCHAR(10) DEFAULT 'SOFT';

