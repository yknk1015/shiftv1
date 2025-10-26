package com.example.shiftv1.schedule;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    // --- Core-time boost endpoint ---
    public static class CoreBoostRequest {
        public String date;      // yyyy-MM-dd
        public Long skillId;     // optional if skillCode provided
        public String skillCode; // optional if skillId provided
        public String startTime; // HH:mm
        public String endTime;   // HH:mm
        public Integer seats;    // N
    }

    @PostMapping("/core-boost")
    public ResponseEntity<ApiResponse<Map<String,Object>>> coreBoost(@RequestBody CoreBoostRequest req) {
        try {
            java.time.LocalDate day = java.time.LocalDate.parse(req.date);
            java.time.LocalTime s = java.time.LocalTime.parse(req.startTime.length()==5? req.startTime+":00": req.startTime);
            java.time.LocalTime e = java.time.LocalTime.parse(req.endTime.length()==5? req.endTime+":00": req.endTime);
            int n = req.seats == null ? 1 : Math.max(0, req.seats);
            if (!s.isBefore(e) || n <= 0) {
                return ResponseEntity.badRequest().body(ApiResponse.failure("時間範囲または人数が不正です"));
            }
            Map<String,Object> result = scheduleService.addCoreTime(day, req.skillId, req.skillCode, s, e, n);
            return ResponseEntity.ok(ApiResponse.success("コアタイム追加を実行しました", result));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("コアタイム追加に失敗しました: " + ex.getMessage()));
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

    // Synchronous demand-based generation (for debugging/proof)
    @PreAuthorize("hasRole('ADMIN')")
    @RequestMapping(value = "/generate-from-demand-sync", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<ApiResponse<Map<String,Object>>> generateFromDemandSync(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "granularity", defaultValue = "60") int granularity,
            @RequestParam(name = "reset", defaultValue = "true") boolean reset) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            List<ShiftAssignment> res = scheduleService.generateMonthlyFromDemand(target.getYear(), target.getMonthValue(), granularity, reset);
            Map<String, Object> meta = new HashMap<>();
            meta.put("year", target.getYear());
            meta.put("month", target.getMonthValue());
            meta.put("generated", res == null ? 0 : res.size());
            return ResponseEntity.ok(ApiResponse.success("デマンドベース同期生成が完了しました", meta));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("デマンド同期生成に失敗しました: " + e.getMessage()));
        }
    }

    // One-day generation (debugging aid)
    @PreAuthorize("hasRole('ADMIN')")
    @RequestMapping(value = "/generate-from-demand-sync-day", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<ApiResponse<Map<String,Object>>> generateFromDemandSyncDay(
            @RequestParam(name = "date") LocalDate date,
            @RequestParam(name = "reset", defaultValue = "true") boolean reset) {
        try {
            List<ShiftAssignment> res = scheduleService.generateForDateFromDemand(date, reset);
            Map<String, Object> meta = new HashMap<>();
            meta.put("date", date.toString());
            meta.put("generated", res == null ? 0 : res.size());
            return ResponseEntity.ok(ApiResponse.success("一日分の同期生成が完了しました", meta));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("一日分の同期生成に失敗しました: " + e.getMessage()));
        }
    }

    // --- Formalized endpoints (stable) ---
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/generate/demand")
    public ResponseEntity<ApiResponse<Map<String,Object>>> generateDemandStable(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "granularity", defaultValue = "60") int granularity,
            @RequestParam(name = "reset", defaultValue = "true") boolean reset) {
        return generateFromDemandSync(year, month, granularity, reset);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/generate/demand/day")
    public ResponseEntity<ApiResponse<Map<String,Object>>> generateDemandDayStable(
            @RequestParam(name = "date") LocalDate date,
            @RequestParam(name = "reset", defaultValue = "true") boolean reset) {
        return generateFromDemandSyncDay(date, reset);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/generate/demand/async")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentDto>>> generateDemandAsyncStable(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "granularity", defaultValue = "60") int granularity,
            @RequestParam(name = "reset", defaultValue = "true") boolean reset) {
        return generateFromDemandAsync(year, month, granularity, reset);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @RequestMapping(value = "/generate-from-demand", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<ApiResponse<List<ShiftAssignmentDto>>> generateFromDemand(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "granularity", defaultValue = "60") int granularity,
            @RequestParam(name = "reset", defaultValue = "true") boolean reset) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            List<ShiftAssignment> assignments = scheduleService.generateMonthlyFromDemand(
                    target.getYear(), target.getMonthValue(), granularity, reset);
            Map<String, Object> meta = buildScheduleMeta(assignments, target);
            meta.put("generatedCount", assignments.size());
            List<ShiftAssignmentDto> data = assignments.stream()
                    .map(ShiftAssignmentDto::from)
                    .collect(java.util.stream.Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success("需要に基づいてシフトを生成しました", data, meta));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("需要ベースのシフト生成に失敗しました"));
        }
    }

    // 非同期版: 即時に受付し、バックグラウンドで生成を開始
    @PreAuthorize("hasRole('ADMIN')")
    @RequestMapping(value = "/generate-from-demand-async", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<ApiResponse<List<ShiftAssignmentDto>>> generateFromDemandAsync(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "granularity", defaultValue = "60") int granularity,
            @RequestParam(name = "reset", defaultValue = "true") boolean reset) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            scheduleService.generateMonthlyFromDemandAsync(target.getYear(), target.getMonthValue(), granularity, reset);
            Map<String, Object> meta = new HashMap<>();
            meta.put("year", target.getYear());
            meta.put("month", target.getMonthValue());
            meta.put("started", true);
            meta.put("granularity", granularity);
            meta.put("reset", reset);
            return ResponseEntity.ok(ApiResponse.success("需要ベースのシフト生成を開始しました", List.of(), meta));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("需要ベースのシフト生成の開始に失敗しました"));
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
