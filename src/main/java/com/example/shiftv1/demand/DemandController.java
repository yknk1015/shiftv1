package com.example.shiftv1.demand;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@RestController
@RequestMapping("/api/demand")
public class DemandController {

    private final DemandIntervalRepository repository;
    private final SkillRepository skillRepository;

    public DemandController(DemandIntervalRepository repository, SkillRepository skillRepository) {
        this.repository = repository;
        this.skillRepository = skillRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DemandInterval>>> list(
            @RequestParam(value = "date", required = false) LocalDate date,
            @RequestParam(value = "dayOfWeek", required = false) DayOfWeek dayOfWeek
    ) {
        List<DemandInterval> data;
        if (date != null) {
            data = repository.findByDate(date);
        } else if (dayOfWeek != null) {
            data = repository.findByDayOfWeek(dayOfWeek);
        } else {
            data = repository.findAll();
        }
        return ResponseEntity.ok(ApiResponse.success("需要インターバル一覧を取得しました", data));
    }

    @GetMapping("/effective")
    public ResponseEntity<ApiResponse<List<DemandInterval>>> effective(@RequestParam("date") LocalDate date) {
        List<DemandInterval> data = repository.findEffectiveForDate(date, date.getDayOfWeek());
        return ResponseEntity.ok(ApiResponse.success("当日の需要インターバルを取得しました", data));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DemandInterval>> create(@Valid @RequestBody DemandRequest req) {
        DemandInterval d = new DemandInterval();
        d.setDate(req.date());
        d.setDayOfWeek(req.dayOfWeek());
        d.setStartTime(req.startTime());
        d.setEndTime(req.endTime());
        d.setRequiredSeats(req.requiredSeats());
        d.setActive(req.active() != null ? req.active() : true);
        if (req.skillId() != null) {
            Skill s = skillRepository.findById(req.skillId()).orElseThrow(() -> new IllegalArgumentException("スキルが見つかりません"));
            d.setSkill(s);
        }
        DemandInterval saved = repository.save(d);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("需要インターバルを作成しました", saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DemandInterval>> update(@PathVariable Long id, @Valid @RequestBody DemandRequest req) {
        Optional<DemandInterval> od = repository.findById(id);
        if (od.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("需要インターバルが見つかりません"));
        DemandInterval d = od.get();
        d.setDate(req.date());
        d.setDayOfWeek(req.dayOfWeek());
        d.setStartTime(req.startTime());
        d.setEndTime(req.endTime());
        d.setRequiredSeats(req.requiredSeats());
        d.setActive(req.active() != null ? req.active() : d.getActive());
        if (req.skillId() != null) {
            Skill s = skillRepository.findById(req.skillId()).orElseThrow(() -> new IllegalArgumentException("スキルが見つかりません"));
            d.setSkill(s);
        } else {
            d.setSkill(null);
        }
        DemandInterval saved = repository.save(d);
        return ResponseEntity.ok(ApiResponse.success("需要インターバルを更新しました", saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("需要インターバルが見つかりません"));
        }
        repository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("需要インターバルを削除しました", null));
    }

    public record DemandRequest(
            LocalDate date,
            DayOfWeek dayOfWeek,
            LocalTime startTime,
            LocalTime endTime,
            Integer requiredSeats,
            Long skillId,
            Boolean active
    ) {}
}

