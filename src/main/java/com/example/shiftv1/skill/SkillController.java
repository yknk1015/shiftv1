package com.example.shiftv1.skill;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
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
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listSkills() {
        List<Map<String, Object>> data = skillRepository.findAll().stream()
                .map(s -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", s.getId());
                    m.put("code", s.getCode());
                    m.put("name", s.getName());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("skills", data));
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
}
