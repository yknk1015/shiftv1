-- Add flags for FREE and OFF placeholders
ALTER TABLE shift_assignments ADD COLUMN IF NOT EXISTS is_free BOOLEAN DEFAULT FALSE;
ALTER TABLE shift_assignments ADD COLUMN IF NOT EXISTS is_off BOOLEAN DEFAULT FALSE;

-- Backfill from existing names
UPDATE shift_assignments SET is_free = TRUE WHERE UPPER(shift_name) = 'FREE';
UPDATE shift_assignments SET is_off = TRUE WHERE shift_name = '休日' OR UPPER(shift_name) = 'OFF';

