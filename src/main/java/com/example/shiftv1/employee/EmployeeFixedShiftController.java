package com.example.shiftv1.employee;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/employees/{employeeId}/fixed-shifts")
public class EmployeeFixedShiftController {

    private final EmployeeRepository employeeRepository;
    private final EmployeeFixedShiftRepository fixedShiftRepository;

    public EmployeeFixedShiftController(EmployeeRepository employeeRepository,
                                        EmployeeFixedShiftRepository fixedShiftRepository) {
        this.employeeRepository = employeeRepository;
        this.fixedShiftRepository = fixedShiftRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<FixedShiftDto>>> list(@PathVariable Long employeeId) {
        if (!employeeRepository.existsById(employeeId)) {
            return ResponseEntity.status(404).body(ApiResponse.failure("従業員が見つかりません"));
        }
        List<FixedShiftDto> data = fixedShiftRepository.findByEmployeeId(employeeId).stream()
                .sorted(Comparator.comparing(EmployeeFixedShift::getDayOfWeek))
                .map(FixedShiftDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success("固定シフトを取得しました", data));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> replace(@PathVariable Long employeeId,
                                                                    @RequestBody List<FixedShiftRequest> payload) {
        Employee employee = employeeRepository.findById(employeeId).orElse(null);
        if (employee == null) {
            return ResponseEntity.status(404).body(ApiResponse.failure("従業員が見つかりません"));
        }
        List<EmployeeFixedShift> toSave = new ArrayList<>();
        if (payload != null) {
            for (FixedShiftRequest req : payload) {
                if (req == null || !Boolean.TRUE.equals(req.active())) {
                    continue;
                }
                DayOfWeek dayOfWeek = parseDay(req.dayOfWeek());
                if (dayOfWeek == null)
                    continue;
                LocalTime start = parseTime(req.startTime());
                LocalTime end = parseTime(req.endTime());
                if (start == null || end == null || !start.isBefore(end))
                    continue;
                EmployeeFixedShift entity = new EmployeeFixedShift();
                entity.setEmployee(employee);
                entity.setDayOfWeek(dayOfWeek);
                entity.setStartTime(start);
                entity.setEndTime(end);
                entity.setNote(req.note());
                entity.setActive(true);
                toSave.add(entity);
            }
        }
        fixedShiftRepository.deleteByEmployeeId(employeeId);
        if (!toSave.isEmpty()) {
            fixedShiftRepository.saveAll(toSave);
        }
        return ResponseEntity.ok(ApiResponse.success("固定シフトを更新しました", Map.of("count", toSave.size())));
    }

    private DayOfWeek parseDay(String value) {
        if (value == null || value.isBlank())
            return null;
        try {
            return DayOfWeek.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank())
            return null;
        try {
            String normalized = value.trim();
            return LocalTime.parse(normalized.length() == 5 ? normalized + ":00" : normalized);
        } catch (Exception e) {
            return null;
        }
    }

    public record FixedShiftRequest(String dayOfWeek, String startTime, String endTime, String note, Boolean active) {
    }

    public record FixedShiftDto(Long id, String dayOfWeek, String startTime, String endTime, String note, Boolean active) {
        static FixedShiftDto from(EmployeeFixedShift entity) {
            return new FixedShiftDto(
                    entity.getId(),
                    entity.getDayOfWeek() != null ? entity.getDayOfWeek().name() : null,
                    entity.getStartTime() != null ? entity.getStartTime().toString() : null,
                    entity.getEndTime() != null ? entity.getEndTime().toString() : null,
                    entity.getNote(),
                    entity.getActive()
            );
        }
    }
}
