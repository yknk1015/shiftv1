package com.example.shiftv1.employee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByName(String name);

    long countBySkills_Id(Long skillId);

    Optional<Employee> findTopByOrderByDisplayOrderDescIdDesc();

    default List<Employee> findAllOrdered() {
        List<Employee> employees = findAll();
        employees.sort(Comparator
                .comparing((Employee e) -> e.getDisplayOrder() == null ? Integer.MAX_VALUE : e.getDisplayOrder())
                .thenComparing(e -> e.getId() == null ? Long.MAX_VALUE : e.getId()));
        return employees;
    }
}
