package com.example.shiftv1.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/config/break-settings")
public class BreakSettingsController {

    private final BreakSettingsRepository repository;

    public BreakSettingsController(BreakSettingsRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<BreakSettings> get() {
        return ResponseEntity.ok(loadOrCreate());
    }

    @PutMapping
    public ResponseEntity<BreakSettings> update(@RequestBody BreakSettingsRequest request) {
        BreakSettings entity = loadOrCreate();
        entity.setShortBreakEnabled(Boolean.TRUE.equals(request.shortBreakEnabled()));
        entity.setShortBreakMinutes(normalizePositive(request.shortBreakMinutes(), 15));
        entity.setMinShiftMinutes(normalizePositive(request.minShiftMinutes(), 180));
        entity.setApplyToShortShifts(Boolean.TRUE.equals(request.applyToShortShifts()));
        return ResponseEntity.ok(repository.save(entity));
    }

    private BreakSettings loadOrCreate() {
        return repository.findAll().stream().findFirst().orElseGet(() -> repository.save(new BreakSettings()));
    }

    private int normalizePositive(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }

    public record BreakSettingsRequest(Boolean shortBreakEnabled,
                                       Integer shortBreakMinutes,
                                       Integer minShiftMinutes,
                                       Boolean applyToShortShifts) {
    }
}
