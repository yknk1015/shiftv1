package com.example.shiftv1.admin;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
    private final EmployeeRepository employeeRepository;
    private final SkillRepository skillRepository;

    public AdminController(EmployeeRepository employeeRepository, SkillRepository skillRepository) {
        this.employeeRepository = employeeRepository;
        this.skillRepository = skillRepository;
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<SystemStatusResponse>> getSystemStatus() {
        try {
            logger.info("システム状態確認を開始します");
            long employeeCount = employeeRepository.count();
            boolean hasEmployees = employeeCount > 0;

            logger.info("従業員数: {}", employeeCount);

            return ResponseEntity.ok(ApiResponse.success("システム状態を取得しました", new SystemStatusResponse(
                    hasEmployees,
                    employeeCount,
                    hasEmployees ? "システムは正常に動作しています" : "従業員データが不足しています"
            )));
        } catch (Exception e) {
            logger.error("システム状態確認でエラーが発生しました", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("システム状態の取得に失敗しました"));
        }
    }

    @PostMapping("/initialize-skills")
    public ResponseEntity<ApiResponse<java.util.Map<String,Object>>> initializeSkills() {
        try {
            Skill a = skillRepository.findByCode("A").orElseGet(() -> skillRepository.save(new Skill("A", "Skill A", "基本スキルA")));
            Skill b = skillRepository.findByCode("B").orElseGet(() -> skillRepository.save(new Skill("B", "Skill B", "基本スキルB")));
            return ResponseEntity.ok(ApiResponse.success("スキルA/Bを初期化しました", java.util.Map.of("A", a.getId(), "B", b.getId())));
        } catch (Exception e) {
            logger.error("スキル初期化でエラー", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("スキル初期化に失敗しました"));
        }
    }

    @PostMapping("/assign-skills-ab-distribution")
    public ResponseEntity<ApiResponse<java.util.Map<String,Object>>> assignSkillsABDistribution() {
        try {
            Skill a = skillRepository.findByCode("A").orElseGet(() -> skillRepository.save(new Skill("A", "Skill A", "基本スキルA")));
            Skill b = skillRepository.findByCode("B").orElseGet(() -> skillRepository.save(new Skill("B", "Skill B", "基本スキルB")));
            java.util.List<Employee> all = employeeRepository.findAll().stream()
                    .sorted(java.util.Comparator.comparing(Employee::getId))
                    .toList();
            if (all.size() < 30) {
                return ResponseEntity.badRequest().body(ApiResponse.failure("従業員が30名以上必要です"));
            }
            for (int i=0;i<all.size();i++) {
                Employee e = all.get(i);
                if (i < 10) {
                    e.getSkills().add(a);
                } else if (i < 20) {
                    e.getSkills().add(b);
                } else if (i < 30) {
                    e.getSkills().add(a);
                    e.getSkills().add(b);
                }
            }
            employeeRepository.saveAll(all.subList(0, 30));
            return ResponseEntity.ok(ApiResponse.success("A:10, B:10, A&B:10 を付与しました", java.util.Map.of("updated", 30)));
        } catch (Exception e) {
            logger.error("スキル付与でエラー", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("スキル付与に失敗しました"));
        }
    }

    @PostMapping("/initialize-employees")
    public ResponseEntity<ApiResponse<InitializeResponse>> initializeEmployees() {
        try {
            logger.info("従業員初期化を開始します");
            long existingCount = employeeRepository.count();

            if (existingCount > 0) {
                logger.info("従業員は既に初期化されています: {}名", existingCount);
                return ResponseEntity.ok(ApiResponse.success("従業員は既に初期化されています", new InitializeResponse(
                        false,
                        existingCount,
                        "従業員は既に初期化されています"
                )));
            }

            logger.info("30名の従業員を作成します");
            List<Employee> employees = java.util.stream.IntStream.rangeClosed(1, 30)
                    .mapToObj(i -> new Employee("従業員%02d".formatted(i), "スタッフ"))
                    .toList();

            employeeRepository.saveAll(employees);
            logger.info("従業員初期化が完了しました: {}名", employees.size());

            return ResponseEntity.ok(ApiResponse.success("従業員データを初期化しました", new InitializeResponse(
                    true,
                    employees.size(),
                    "従業員データを初期化しました"
            )));
        } catch (Exception e) {
            logger.error("従業員初期化でエラーが発生しました", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("従業員の初期化に失敗しました"));
        }
    }

    @DeleteMapping("/reset-employees")
    public ResponseEntity<ApiResponse<ResetResponse>> resetEmployees() {
        try {
            logger.info("従業員リセットを開始します");
            long count = employeeRepository.count();
            employeeRepository.deleteAll();
            logger.info("従業員リセットが完了しました: {}名を削除", count);

            return ResponseEntity.ok(ApiResponse.success("従業員データをリセットしました", new ResetResponse(
                    count,
                    "従業員データをリセットしました"
            )));
        } catch (Exception e) {
            logger.error("従業員リセットでエラーが発生しました", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("従業員のリセットに失敗しました"));
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
