package com.example.shiftv1.shiftchange;

import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.schedule.ShiftAssignment;
import com.example.shiftv1.schedule.ShiftAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ShiftChangeDataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ShiftChangeDataInitializer.class);

    private final ShiftChangeRequestRepository changeRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final ShiftAssignmentRepository shiftAssignmentRepository;

    public ShiftChangeDataInitializer(ShiftChangeRequestRepository changeRequestRepository,
                                    EmployeeRepository employeeRepository,
                                    ShiftAssignmentRepository shiftAssignmentRepository) {
        this.changeRequestRepository = changeRequestRepository;
        this.employeeRepository = employeeRepository;
        this.shiftAssignmentRepository = shiftAssignmentRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        if (changeRequestRepository.count() == 0) {
            logger.info("シフト変更申請データの初期化を開始します");
            initializeShiftChangeData();
            logger.info("シフト変更申請データの初期化が完了しました");
        } else {
            logger.info("シフト変更申請データは既に存在します");
        }
    }

    private void initializeShiftChangeData() {
        // サンプルのシフト変更申請データを作成
        var employees = employeeRepository.findAll();
        var shifts = shiftAssignmentRepository.findAll();
        
        if (employees.isEmpty() || shifts.isEmpty()) {
            logger.warn("従業員またはシフトデータが見つかりません。変更申請データの初期化をスキップします。");
            return;
        }

        // 最初の2つのシフトに変更申請を作成
        for (int i = 0; i < Math.min(2, shifts.size()); i++) {
            ShiftAssignment shift = shifts.get(i);
            Employee requester = shift.getEmployee();
            
            // 他の従業員を代行者として選択
            Employee substitute = employees.stream()
                    .filter(emp -> !emp.getId().equals(requester.getId()))
                    .findFirst()
                    .orElse(null);
            
            if (substitute != null) {
                ShiftChangeRequest changeRequest = new ShiftChangeRequest(
                        shift,
                        requester,
                        substitute,
                        "体調不良のため代行をお願いします"
                );
                changeRequestRepository.save(changeRequest);
                
                logger.info("シフト変更申請を作成しました: 申請者={}, 代行者={}, 日付={}", 
                           requester.getName(), substitute.getName(), shift.getWorkDate());
            }
        }

        logger.info("{}件のシフト変更申請データを作成しました", Math.min(2, shifts.size()));
    }
}
