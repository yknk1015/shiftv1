-- Fix shift configs: correct days linkage and holiday capacity
DELETE FROM shift_config_days;
DELETE FROM shift_config;

-- 平日: 朝シフト（平日） 09:00-15:00 4人 月-金
INSERT INTO shift_config (name, start_time, end_time, required_employees, is_active, is_weekend, day_of_week, is_holiday, created_at, updated_at)
VALUES ('朝シフト（平日）','09:00:00','15:00:00',4,1,0,NULL,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'MONDAY' FROM shift_config WHERE name='朝シフト（平日）';
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'TUESDAY' FROM shift_config WHERE name='朝シフト（平日）';
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'WEDNESDAY' FROM shift_config WHERE name='朝シフト（平日）';
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'THURSDAY' FROM shift_config WHERE name='朝シフト（平日）';
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'FRIDAY' FROM shift_config WHERE name='朝シフト（平日）';

-- 平日: 夜シフト（平日） 15:00-21:00 4人 月-金
INSERT INTO shift_config (name, start_time, end_time, required_employees, is_active, is_weekend, day_of_week, is_holiday, created_at, updated_at)
VALUES ('夜シフト（平日）','15:00:00','21:00:00',4,1,0,NULL,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'MONDAY' FROM shift_config WHERE name='夜シフト（平日）';
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'TUESDAY' FROM shift_config WHERE name='夜シフト（平日）';
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'WEDNESDAY' FROM shift_config WHERE name='夜シフト（平日）';
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'THURSDAY' FROM shift_config WHERE name='夜シフト（平日）';
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'FRIDAY' FROM shift_config WHERE name='夜シフト（平日）';

-- 土日祝: 朝シフト（土日祝） 09:00-18:00 5人 土日＋祝
INSERT INTO shift_config (name, start_time, end_time, required_employees, is_active, is_weekend, day_of_week, is_holiday, created_at, updated_at)
VALUES ('朝シフト（土日祝）','09:00:00','18:00:00',5,1,0,NULL,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'SATURDAY' FROM shift_config WHERE name='朝シフト（土日祝）';
INSERT INTO shift_config_days (shift_config_id, day_of_week)
SELECT id, 'SUNDAY' FROM shift_config WHERE name='朝シフト（土日祝）';

