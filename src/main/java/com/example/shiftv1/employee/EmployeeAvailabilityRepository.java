package com.example.shiftv1.employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.DayOfWeek;
import java.util.List;

@Repository
public interface EmployeeAvailabilityRepository extends JpaRepository<EmployeeAvailability, Long> {
    List<EmployeeAvailability> findByEmployeeId(Long employeeId);
    List<EmployeeAvailability> findByEmployeeIdAndDayOfWeek(Long employeeId, DayOfWeek dayOfWeek);
}

