package com.example.shiftv1.schedule;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ShiftAssignmentRepository assignmentRepository;

    public ScheduleController(ScheduleService scheduleService,
                              ShiftAssignmentRepository assignmentRepository,
                              com.example.shiftv1.common.error.ErrorLogBuffer errorLogBuffer) {
        this.scheduleService = scheduleService;
        this.assignmentRepository = assignmentRepository;
    }

    // Fallback generator (delegates to demand-based simple)
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentDto>>> generateSchedule(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            List<ShiftAssignment> assignments = scheduleService.generateMonthlySchedule(target.getYear(), target.getMonthValue());
            scheduleService.ensureWeeklyHolidays(target.getYear(), target.getMonthValue());
            scheduleService.ensureFreePlaceholders(target.getYear(), target.getMonthValue());
            assignments = assignmentRepository.findByWorkDateBetween(target.atDay(1), target.atEndOfMonth());
            Map<String, Object> meta = buildScheduleMeta(assignments, target);
            meta.put("generatedCount", assignments.size());
            List<ShiftAssignmentDto> data = assignments.stream().map(ShiftAssignmentDto::from).toList();
            return ResponseEntity.ok(ApiResponse.success("シフトを生成しました", data, meta));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("シフト生成に失敗しました"));
        }
    }

    // --- Demand-based generation (simple) ---
    @PostMapping("/generate/demand")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateDemand(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "granularity", required = false, defaultValue = "60") Integer granularityMinutes,
            @RequestParam(name = "reset", required = false, defaultValue = "false") boolean reset,
            @RequestParam(name = "mode", required = false) String mode) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            List<ShiftAssignment> created = scheduleService.generateMonthlyFromDemand(target.getYear(), target.getMonthValue(), granularityMinutes, reset);
            Map<String, Object> meta = new HashMap<>();
            meta.put("year", target.getYear());
            meta.put("month", target.getMonthValue());
            meta.put("generated", created.size());
            return ResponseEntity.ok(ApiResponse.success("デマンドベース同期生成が完了しました", meta));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("需要ベースのシフト生成に失敗しました"));
        }
    }

    // Debug: list available employees for a time slot (optionally by skill)
    @GetMapping("/debug/available")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> debugAvailable(
            @RequestParam("date") java.time.LocalDate date,
            @RequestParam("start") String start,
            @RequestParam("end") String end,
            @RequestParam(name = "skillId", required = false) Long skillId) {
        try {
            java.time.LocalTime s = java.time.LocalTime.parse(start.length()==5? start+":00": start);
            java.time.LocalTime e = java.time.LocalTime.parse(end.length()==5? end+":00": end);
            List<com.example.shiftv1.employee.Employee> avail = assignmentRepository.findAvailableEmployeesForTimeSlot(date, s, e);
            if (skillId != null) {
                avail = avail.stream().filter(emp -> emp.getSkills() != null && emp.getSkills().stream().anyMatch(sk -> Objects.equals(sk.getId(), skillId))).toList();
            }
            List<Map<String, Object>> data = avail.stream()
                    .map(emp -> {
                        Map<String, Object> m = new java.util.HashMap<>();
                        m.put("id", emp.getId());
                        m.put("name", emp.getName());
                        return m;
                    })
                    .toList();
            return ResponseEntity.ok(ApiResponse.success("available employees", data));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("debug available failed"));
        }
    }

    @PostMapping("/generate/demand/async")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateDemandAsync(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "granularity", required = false, defaultValue = "60") Integer granularityMinutes,
            @RequestParam(name = "reset", required = false, defaultValue = "false") boolean reset,
            @RequestParam(name = "mode", required = false) String mode) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            scheduleService.generateMonthlyFromDemandAsync(target.getYear(), target.getMonthValue(), granularityMinutes, reset);
            Map<String, Object> meta = new HashMap<>();
            meta.put("started", true);
            meta.put("year", target.getYear());
            meta.put("month", target.getMonthValue());
            meta.put("granularity", granularityMinutes);
            meta.put("reset", reset);
            return ResponseEntity.ok(ApiResponse.success("需要ベースのシフト生成を開始しました", meta));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("需要ベースのシフト生成に失敗しました"));
        }
    }

    // Monthly snapshot for dashboard/calendar
    @GetMapping("")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMonthlySnapshot(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            var start = target.atDay(1);
            var end = target.atEndOfMonth();
            List<ShiftAssignment> items = assignmentRepository.findByWorkDateBetween(start, end);
            // include placeholders so users can see FREE/休日
            List<Map<String, Object>> list = items.stream()
                    .map(a -> {
                        Map<String, Object> m = new HashMap<>();
                        String dateStr = a.getWorkDate().toString();
                        m.put("workDate", dateStr); // expected by calendar/dashboard
                        m.put("date", dateStr);     // backward compatibility
                        m.put("employeeName", a.getEmployee().getName());
                        String shiftName = a.getShiftName();
                        m.put("shiftName", shiftName);
                        m.put("shift", shiftName);  // backward compatibility
                        // flags for client to style placeholders
                        try { m.put("isFree", Boolean.TRUE.equals(a.getIsFree())); } catch (Exception ignore) {}
                        try { m.put("isOff", Boolean.TRUE.equals(a.getIsOff())); } catch (Exception ignore) {}
                        m.put("start", a.getStartTime().toString());
                        m.put("end", a.getEndTime().toString());
                        m.put("employeeId", a.getEmployee().getId());
                        m.put("id", a.getId());
                        return m;
                    })
                    .collect(Collectors.toList());
            Map<String, Object> meta = new HashMap<>();
            meta.put("month", target.getMonthValue());
            meta.put("year", target.getYear());
            meta.put("count", list.size());
            // Return the array directly as data so frontend can data.forEach(...)
            return ResponseEntity.ok(ApiResponse.success("Assignments snapshot", list, meta));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("月次スナップショットの取得に失敗しました"));
        }
    }

    // Optional: reset monthly assignments endpoint (UI attempted to access /api/schedule/reset)
    @RequestMapping(value = "/reset", method = { RequestMethod.POST, RequestMethod.DELETE })
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetMonthly(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            var start = target.atDay(1);
            var end = target.atEndOfMonth();
            long before = assignmentRepository.countByWorkDateBetween(start, end);
            assignmentRepository.deleteByWorkDateBetween(start, end);
            Map<String, Object> meta = new HashMap<>();
            meta.put("year", target.getYear());
            meta.put("month", target.getMonthValue());
            meta.put("deleted", before);
            return ResponseEntity.ok(ApiResponse.success("対象月のシフトをリセットしました", meta));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("リセットに失敗しました"));
        }
    }

    private YearMonth resolveYearMonth(Integer year, Integer month) {
        LocalDate now = LocalDate.now();
        int y = (year == null || year <= 0) ? now.getYear() : year;
        int m = (month == null || month < 1 || month > 12) ? now.getMonthValue() : month;
        return YearMonth.of(y, m);
    }

    private Map<String, Object> buildScheduleMeta(List<ShiftAssignment> items, YearMonth ym) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("year", ym.getYear());
        meta.put("month", ym.getMonthValue());
        meta.put("count", items.size());
        return meta;
    }
}
