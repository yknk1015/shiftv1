package com.example.shiftv1.employee;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeRepository employeeRepository;

    public EmployeeController(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    @GetMapping
    public ResponseEntity<List<Employee>> getAllEmployees() {
        List<Employee> employees = employeeRepository.findAll();
        return ResponseEntity.ok(employees);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Employee> getEmployee(@PathVariable Long id) {
        Optional<Employee> employee = employeeRepository.findById(id);
        return employee.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Employee> createEmployee(@Valid @RequestBody EmployeeRequest request) {
        try {
            Employee employee = new Employee(request.name(), request.role());
            Employee savedEmployee = employeeRepository.save(employee);
            return ResponseEntity.status(HttpStatus.CREATED).body(savedEmployee);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Employee> updateEmployee(@PathVariable Long id, @Valid @RequestBody EmployeeRequest request) {
        Optional<Employee> existingEmployee = employeeRepository.findById(id);
        if (existingEmployee.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Employee employee = existingEmployee.get();
        employee.setName(request.name());
        employee.setRole(request.role());
        Employee updatedEmployee = employeeRepository.save(employee);
        return ResponseEntity.ok(updatedEmployee);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEmployee(@PathVariable Long id) {
        if (!employeeRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        employeeRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/count")
    public ResponseEntity<EmployeeCountResponse> getEmployeeCount() {
        long count = employeeRepository.count();
        return ResponseEntity.ok(new EmployeeCountResponse(count));
    }

    public record EmployeeRequest(String name, String role) {}
    
    public record EmployeeCountResponse(long count) {}
}
