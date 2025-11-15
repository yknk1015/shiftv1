package com.example.shiftv1.skill;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRepository skillRepository;
    private final EmployeeRepository employeeRepository;

    public SkillController(SkillRepository skillRepository, EmployeeRepository employeeRepository) {
        this.skillRepository = skillRepository;
        this.employeeRepository = employeeRepository;
    }

    @GetMapping("")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listSkills(
            @RequestParam(value = "query", required = false) String query) {
        List<Skill> source;
        if (StringUtils.hasText(query)) {
            source = skillRepository.findByCodeContainingIgnoreCaseOrNameContainingIgnoreCase(query.trim(), query.trim());
        } else {
            source = skillRepository.findAll();
        }
        source.sort(Comparator.comparing((Skill s) -> Optional.ofNullable(s.getPriority()).orElse(5))
                .thenComparing(s -> Optional.ofNullable(s.getCode()).orElse("")));
        List<Map<String, Object>> data = source.stream()
                .map(this::toResponseMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("skills", data));
    }

    @PostMapping("")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSkill(@Valid @RequestBody SkillRequest request) {
        validateRequest(request, null);
        Skill skill = new Skill();
        applyRequest(skill, request);
        Skill saved = skillRepository.save(skill);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("スキルを登録しました", toResponseMap(saved)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSkill(
            @PathVariable("id") Long id,
            @Valid @RequestBody SkillRequest request) {
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "スキルが見つかりません"));
        validateRequest(request, id);
        applyRequest(skill, request);
        Skill saved = skillRepository.save(skill);
        return ResponseEntity.ok(ApiResponse.success("スキルを更新しました", toResponseMap(saved)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteSkill(@PathVariable("id") Long id) {
        if (!skillRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.failure("スキルが見つかりません"));
        }
        skillRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("スキルを削除しました", null));
    }

    @GetMapping("/employees")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listEmployeesBySkill(
            @RequestParam("skillId") Long skillId) {
        List<Map<String, Object>> data = employeeRepository.findAll().stream()
                .filter(e -> e.getSkills() != null && e.getSkills().stream().anyMatch(s -> s.getId().equals(skillId)))
                .map(e -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", e.getId());
                    m.put("name", e.getName());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("employees by skill", data));
    }

    private void validateRequest(SkillRequest request, Long currentId) {
        if (!StringUtils.hasText(request.code())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "コードは必須です");
        }
        if (!StringUtils.hasText(request.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "名称は必須です");
        }
        String code = request.code().trim();
        boolean duplicate = skillRepository.findByCodeIgnoreCase(code)
                .filter(skill -> currentId == null || !skill.getId().equals(currentId))
                .isPresent();
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同じコードが既に登録されています");
        }
    }

    private void applyRequest(Skill skill, SkillRequest request) {
        skill.setCode(request.code().trim());
        skill.setName(request.name().trim());
        skill.setDescription(Optional.ofNullable(request.description()).map(String::trim).orElse(null));
        Integer priority = request.priority();
        if (priority == null) priority = 5;
        priority = Math.max(1, Math.min(10, priority));
        skill.setPriority(priority);
    }

    private Map<String, Object> toResponseMap(Skill skill) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", skill.getId());
        m.put("code", skill.getCode());
        m.put("name", skill.getName());
        m.put("description", skill.getDescription());
        m.put("priority", skill.getPriority());
        return m;
    }

    public record SkillRequest(String code, String name, String description, Integer priority) {
    }
}
