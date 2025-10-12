package com.example.shiftv1.schedule;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentDto>>> generateSchedule(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            List<ShiftAssignment> assignments = scheduleService.generateMonthlySchedule(target.getYear(), target.getMonthValue());
            Map<String, Object> meta = new HashMap<>();
            meta.put("generatedCount", assignments.size());
            meta.put("year", target.getYear());
            meta.put("month", target.getMonthValue());
            List<ShiftAssignmentDto> data = assignments.stream()
                    .map(ShiftAssignmentDto::from)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success("シフトを生成しました", data, meta));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("シフト生成に失敗しました"));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ShiftAssignmentDto>>> getSchedule(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            List<ShiftAssignment> assignments = scheduleService.getMonthlySchedule(target.getYear(), target.getMonthValue());
            Map<String, Object> meta = new HashMap<>();
            meta.put("count", assignments.size());
            meta.put("year", target.getYear());
            meta.put("month", target.getMonthValue());
            List<ShiftAssignmentDto> data = assignments.stream()
                    .map(ShiftAssignmentDto::from)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success("シフトを取得しました", data, meta));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("シフトの取得に失敗しました"));
        }
    }

    private YearMonth resolveYearMonth(Integer year, Integer month) {
        if (year != null && month != null) {
            try {
                return YearMonth.of(year, month);
            } catch (Exception e) {
                throw new IllegalArgumentException("無効な年月です: " + year + "年" + month + "月");
            }
        }
        LocalDate today = LocalDate.now();
        return YearMonth.of(today.getYear(), today.getMonthValue());
    }
}
