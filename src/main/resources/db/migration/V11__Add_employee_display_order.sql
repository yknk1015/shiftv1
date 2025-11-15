ALTER TABLE employees ADD COLUMN display_order INTEGER DEFAULT 0;
UPDATE employees
SET display_order = COALESCE(display_order, id);
