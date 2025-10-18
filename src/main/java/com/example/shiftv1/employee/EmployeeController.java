package com.example.shiftv1.employee;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;
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
    private final SkillRepository skillRepository;

    public EmployeeController(EmployeeRepository employeeRepository, SkillRepository skillRepository) {
        this.employeeRepository = employeeRepository;
        this.skillRepository = skillRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Employee>>> getAllEmployees() {
        List<Employee> employees = employeeRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success("従業員一覧を取得しました", employees));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Employee>> getEmployee(@PathVariable Long id) {
        Optional<Employee> employee = employeeRepository.findById(id);
        return employee
                .map(value -> ResponseEntity.ok(ApiResponse.success("従業員を取得しました", value)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.failure("従業員が見つかりません")));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Employee>> createEmployee(@Valid @RequestBody EmployeeRequest request) {
        try {
            Employee employee = new Employee(request.name(), request.role());
            Employee savedEmployee = employeeRepository.save(employee);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success("従業員を作成しました", savedEmployee));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("従業員の作成に失敗しました"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Employee>> updateEmployee(@PathVariable Long id, @Valid @RequestBody EmployeeRequest request) {
        Optional<Employee> existingEmployee = employeeRepository.findById(id);
        if (existingEmployee.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.failure("従業員が見つかりません"));
        }

        Employee employee = existingEmployee.get();
        employee.setName(request.name());
        employee.setRole(request.role());
        Employee updatedEmployee = employeeRepository.save(employee);
        return ResponseEntity.ok(ApiResponse.success("従業員を更新しました", updatedEmployee));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteEmployee(@PathVariable Long id) {
        if (!employeeRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.failure("従業員が見つかりません"));
        }
        employeeRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("従業員を削除しました", null));
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<EmployeeCountResponse>> getEmployeeCount() {
        long count = employeeRepository.count();
        return ResponseEntity.ok(ApiResponse.success("従業員数を取得しました", new EmployeeCountResponse(count)));
    }

    public record EmployeeRequest(String name, String role) {}
    
    public record EmployeeCountResponse(long count) {}

    // ===== Skill management for Employee =====
    @PostMapping("/{id}/skills/{skillId}")
    public ResponseEntity<ApiResponse<Employee>> addSkill(@PathVariable Long id, @PathVariable Long skillId) {
        Optional<Employee> emp = employeeRepository.findById(id);
        if (emp.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません"));
        Optional<Skill> sk = skillRepository.findById(skillId);
        if (sk.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルが見つかりません"));
        Employee e = emp.get();
        e.getSkills().add(sk.get());
        return ResponseEntity.ok(ApiResponse.success("スキルを付与しました", employeeRepository.save(e)));
    }

    @DeleteMapping("/{id}/skills/{skillId}")
    public ResponseEntity<ApiResponse<Employee>> removeSkill(@PathVariable Long id, @PathVariable Long skillId) {
        Optional<Employee> emp = employeeRepository.findById(id);
        if (emp.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません"));
        Optional<Skill> sk = skillRepository.findById(skillId);
        if (sk.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルが見つかりません"));
        Employee e = emp.get();
        e.getSkills().remove(sk.get());
        return ResponseEntity.ok(ApiResponse.success("スキルを解除しました", employeeRepository.save(e)));
    }
}
