-- Reset shift configurations to requested three presets
DELETE FROM shift_config_days;
DELETE FROM shift_config;

-- 朝シフト（平日） 09:00-15:00 4人 月-金
INSERT INTO shift_config (name, start_time, end_time, required_employees, is_active, is_weekend, day_of_week, is_holiday, created_at, updated_at)
VALUES ('朝シフト（平日）','09:00:00','15:00:00',4,1,0,NULL,0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'MONDAY');
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'TUESDAY');
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'WEDNESDAY');
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'THURSDAY');
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'FRIDAY');

-- 夜シフト（平日） 15:00-21:00 4人 月-金
INSERT INTO shift_config (name, start_time, end_time, required_employees, is_active, is_weekend, day_of_week, is_holiday, created_at, updated_at)
VALUES ('夜シフト（平日）','15:00:00','21:00:00',4,1,0,NULL,0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'MONDAY');
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'TUESDAY');
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'WEDNESDAY');
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'THURSDAY');
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'FRIDAY');

-- 朝シフト（土日祝） 09:00-18:00 4人 土日＋祝
INSERT INTO shift_config (name, start_time, end_time, required_employees, is_active, is_weekend, day_of_week, is_holiday, created_at, updated_at)
VALUES ('朝シフト（土日祝）','09:00:00','18:00:00',4,1,0,NULL,1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'SATURDAY');
INSERT INTO shift_config_days (shift_config_id, day_of_week) VALUES (last_insert_rowid(), 'SUNDAY');

