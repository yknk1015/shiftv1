-- Add sort_order column to control manual ordering of demand rows
ALTER TABLE demand_intervals ADD COLUMN sort_order INTEGER;

-- Initialize sort_order to current id for existing rows
UPDATE demand_intervals SET sort_order = id WHERE sort_order IS NULL;

-- Optional index to speed ordering
CREATE INDEX IF NOT EXISTS idx_demand_intervals_sort_order ON demand_intervals(sort_order);

