package com.example.shiftv1.schedule;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            List<ShiftAssignment> assignments = scheduleService.generateMonthlySchedule(target.getYear(), target.getMonthValue());
            Map<String, Object> meta = buildScheduleMeta(assignments, target);
            meta.put("generatedCount", assignments.size());
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
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            List<ShiftAssignment> assignments = scheduleService.getMonthlySchedule(target.getYear(), target.getMonthValue());
            Map<String, Object> meta = buildScheduleMeta(assignments, target);
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

    @PostMapping("/generate-with-report")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentDto>>> generateScheduleWithReport(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            ScheduleService.GenerationReport report = scheduleService.generateMonthlyScheduleWithReport(target.getYear(), target.getMonthValue());
            List<ShiftAssignment> assignments = report.assignments;
            Map<String, Object> meta = buildScheduleMeta(assignments, target);
            meta.put("generatedCount", assignments.size());
            if (report.shortages != null && !report.shortages.isEmpty()) {
                meta.put("shortageCount", report.shortages.size());
                List<Map<String, Object>> shortageList = report.shortages.stream().map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("workDate", s.workDate.toString());
                    m.put("shiftName", s.shiftName);
                    m.put("required", s.required);
                    m.put("assigned", s.assigned);
                    return m;
                }).collect(Collectors.toList());
                meta.put("shortages", shortageList);
            } else {
                meta.put("shortageCount", 0);
                meta.put("shortages", List.of());
            }
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

    @DeleteMapping("/reset")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetSchedule(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            scheduleService.resetMonthlySchedule(target.getYear(), target.getMonthValue());
            Map<String, Object> meta = new HashMap<>();
            meta.put("year", target.getYear());
            meta.put("month", target.getMonthValue());
            return ResponseEntity.ok(ApiResponse.success("対象月のシフトを初期化しました", Map.of(), meta));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("シフトの初期化に失敗しました"));
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

    private Map<String, Object> buildScheduleMeta(List<ShiftAssignment> assignments, YearMonth target) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("year", target.getYear());
        meta.put("month", target.getMonthValue());
        meta.put("count", assignments.size());
        long uniqueEmployees = assignments.stream()
                .map(assignment -> assignment.getEmployee().getId())
                .distinct()
                .count();
        long workingDays = assignments.stream()
                .map(ShiftAssignment::getWorkDate)
                .distinct()
                .count();
        meta.put("uniqueEmployees", uniqueEmployees);
        meta.put("workingDays", workingDays);
        return meta;
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<ApiResponse<ScheduleService.DiagnosticReport>> getDiagnostics(
            @RequestParam(name = "date") LocalDate date) {
        ScheduleService.DiagnosticReport report = scheduleService.diagnoseDay(date);
        return ResponseEntity.ok(ApiResponse.success("診断結果を取得しました", report));
    }
}
