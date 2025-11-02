package com.example.shiftv1.employee;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.IntStream;

@Configuration
public class EmployeeDataInitializer {

    // 起動時に従業員が0件ならデモ用に自動投入します
    @Bean
    CommandLineRunner loadEmployees(EmployeeRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            List<Employee> employees = IntStream.rangeClosed(1, 30)
                    .mapToObj(i -> new Employee("Employee %02d".formatted(i), "Staff"))
                    .toList();
            repository.saveAll(employees);
        };
    }
}

