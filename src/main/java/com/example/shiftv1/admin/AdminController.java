package com.example.shiftv1.admin;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.schedule.ShiftAssignment;
import com.example.shiftv1.schedule.ShiftAssignmentRepository;
import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
    private final EmployeeRepository employeeRepository;
    private final SkillRepository skillRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final com.example.shiftv1.common.error.ErrorLogBuffer errorLogBuffer;

    public AdminController(EmployeeRepository employeeRepository,
                           SkillRepository skillRepository,
                           ShiftAssignmentRepository assignmentRepository,
                           com.example.shiftv1.common.error.ErrorLogBuffer errorLogBuffer) {
        this.employeeRepository = employeeRepository;
        this.skillRepository = skillRepository;
        this.assignmentRepository = assignmentRepository;
        this.errorLogBuffer = errorLogBuffer;
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<SystemStatusResponse>> getSystemStatus() {
        try {
            long employeeCount = employeeRepository.count();
            boolean hasEmployees = employeeCount > 0;
            return ResponseEntity.ok(ApiResponse.success("system status", new SystemStatusResponse(
                    hasEmployees,
                    employeeCount,
                    hasEmployees ? "employees exist" : "no employees"
            )));
        } catch (Exception e) {
            logger.error("status error", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("failed to get status"));
        }
    }

    @GetMapping("/error-logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getErrorLogs(@RequestParam(name = "limit", required = false) Integer limit) {
        try {
            List<com.example.shiftv1.common.error.ErrorLogBuffer.Entry> list = errorLogBuffer.recent();
            if (limit != null && limit > 0 && list.size() > limit) list = list.subList(0, limit);
            Map<String, Object> resp = new HashMap<>();
            resp.put("count", list.size());
            resp.put("items", list);
            return ResponseEntity.ok(ApiResponse.success("recent error logs", resp));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("failed to get error logs"));
        }
    }

    @GetMapping("/snapshot/employees")
    public ResponseEntity<ApiResponse<Map<String,Object>>> snapshotEmployees() {
        try {
            List<Employee> list = employeeRepository.findAll().stream()
                    .sorted(Comparator.comparing(Employee::getId))
                    .toList();
            List<Map<String,Object>> items = new ArrayList<>();
            for (var e : list) {
                Map<String,Object> row = new HashMap<>();
                row.put("id", e.getId());
                row.put("name", e.getName());
                row.put("role", e.getRole());
                List<Map<String,Object>> skills = new ArrayList<>();
                if (e.getSkills() != null) {
                    for (var s : e.getSkills()) {
                        if (s == null) continue;
                        Map<String,Object> ss = new HashMap<>();
                        ss.put("id", s.getId()); ss.put("code", s.getCode()); ss.put("name", s.getName());
                        skills.add(ss);
                    }
                }
                row.put("skills", skills);
                items.add(row);
            }
            Map<String,Object> resp = new HashMap<>();
            resp.put("count", items.size());
            resp.put("items", items);
            return ResponseEntity.ok(ApiResponse.success("Employees snapshot", resp));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("Failed to build employees snapshot"));
        }
    }

    @GetMapping("/snapshot/assignments")
    public ResponseEntity<ApiResponse<Map<String,Object>>> snapshotAssignments(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = (year != null && month != null)
                    ? YearMonth.of(year, month)
                    : YearMonth.of(LocalDate.now().getYear(), LocalDate.now().getMonthValue());
            LocalDate start = target.atDay(1); LocalDate end = target.atEndOfMonth();
            List<ShiftAssignment> list = assignmentRepository.findByWorkDateBetween(start, end);
            List<Map<String,Object>> items = new ArrayList<>();
            for (var a : list) {
                Map<String,Object> row = new HashMap<>();
                row.put("id", a.getId()); row.put("date", a.getWorkDate());
                row.put("shift", a.getShiftName()); row.put("start", a.getStartTime()); row.put("end", a.getEndTime());
                var emp = a.getEmployee();
                if (emp != null) { row.put("employeeId", emp.getId()); row.put("employeeName", emp.getName()); }
                items.add(row);
            }
            Map<String,Object> resp = new HashMap<>();
            resp.put("year", target.getYear()); resp.put("month", target.getMonthValue());
            resp.put("count", items.size()); resp.put("items", items);
            return ResponseEntity.ok(ApiResponse.success("Assignments snapshot", resp));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("Failed to build assignments snapshot"));
        }
    }

    @PostMapping("/initialize-skills")
    public ResponseEntity<ApiResponse<Map<String,Object>>> initializeSkills() {
        try {
            Skill a = skillRepository.findByCode("A").orElseGet(() -> skillRepository.save(new Skill("A", "Skill A", "Skill A")));
            Skill b = skillRepository.findByCode("B").orElseGet(() -> skillRepository.save(new Skill("B", "Skill B", "Skill B")));
            return ResponseEntity.ok(ApiResponse.success("initialized skills A/B", Map.of("A", a.getId(), "B", b.getId())));
        } catch (Exception e) {
            logger.error("init skills error", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("failed to initialize skills"));
        }
    }

    @PostMapping("/assign-skills-ab-distribution")
    public ResponseEntity<ApiResponse<Map<String,Object>>> assignSkillsABDistribution() {
        try {
            Skill a = skillRepository.findByCode("A").orElseGet(() -> skillRepository.save(new Skill("A", "Skill A", "Skill A")));
            Skill b = skillRepository.findByCode("B").orElseGet(() -> skillRepository.save(new Skill("B", "Skill B", "Skill B")));
            List<Employee> all = employeeRepository.findAll().stream()
                    .sorted(Comparator.comparing(Employee::getId))
                    .toList();
            if (all.size() < 30) {
                return ResponseEntity.badRequest().body(ApiResponse.failure("need >= 30 employees"));
            }
            for (int i=0;i<all.size();i++) {
                Employee e = all.get(i);
                if (i < 10) e.getSkills().add(a);
                else if (i < 20) e.getSkills().add(b);
                else if (i < 30) { e.getSkills().add(a); e.getSkills().add(b); }
            }
            employeeRepository.saveAll(all.subList(0, 30));
            return ResponseEntity.ok(ApiResponse.success("assigned A:10, B:10, A&B:10", Map.of("updated", 30)));
        } catch (Exception e) {
            logger.error("assign skills error", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("failed to assign skills"));
        }
    }

    @PostMapping("/initialize-employees")
    public ResponseEntity<ApiResponse<InitializeResponse>> initializeEmployees() {
        try {
            long existingCount = employeeRepository.count();
            if (existingCount > 0) {
                return ResponseEntity.ok(ApiResponse.success("already initialized", new InitializeResponse(false, existingCount, "already exists")));
            }
            List<Employee> employees = java.util.stream.IntStream.rangeClosed(1, 30)
                    .mapToObj(i -> new Employee("Employee%02d".formatted(i), "Staff"))
                    .toList();
            employeeRepository.saveAll(employees);
            return ResponseEntity.ok(ApiResponse.success("initialized employees", new InitializeResponse(true, employees.size(), "ok")));
        } catch (Exception e) {
            logger.error("init employees error", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("failed to initialize employees"));
        }
    }

    @DeleteMapping("/reset-employees")
    public ResponseEntity<ApiResponse<ResetResponse>> resetEmployees() {
        try {
            long count = employeeRepository.count();
            employeeRepository.deleteAll();
            return ResponseEntity.ok(ApiResponse.success("employees reset", new ResetResponse(count, "deleted")));
        } catch (Exception e) {
            logger.error("reset employees error", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("failed to reset employees"));
        }
    }

    public record SystemStatusResponse(
            boolean hasEmployees,
            long employeeCount,
            String message
    ) {}

    public record InitializeResponse(
            boolean initialized,
            long count,
            String message
    ) {}

    public record ResetResponse(
            long deletedCount,
            String message
    ) {}
}

