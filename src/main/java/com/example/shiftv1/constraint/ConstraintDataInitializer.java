package com.example.shiftv1.constraint;

import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;

@Component
public class ConstraintDataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ConstraintDataInitializer.class);

    private final EmployeeConstraintRepository constraintRepository;
    private final EmployeeRepository employeeRepository;

    public ConstraintDataInitializer(EmployeeConstraintRepository constraintRepository,
                                   EmployeeRepository employeeRepository) {
        this.constraintRepository = constraintRepository;
        this.employeeRepository = employeeRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        if (constraintRepository.count() == 0) {
            logger.info("制約データの初期化を開始します");
            initializeConstraintData();
            logger.info("制約データの初期化が完了しました");
        } else {
            logger.info("制約データは既に存在します");
        }
    }

    private void initializeConstraintData() {
        // サンプルの従業員制約データを作成
        var employees = employeeRepository.findAll();
        if (employees.isEmpty()) {
            logger.warn("従業員データが見つかりません。制約データの初期化をスキップします。");
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate nextWeek = today.plusWeeks(1);

        // 各従業員にサンプル制約を設定
        for (int i = 0; i < employees.size(); i++) {
            Employee employee = employees.get(i);
            
            // 今週の土曜日に勤務不可制約
            LocalDate saturday = today.plusDays((5 - today.getDayOfWeek().getValue() + 7) % 7);
            if (saturday.isAfter(today.minusDays(1))) {
                EmployeeConstraint unavailableConstraint = new EmployeeConstraint(
                        employee,
                        saturday,
                        EmployeeConstraint.ConstraintType.UNAVAILABLE,
                        "私用のため勤務不可"
                );
                constraintRepository.save(unavailableConstraint);
            }

            // 来週の希望シフト（時間指定）
            if (i % 2 == 0) {
                EmployeeConstraint preferredConstraint = new EmployeeConstraint(
                        employee,
                        nextWeek.plusDays(i % 7),
                        EmployeeConstraint.ConstraintType.PREFERRED,
                        "希望シフト",
                        LocalTime.of(9, 0),
                        LocalTime.of(15, 0)
                );
                constraintRepository.save(preferredConstraint);
            }

            // 制限ありの制約（月曜日の午後のみ勤務可能）
            LocalDate monday = today.plusDays((1 - today.getDayOfWeek().getValue() + 7) % 7);
            if (monday.isAfter(today.minusDays(1))) {
                EmployeeConstraint limitedConstraint = new EmployeeConstraint(
                        employee,
                        monday,
                        EmployeeConstraint.ConstraintType.LIMITED,
                        "午後のみ勤務可能",
                        LocalTime.of(13, 0),
                        LocalTime.of(21, 0)
                );
                constraintRepository.save(limitedConstraint);
            }
        }

        logger.info("{}名の従業員に制約データを設定しました", employees.size());
    }
}
