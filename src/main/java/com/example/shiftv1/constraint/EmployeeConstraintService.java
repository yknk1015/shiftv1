package com.example.shiftv1.constraint;

import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class EmployeeConstraintService {

    private static final Logger logger = LoggerFactory.getLogger(EmployeeConstraintService.class);

    private final EmployeeConstraintRepository constraintRepository;
    private final EmployeeRepository employeeRepository;

    public EmployeeConstraintService(EmployeeConstraintRepository constraintRepository,
                                   EmployeeRepository employeeRepository) {
        this.constraintRepository = constraintRepository;
        this.employeeRepository = employeeRepository;
    }

    /**
     * 制約を作成
     */
    public EmployeeConstraint createConstraint(Long employeeId, LocalDate date, 
                                             EmployeeConstraint.ConstraintType type, 
                                             String reason) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("従業員が見つかりません: " + employeeId));

        // 既存の制約をチェック
        List<EmployeeConstraint> existingConstraints = constraintRepository
                .findByEmployeeAndDateAndActiveTrue(employee, date);
        
        if (!existingConstraints.isEmpty()) {
            logger.warn("従業員 {} の日付 {} に既に制約が存在します", employee.getName(), date);
        }

        EmployeeConstraint constraint = new EmployeeConstraint(employee, date, type, reason);
        EmployeeConstraint saved = constraintRepository.save(constraint);
        
        logger.info("制約を作成しました: 従業員={}, 日付={}, タイプ={}", 
                   employee.getName(), date, type);
        
        return saved;
    }

    /**
     * 時間指定の制約を作成
     */
    public EmployeeConstraint createTimeConstraint(Long employeeId, LocalDate date, 
                                                 EmployeeConstraint.ConstraintType type, 
                                                 String reason, LocalTime startTime, LocalTime endTime) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("従業員が見つかりません: " + employeeId));

        EmployeeConstraint constraint = new EmployeeConstraint(employee, date, type, reason, startTime, endTime);
        EmployeeConstraint saved = constraintRepository.save(constraint);
        
        logger.info("時間指定制約を作成しました: 従業員={}, 日付={}, タイプ={}, 時間={}-{}", 
                   employee.getName(), date, type, startTime, endTime);
        
        return saved;
    }

    /**
     * 制約を更新
     */
    public EmployeeConstraint updateConstraint(Long constraintId, String reason, 
                                             LocalTime startTime, LocalTime endTime) {
        EmployeeConstraint constraint = constraintRepository.findById(constraintId)
                .orElseThrow(() -> new IllegalArgumentException("制約が見つかりません: " + constraintId));

        constraint.setReason(reason);
        if (startTime != null) constraint.setStartTime(startTime);
        if (endTime != null) constraint.setEndTime(endTime);
        
        EmployeeConstraint saved = constraintRepository.save(constraint);
        
        logger.info("制約を更新しました: ID={}, 従業員={}, 日付={}", 
                   constraintId, constraint.getEmployee().getName(), constraint.getDate());
        
        return saved;
    }

    /**
     * 制約を削除（論理削除）
     */
    public void deleteConstraint(Long constraintId) {
        EmployeeConstraint constraint = constraintRepository.findById(constraintId)
                .orElseThrow(() -> new IllegalArgumentException("制約が見つかりません: " + constraintId));

        constraint.setActive(false);
        constraintRepository.save(constraint);
        
        logger.info("制約を削除しました: ID={}, 従業員={}, 日付={}", 
                   constraintId, constraint.getEmployee().getName(), constraint.getDate());
    }

    /**
     * 従業員の制約一覧を取得
     */
    @Transactional(readOnly = true)
    public List<EmployeeConstraint> getEmployeeConstraints(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("従業員が見つかりません: " + employeeId));

        return constraintRepository.findByEmployeeAndActiveTrue(employee);
    }

    /**
     * 指定日付範囲の制約一覧を取得
     */
    @Transactional(readOnly = true)
    public List<EmployeeConstraint> getConstraintsByDateRange(LocalDate startDate, LocalDate endDate) {
        return constraintRepository.findByDateBetweenAndActiveTrue(startDate, endDate);
    }

    /**
     * 指定従業員の指定日付範囲の制約一覧を取得
     */
    @Transactional(readOnly = true)
    public List<EmployeeConstraint> getEmployeeConstraintsByDateRange(Long employeeId, 
                                                                     LocalDate startDate, LocalDate endDate) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("従業員が見つかりません: " + employeeId));

        return constraintRepository.findByEmployeeAndDateBetweenAndActiveTrue(employee, startDate, endDate);
    }

    /**
     * 指定日付で勤務不可の従業員一覧を取得
     */
    @Transactional(readOnly = true)
    public List<Employee> getUnavailableEmployees(LocalDate date) {
        return constraintRepository.findUnavailableEmployeesByDate(date);
    }

    /**
     * 従業員が指定日付で勤務可能かチェック
     */
    @Transactional(readOnly = true)
    public boolean isEmployeeAvailable(Long employeeId, LocalDate date) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("従業員が見つかりません: " + employeeId));

        List<EmployeeConstraint> unavailableConstraints = constraintRepository
                .findUnavailableConstraintsByEmployeeAndDate(employee, date);
        
        return unavailableConstraints.isEmpty();
    }

    /**
     * 従業員が指定日時のシフトで勤務可能かチェック
     */
    @Transactional(readOnly = true)
    public boolean isEmployeeAvailableForShift(Long employeeId, LocalDate date, 
                                              LocalTime shiftStartTime, LocalTime shiftEndTime) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("従業員が見つかりません: " + employeeId));

        // 勤務不可制約をチェック
        List<EmployeeConstraint> unavailableConstraints = constraintRepository
                .findUnavailableConstraintsByEmployeeAndDate(employee, date);
        
        if (!unavailableConstraints.isEmpty()) {
            return false;
        }

        // 時間制限をチェック
        List<EmployeeConstraint> constraints = constraintRepository
                .findByEmployeeAndDateAndActiveTrue(employee, date);
        
        for (EmployeeConstraint constraint : constraints) {
            if (constraint.getType() == EmployeeConstraint.ConstraintType.LIMITED) {
                LocalTime constraintStart = constraint.getStartTime();
                LocalTime constraintEnd = constraint.getEndTime();
                
                if (constraintStart != null && constraintEnd != null) {
                    // シフト時間が制約時間と重複するかチェック
                    if (shiftOverlapsWithConstraint(shiftStartTime, shiftEndTime, constraintStart, constraintEnd)) {
                        return false;
                    }
                }
            }
        }
        
        return true;
    }

    /**
     * シフト時間と制約時間の重複チェック
     */
    private boolean shiftOverlapsWithConstraint(LocalTime shiftStart, LocalTime shiftEnd,
                                              LocalTime constraintStart, LocalTime constraintEnd) {
        return shiftStart.isBefore(constraintEnd) && shiftEnd.isAfter(constraintStart);
    }

    /**
     * 制約統計を取得
     */
    @Transactional(readOnly = true)
    public List<Object[]> getConstraintStatistics(LocalDate startDate, LocalDate endDate) {
        return constraintRepository.countConstraintsByTypeAndDateRange(startDate, endDate);
    }

    /**
     * 制約を取得
     */
    @Transactional(readOnly = true)
    public Optional<EmployeeConstraint> getConstraint(Long constraintId) {
        return constraintRepository.findById(constraintId);
    }
}
