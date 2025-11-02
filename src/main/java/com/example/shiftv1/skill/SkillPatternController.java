package com.example.shiftv1.skill;

import com.example.shiftv1.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/skill-patterns")
public class SkillPatternController {

    private final SkillPatternRepository patternRepository;
    private final SkillRepository skillRepository;

    public SkillPatternController(SkillPatternRepository patternRepository,
                                  SkillRepository skillRepository) {
        this.patternRepository = patternRepository;
        this.skillRepository = skillRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SkillPattern>>> list(
            @RequestParam(value = "skillId", required = false) Long skillId,
            @RequestParam(value = "activeOnly", required = false, defaultValue = "true") boolean activeOnly
    ) {
        List<SkillPattern> list;
        if (skillId != null) {
            list = activeOnly ? patternRepository.findBySkill_IdAndActiveTrue(skillId)
                    : patternRepository.findAll().stream().filter(p -> p.getSkill()!=null && p.getSkill().getId()!=null && p.getSkill().getId().equals(skillId)).toList();
        } else {
            list = activeOnly ? patternRepository.findByActiveTrue() : patternRepository.findAll();
        }
        return ResponseEntity.ok(ApiResponse.success("スキルパターン一覧を取得しました", list));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SkillPattern>> get(@PathVariable Long id) {
        Optional<SkillPattern> s = patternRepository.findById(id);
        return s.map(v -> ResponseEntity.ok(ApiResponse.success("スキルパターンを取得しました", v)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルパターンが見つかりません")));
    }

    public record PatternRequest(
            Long skillId,
            String dayOfWeek,
            String startTime,
            String endTime,
            String allowedLengthsCsv,
            String priorityHint,
            Boolean active
    ) {}

    @PostMapping
    public ResponseEntity<ApiResponse<SkillPattern>> create(@Valid @RequestBody PatternRequest req) {
        if (req.skillId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("skillIdは必須です"));
        }
        Optional<Skill> sk = skillRepository.findById(req.skillId());
        if (sk.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルが見つかりません"));
        SkillPattern p = new SkillPattern();
        p.setSkill(sk.get());
        if (req.dayOfWeek()!=null && !req.dayOfWeek().isBlank()) p.setDayOfWeek(DayOfWeek.valueOf(req.dayOfWeek()));
        if (req.startTime()!=null && !req.startTime().isBlank()) p.setStartTime(parseTime(req.startTime()));
        if (req.endTime()!=null && !req.endTime().isBlank()) p.setEndTime(parseTime(req.endTime()));
        if (req.allowedLengthsCsv()!=null) p.setAllowedLengthsCsv(req.allowedLengthsCsv());
        p.setPriorityHint(parseHint(req.priorityHint()));
        p.setActive(req.active()==null ? Boolean.TRUE : req.active());
        SkillPattern saved = patternRepository.save(p);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("スキルパターンを作成しました", saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<SkillPattern>> update(@PathVariable Long id, @Valid @RequestBody PatternRequest req) {
        Optional<SkillPattern> s = patternRepository.findById(id);
        if (s.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルパターンが見つかりません"));
        SkillPattern p = s.get();
        if (req.skillId()!=null) {
            Optional<Skill> sk = skillRepository.findById(req.skillId());
            if (sk.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルが見つかりません"));
            p.setSkill(sk.get());
        }
        if (req.dayOfWeek()!=null) p.setDayOfWeek(req.dayOfWeek().isBlank()? null : DayOfWeek.valueOf(req.dayOfWeek()));
        if (req.startTime()!=null) p.setStartTime(req.startTime().isBlank()? null : parseTime(req.startTime()));
        if (req.endTime()!=null) p.setEndTime(req.endTime().isBlank()? null : parseTime(req.endTime()));
        if (req.allowedLengthsCsv()!=null) p.setAllowedLengthsCsv(req.allowedLengthsCsv());
        if (req.priorityHint()!=null) p.setPriorityHint(parseHint(req.priorityHint()));
        if (req.active()!=null) p.setActive(req.active());
        return ResponseEntity.ok(ApiResponse.success("スキルパターンを更新しました", patternRepository.save(p)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (!patternRepository.existsById(id)) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("スキルパターンが見つかりません"));
        patternRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("スキルパターンを削除しました", null));
    }

    private LocalTime parseTime(String v) {
        if (v == null || v.isBlank()) return null;
        String s = v.trim();
        if (s.length()==5) s = s+":00";
        return LocalTime.parse(s);
    }

    private SkillPattern.PriorityHint parseHint(String v) {
        if (v == null || v.isBlank()) return SkillPattern.PriorityHint.ANY;
        try { return SkillPattern.PriorityHint.valueOf(v.trim().toUpperCase()); }
        catch (Exception e) { return SkillPattern.PriorityHint.ANY; }
    }
}

