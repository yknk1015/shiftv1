package com.example.shiftv1.skill;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.demand.DemandIntervalRepository;
import com.example.shiftv1.employee.EmployeeRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRepository skillRepository;
    private final EmployeeRepository employeeRepository;
    private final DemandIntervalRepository demandRepository;

    public SkillController(SkillRepository skillRepository,
                           EmployeeRepository employeeRepository,
                           DemandIntervalRepository demandRepository) {
        this.skillRepository = skillRepository;
        this.employeeRepository = employeeRepository;
        this.demandRepository = demandRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Skill>>> getAll(@RequestParam(value = "query", required = false) String query) {
        List<Skill> data;
        if (query != null && !query.isBlank()) {
            data = skillRepository.findByCodeContainingIgnoreCaseOrNameContainingIgnoreCase(query.trim(), query.trim());
        } else {
            data = skillRepository.findAll();
        }
        return ResponseEntity.ok(ApiResponse.success("スキル一覧を取得しました", data));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Skill>> get(@PathVariable Long id) {
        Optional<Skill> s = skillRepository.findById(id);
        return s.map(v -> ResponseEntity.ok(ApiResponse.success("スキルを取得しました", v)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルが見つかりません")));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Skill>> create(@Valid @RequestBody SkillRequest req) {
        String code = normalizeCode(req.code());
        String name = normalizeName(req.name());
        if (skillRepository.existsByCodeIgnoreCase(code)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure("同じコードのスキルは既に存在します"));
        }
        Skill s = new Skill(code, name, req.description());
        Integer pr = normalizePriority(req.priority());
        if (pr != null) s.setPriority(pr);
        Skill saved = skillRepository.save(s);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("スキルを作成しました", saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Skill>> update(@PathVariable Long id, @Valid @RequestBody SkillRequest req) {
        Optional<Skill> s = skillRepository.findById(id);
        if (s.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルが見つかりません"));
        String code = normalizeCode(req.code());
        String name = normalizeName(req.name());
        Skill skill = s.get();
        if (!skill.getCode().equalsIgnoreCase(code) && skillRepository.existsByCodeIgnoreCase(code)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure("同じコードのスキルは既に存在します"));
        }
        skill.setCode(code);
        skill.setName(name);
        skill.setDescription(req.description());
        Integer pr = normalizePriority(req.priority());
        if (pr != null) skill.setPriority(pr);
        return ResponseEntity.ok(ApiResponse.success("スキルを更新しました", skillRepository.save(skill)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (!skillRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルが見つかりません"));
        }
        long refEmployees = employeeRepository.countBySkills_Id(id);
        long refDemands = demandRepository.countBySkill_Id(id);
        if (refEmployees > 0 || refDemands > 0) {
            String msg = String.format("このスキルは参照されています（従業員: %d, 需要: %d）ため削除できません", refEmployees, refDemands);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure(msg));
        }
        skillRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("スキルを削除しました", null));
    }

    public record SkillRequest(String code, String name, String description, Integer priority) {}

    private String normalizeCode(String code) { return code == null ? null : code.trim().toUpperCase(); }
    private String normalizeName(String name) { return name == null ? null : name.trim(); }
    private Integer normalizePriority(Integer p) {
        if (p == null) return null;
        int v = Math.max(1, Math.min(10, p));
        return v;
    }
}

