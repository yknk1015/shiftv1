package com.example.shiftv1.employee;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeRepository employeeRepository;
    private final SkillRepository skillRepository;
    private static final Logger logger = LoggerFactory.getLogger(EmployeeController.class);

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
            String name = request.name() == null ? null : request.name().trim();
            String role = request.role() == null ? null : request.role().trim();
            if (role != null && role.isBlank()) role = null;
            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest().body(ApiResponse.failure("氏名は必須です"));
            }
            if (employeeRepository.findByName(name).isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure("同名の従業員が既に存在します"));
            }
            Employee employee = new Employee(name, role);
            Integer pr = request.assignPriority();
            employee.setAssignPriority(clampPriority(pr == null ? 3 : pr));
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
        String name = request.name() == null ? null : request.name().trim();
        String role = request.role() == null ? null : request.role().trim();
        if (role != null && role.isBlank()) role = null;
        employee.setName(name);
        employee.setRole(role);
        if (request.assignPriority() != null) {
            employee.setAssignPriority(clampPriority(request.assignPriority()));
        }
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

    public record EmployeeRequest(String name, String role, Integer assignPriority) {}

    // Phase A: work preferences payload
    public static class WorkPrefsRequest {
        public Boolean eligibleFull;
        public Boolean eligibleShortMorning;
        public Boolean eligibleShortAfternoon;
        public Boolean overtimeAllowed;
        public Integer overtimeDailyMaxHours;
        public Integer overtimeWeeklyMaxHours;
    }

    public static class PriorityRequest { public Integer assignPriority; }

    @PutMapping("/{id}/priority")
    public ResponseEntity<ApiResponse<Employee>> updatePriority(@PathVariable Long id, @RequestBody PriorityRequest req) {
        if (req == null || req.assignPriority == null) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("優先度が指定されていません"));
        }
        Optional<Employee> existingEmployee = employeeRepository.findById(id);
        if (existingEmployee.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません"));
        }
        Employee e = existingEmployee.get();
        e.setAssignPriority(clampPriority(req.assignPriority));
        return ResponseEntity.ok(ApiResponse.success("優先度を更新しました", employeeRepository.save(e)));
    }

    private int clampPriority(Integer p) {
        if (p == null) return 3; // default mid for 1-5
        return Math.max(1, Math.min(5, p));
    }

    @PutMapping("/{id}/work-prefs")
    public ResponseEntity<ApiResponse<Employee>> updateWorkPrefs(@PathVariable Long id, @RequestBody WorkPrefsRequest req) {
        Optional<Employee> opt = employeeRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.failure("従業員が見つかりません"));
        }
        Employee e = opt.get();
        if (req.eligibleFull != null) e.setEligibleFull(req.eligibleFull);
        if (req.eligibleShortMorning != null) e.setEligibleShortMorning(req.eligibleShortMorning);
        if (req.eligibleShortAfternoon != null) e.setEligibleShortAfternoon(req.eligibleShortAfternoon);
        if (req.overtimeAllowed != null) e.setOvertimeAllowed(req.overtimeAllowed);
        if (req.overtimeDailyMaxHours != null) e.setOvertimeDailyMaxHours(req.overtimeDailyMaxHours);
        if (req.overtimeWeeklyMaxHours != null) e.setOvertimeWeeklyMaxHours(req.overtimeWeeklyMaxHours);
        return ResponseEntity.ok(ApiResponse.success("勤務適格性・残業設定を更新しました", employeeRepository.save(e)));
    }

    public record EmployeeCountResponse(long count) {}

    // ===== Skill management for Employee =====
    @GetMapping("/{id}/skills")
    public ResponseEntity<ApiResponse<java.util.Set<Skill>>> listSkills(@PathVariable Long id) {
        Optional<Employee> emp = employeeRepository.findById(id);
        if (emp.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません"));
        return ResponseEntity.ok(ApiResponse.success("スキル一覧を取得しました", emp.get().getSkills()));
    }

    @PutMapping("/{id}/skills")
    @Transactional
    public ResponseEntity<ApiResponse<Employee>> setSkills(@PathVariable Long id, @RequestBody java.util.List<Long> skillIds) {
        Optional<Employee> emp = employeeRepository.findById(id);
        if (emp.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません"));
        java.util.Set<Skill> skills = new java.util.HashSet<>();
        if (skillIds != null) {
            for (Long sid : skillIds) {
                skillRepository.findById(sid).ifPresent(skills::add);
            }
        }
        Employee e = emp.get();
        logger.info("Setting skills for employee {} -> {} ids", e.getId(), (skillIds==null?0:skillIds.size()));
        e.setSkills(skills);
        Employee saved = employeeRepository.save(e);
        logger.info("Updated skills count: {}", saved.getSkills()==null?0:saved.getSkills().size());
        return ResponseEntity.ok(ApiResponse.success("スキルを更新しました", saved));
    }
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
