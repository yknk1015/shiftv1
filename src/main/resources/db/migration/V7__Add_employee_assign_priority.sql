-- Add assign priority column to employees
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS assign_priority INTEGER DEFAULT 100;

-- Backfill nulls to default for existing rows
UPDATE employees SET assign_priority = 100 WHERE assign_priority IS NULL;

