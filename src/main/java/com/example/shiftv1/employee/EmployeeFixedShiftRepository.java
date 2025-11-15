package com.example.shiftv1.employee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;

public interface EmployeeFixedShiftRepository extends JpaRepository<EmployeeFixedShift, Long> {
    List<EmployeeFixedShift> findByEmployeeId(Long employeeId);
    List<EmployeeFixedShift> findByEmployeeIdIn(List<Long> employeeIds);
    void deleteByEmployeeId(Long employeeId);
    List<EmployeeFixedShift> findByEmployeeIdAndDayOfWeek(Long employeeId, DayOfWeek dayOfWeek);
}
