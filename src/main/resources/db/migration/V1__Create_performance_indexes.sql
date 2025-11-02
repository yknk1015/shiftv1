-- パフォーマンス最適化用のインデックス作成

-- シフト割り当てテーブルのインデックス
CREATE INDEX IF NOT EXISTS idx_shift_assignments_work_date ON shift_assignments(work_date);
CREATE INDEX IF NOT EXISTS idx_shift_assignments_employee_id ON shift_assignments(employee_id);
CREATE INDEX IF NOT EXISTS idx_shift_assignments_work_date_employee ON shift_assignments(work_date, employee_id);
CREATE INDEX IF NOT EXISTS idx_shift_assignments_month_year ON shift_assignments(DATE_FORMAT(work_date, '%Y-%m'));

-- 従業員制約テーブルのインデックス
CREATE INDEX IF NOT EXISTS idx_employee_constraints_employee_id ON employee_constraints(employee_id);
CREATE INDEX IF NOT EXISTS idx_employee_constraints_date ON employee_constraints(date);
CREATE INDEX IF NOT EXISTS idx_employee_constraints_employee_date ON employee_constraints(employee_id, date);
CREATE INDEX IF NOT EXISTS idx_employee_constraints_type ON employee_constraints(type);
CREATE INDEX IF NOT EXISTS idx_employee_constraints_active ON employee_constraints(is_active);

-- シフト変更申請テーブルのインデックス
CREATE INDEX IF NOT EXISTS idx_shift_change_requests_requester_id ON shift_change_requests(requester_id);
CREATE INDEX IF NOT EXISTS idx_shift_change_requests_substitute_id ON shift_change_requests(substitute_id);
CREATE INDEX IF NOT EXISTS idx_shift_change_requests_status ON shift_change_requests(status);
CREATE INDEX IF NOT EXISTS idx_shift_change_requests_requested_at ON shift_change_requests(requested_at);
CREATE INDEX IF NOT EXISTS idx_shift_change_requests_original_shift_id ON shift_change_requests(original_shift_id);
CREATE INDEX IF NOT EXISTS idx_shift_change_requests_active ON shift_change_requests(is_active);

-- ユーザーテーブルのインデックス
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);

-- 従業員テーブルのインデックス
CREATE INDEX IF NOT EXISTS idx_employees_name ON employees(name);
CREATE INDEX IF NOT EXISTS idx_employees_role ON employees(role);

-- 複合インデックス（よく使用されるクエリパターン用）
CREATE INDEX IF NOT EXISTS idx_shift_assignments_date_employee_shift ON shift_assignments(work_date, employee_id, shift_name);
CREATE INDEX IF NOT EXISTS idx_employee_constraints_employee_type_active ON employee_constraints(employee_id, type, is_active);
CREATE INDEX IF NOT EXISTS idx_shift_change_requests_status_active ON shift_change_requests(status, is_active);
