package com.example.shiftv1.leave;

import com.example.shiftv1.employee.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {
    Optional<LeaveBalance> findTopByEmployeeOrderByIdDesc(Employee employee);
}

