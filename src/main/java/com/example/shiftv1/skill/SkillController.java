package com.example.shiftv1.skill;

import com.example.shiftv1.common.ApiResponse;
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

    public SkillController(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Skill>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success("スキル一覧を取得しました", skillRepository.findAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Skill>> get(@PathVariable Long id) {
        Optional<Skill> s = skillRepository.findById(id);
        return s.map(v -> ResponseEntity.ok(ApiResponse.success("スキルを取得しました", v)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルが見つかりません")));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Skill>> create(@Valid @RequestBody SkillRequest req) {
        if (skillRepository.existsByCode(req.code())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure("同じコードのスキルが存在します"));
        }
        Skill saved = skillRepository.save(new Skill(req.code(), req.name(), req.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("スキルを作成しました", saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Skill>> update(@PathVariable Long id, @Valid @RequestBody SkillRequest req) {
        Optional<Skill> s = skillRepository.findById(id);
        if (s.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルが見つかりません"));
        Skill skill = s.get();
        skill.setCode(req.code());
        skill.setName(req.name());
        skill.setDescription(req.description());
        return ResponseEntity.ok(ApiResponse.success("スキルを更新しました", skillRepository.save(skill)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (!skillRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルが見つかりません"));
        }
        skillRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("スキルを削除しました", null));
    }

    public record SkillRequest(String code, String name, String description) {}
}

