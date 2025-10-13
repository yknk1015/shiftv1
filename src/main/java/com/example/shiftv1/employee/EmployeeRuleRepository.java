package com.example.shiftv1.employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmployeeRuleRepository extends JpaRepository<EmployeeRule, Long> {
    Optional<EmployeeRule> findByEmployeeId(Long employeeId);
}

