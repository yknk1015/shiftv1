package com.example.shiftv1.schedule;

import com.example.shiftv1.employee.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ShiftAssignmentRepository extends JpaRepository<ShiftAssignment, Long> {

    /**
     * 指定日付範囲のシフト割り当てを取得
     */
    List<ShiftAssignment> findByWorkDateBetween(LocalDate startDate, LocalDate endDate);

    /**
     * 指定日付のシフト割り当てを取得
     */
    List<ShiftAssignment> findByWorkDate(LocalDate workDate);

    /**
     * 指定従業員のシフト割り当てを取得
     */
    List<ShiftAssignment> findByEmployee(Employee employee);

    /**
     * 指定従業員の指定日付範囲のシフト割り当てを取得
     */
    List<ShiftAssignment> findByEmployeeAndWorkDateBetween(Employee employee, LocalDate startDate, LocalDate endDate);

    /**
     * 指定従業員の指定日付のシフト割り当てを取得
     */
    List<ShiftAssignment> findByEmployeeAndWorkDate(Employee employee, LocalDate workDate);

    /**
     * 指定日付範囲のシフト割り当てを削除
     */
    void deleteByWorkDateBetween(LocalDate startDate, LocalDate endDate);

    /**
     * 指定従業員の指定日付範囲のシフト割り当てを削除
     */
    void deleteByEmployeeAndWorkDateBetween(Employee employee, LocalDate startDate, LocalDate endDate);

    /**
     * 月別シフト割り当て数を取得
     */
    @Query("SELECT COUNT(sa) FROM ShiftAssignment sa WHERE sa.workDate BETWEEN :startDate AND :endDate")
    long countByWorkDateBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * 従業員別月間勤務日数を取得
     */
    @Query("SELECT sa.employee.id, sa.employee.name, COUNT(sa) FROM ShiftAssignment sa " +
           "WHERE sa.workDate BETWEEN :startDate AND :endDate " +
           "GROUP BY sa.employee.id, sa.employee.name")
    List<Object[]> countMonthlyWorkDaysByEmployee(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * シフトタイプ別の割り当て数を取得
     */
    @Query("SELECT sa.shiftName, COUNT(sa) FROM ShiftAssignment sa " +
           "WHERE sa.workDate BETWEEN :startDate AND :endDate " +
           "GROUP BY sa.shiftName")
    List<Object[]> countShiftsByTypeAndDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * 指定従業員の連続勤務日数を取得
     */
    @Query("SELECT COUNT(*) FROM ShiftAssignment sa WHERE sa.employee = :employee " +
           "AND sa.workDate BETWEEN :startDate AND :endDate " +
           "ORDER BY sa.workDate DESC")
    long countConsecutiveWorkDays(@Param("employee") Employee employee, 
                                 @Param("startDate") LocalDate startDate, 
                                 @Param("endDate") LocalDate endDate);

    /**
     * 指定日付の勤務可能な従業員を取得（重複する時間帯のシフトがない従業員）
     */
    @Query("SELECT DISTINCT e FROM Employee e WHERE e NOT IN " +
           "(SELECT DISTINCT sa.employee FROM ShiftAssignment sa WHERE sa.workDate = :workDate " +
           "AND ((sa.startTime < :endTime AND sa.endTime > :startTime)))")
    List<Employee> findAvailableEmployeesForTimeSlot(@Param("workDate") LocalDate workDate,
                                                    @Param("startTime") java.time.LocalTime startTime,
                                                    @Param("endTime") java.time.LocalTime endTime);

    /**
     * 最新のシフト割り当てを取得
     */
    java.util.Optional<ShiftAssignment> findTopByOrderByWorkDateDesc();

    /**
     * 最古のシフト割り当てを取得
     */
    java.util.Optional<ShiftAssignment> findTopByOrderByWorkDateAsc();
}