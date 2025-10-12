package com.example.shiftv1.employee;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
        Employee employee = request.toEntity();
        Employee savedEmployee = employeeRepository.save(employee);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedEmployee);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Employee> updateEmployee(@PathVariable Long id, @Valid @RequestBody EmployeeRequest request) {
        Optional<Employee> existingEmployee = employeeRepository.findById(id);
        if (existingEmployee.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Employee employee = existingEmployee.get();
        request.applyTo(employee);
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

    public record EmployeeRequest(
            @NotBlank(message = "従業員名は必須です")
            @Size(min = 1, max = 50, message = "従業員名は1文字以上50文字以下で入力してください")
            String name,

            @Size(max = 30, message = "役職は30文字以下で入力してください")
            String role,

            @NotNull(message = "スキルレベルは必須です")
            @Min(value = 1, message = "スキルレベルは1以上である必要があります")
            @Max(value = 5, message = "スキルレベルは5以下である必要があります")
            Integer skillLevel,

            @NotNull(message = "土日勤務可否は必須です")
            Boolean canWorkWeekends,

            @NotNull(message = "夜勤可否は必須です")
            Boolean canWorkEvenings,

            @NotNull(message = "希望勤務日数は必須です")
            @Min(value = 1, message = "希望勤務日数は1以上である必要があります")
            @Max(value = 7, message = "希望勤務日数は7以下である必要があります")
            Integer preferredWorkingDays
    ) {
        public Employee toEntity() {
            return new Employee(name, role, skillLevel, canWorkWeekends, canWorkEvenings, preferredWorkingDays);
        }

        public void applyTo(Employee employee) {
            employee.setName(name);
            employee.setRole(role);
            employee.setSkillLevel(skillLevel);
            employee.setCanWorkWeekends(canWorkWeekends);
            employee.setCanWorkEvenings(canWorkEvenings);
            employee.setPreferredWorkingDays(preferredWorkingDays);
        }
    }
    
    public record EmployeeCountResponse(long count) {}
}
