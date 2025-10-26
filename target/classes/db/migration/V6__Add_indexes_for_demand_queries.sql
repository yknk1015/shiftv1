-- Indexes to speed up demand lookups used during schedule generation
-- Filter patterns: (date = ?) OR (date IS NULL AND day_of_week = ?) AND (is_active = 1 OR is_active IS NULL) AND skill_id IS NOT NULL

CREATE INDEX IF NOT EXISTS idx_demand_intervals_date ON demand_intervals(date);
CREATE INDEX IF NOT EXISTS idx_demand_intervals_day_of_week ON demand_intervals(day_of_week);
CREATE INDEX IF NOT EXISTS idx_demand_intervals_is_active ON demand_intervals(is_active);
CREATE INDEX IF NOT EXISTS idx_demand_intervals_skill_id ON demand_intervals(skill_id);

-- Composite indexes to help common predicates
CREATE INDEX IF NOT EXISTS idx_demand_intervals_date_active_skill ON demand_intervals(date, is_active, skill_id);
CREATE INDEX IF NOT EXISTS idx_demand_intervals_dow_active_skill ON demand_intervals(day_of_week, is_active, skill_id);

