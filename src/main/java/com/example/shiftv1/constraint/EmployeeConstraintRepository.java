package com.example.shiftv1.constraint;

import com.example.shiftv1.employee.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EmployeeConstraintRepository extends JpaRepository<EmployeeConstraint, Long> {

    /**
     * 指定された従業員の制約を取得
     */
    List<EmployeeConstraint> findByEmployeeAndActiveTrue(Employee employee);

    /**
     * 指定された日付範囲の制約を取得
     */
    List<EmployeeConstraint> findByDateBetweenAndActiveTrue(LocalDate startDate, LocalDate endDate);

    /**
     * 指定された従業員の指定日付範囲の制約を取得
     */
    List<EmployeeConstraint> findByEmployeeAndDateBetweenAndActiveTrue(
            Employee employee, LocalDate startDate, LocalDate endDate);

    /**
     * 指定された従業員の指定日付の制約を取得
     */
    List<EmployeeConstraint> findByEmployeeAndDateAndActiveTrue(Employee employee, LocalDate date);

    /**
     * 指定された従業員の指定日付の勤務不可制約を取得
     */
    @Query("SELECT ec FROM EmployeeConstraint ec WHERE ec.employee = :employee " +
           "AND ec.date = :date AND ec.type = 'UNAVAILABLE' AND ec.active = true")
    List<EmployeeConstraint> findUnavailableConstraintsByEmployeeAndDate(
            @Param("employee") Employee employee, @Param("date") LocalDate date);

    /**
     * 指定された従業員の指定日付の希望制約を取得
     */
    @Query("SELECT ec FROM EmployeeConstraint ec WHERE ec.employee = :employee " +
           "AND ec.date = :date AND ec.type = 'PREFERRED' AND ec.active = true")
    List<EmployeeConstraint> findPreferredConstraintsByEmployeeAndDate(
            @Param("employee") Employee employee, @Param("date") LocalDate date);

    /**
     * 指定された従業員の指定日付範囲の勤務不可制約を取得
     */
    @Query("SELECT ec FROM EmployeeConstraint ec WHERE ec.employee = :employee " +
           "AND ec.date BETWEEN :startDate AND :endDate " +
           "AND ec.type = 'UNAVAILABLE' AND ec.active = true")
    List<EmployeeConstraint> findUnavailableConstraintsByEmployeeAndDateRange(
            @Param("employee") Employee employee, 
            @Param("startDate") LocalDate startDate, 
            @Param("endDate") LocalDate endDate);

    /**
     * 指定された日付の勤務不可制約を持つ従業員を取得
     */
    @Query("SELECT ec.employee FROM EmployeeConstraint ec WHERE ec.date = :date " +
           "AND ec.type = 'UNAVAILABLE' AND ec.active = true")
    List<Employee> findUnavailableEmployeesByDate(@Param("date") LocalDate date);

    /**
     * 制約タイプ別の制約数を取得
     */
    @Query("SELECT ec.type, COUNT(ec) FROM EmployeeConstraint ec " +
           "WHERE ec.date BETWEEN :startDate AND :endDate AND ec.active = true " +
           "GROUP BY ec.type")
    List<Object[]> countConstraintsByTypeAndDateRange(
            @Param("startDate") LocalDate startDate, 
            @Param("endDate") LocalDate endDate);

    /**
     * 指定された従業員の制約数を取得
     */
    long countByEmployeeAndActiveTrue(Employee employee);

    /**
     * 指定された従業員の指定日付範囲の制約数を取得
     */
    long countByEmployeeAndDateBetweenAndActiveTrue(Employee employee, LocalDate startDate, LocalDate endDate);
}
