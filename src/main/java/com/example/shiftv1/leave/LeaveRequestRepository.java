package com.example.shiftv1.leave;

import com.example.shiftv1.employee.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
    List<LeaveRequest> findByEmployeeAndDateBetween(Employee employee, LocalDate start, LocalDate end);
}

