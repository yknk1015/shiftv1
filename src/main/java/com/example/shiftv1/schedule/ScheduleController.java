package com.example.shiftv1.schedule;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ShiftAssignmentRepository assignmentRepository;
    private final ShiftReservationRepository reservationRepository;
    private final com.example.shiftv1.common.error.ErrorLogBuffer errorLogBuffer;
    private final ScheduleJobStatusService jobStatusService;
    private static final Logger logger = LoggerFactory.getLogger(ScheduleController.class);

    public ScheduleController(ScheduleService scheduleService,
                              ShiftAssignmentRepository assignmentRepository,
                              ShiftReservationRepository reservationRepository,
                              com.example.shiftv1.common.error.ErrorLogBuffer errorLogBuffer,
                              ScheduleJobStatusService jobStatusService) {
        this.scheduleService = scheduleService;
        this.assignmentRepository = assignmentRepository;
        this.reservationRepository = reservationRepository;
        this.errorLogBuffer = errorLogBuffer;
        this.jobStatusService = jobStatusService;
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

    // --- Demand-based generation for a single day (admin-debug use) ---
    @PostMapping("/generate/demand/day")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateDemandForDay(
            @RequestParam("date") LocalDate date,
            @RequestParam(name = "reset", required = false, defaultValue = "false") boolean reset) {
        try {
            List<ShiftAssignment> created = scheduleService.generateForDateFromDemand(date, reset);
            Map<String, Object> meta = new HashMap<>();
            meta.put("date", date.toString());
            meta.put("generated", created.size());
            return ResponseEntity.ok(ApiResponse.success("デマンドベース同期生成（1日）が完了しました", meta));
        } catch (Exception e) {
            logger.error("/api/schedule/generate/demand/day failed for date={} reset={}", date, reset, e);
            try { if (errorLogBuffer != null) errorLogBuffer.addError("/api/schedule/generate/demand/day failed", e); } catch (Exception ignore) {}
            return ResponseEntity.internalServerError().body(ApiResponse.failure("需要ベースの1日シフト生成に失敗しました"));
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
            List<ShiftReservation> reservationsToReset = reservationRepository.findByWorkDateBetweenAndStatusIn(
                    start, end, List.of(ShiftReservation.Status.APPLIED));
            if (!reservationsToReset.isEmpty()) {
                reservationsToReset.forEach(res -> res.setStatus(ShiftReservation.Status.PENDING));
                reservationRepository.saveAll(reservationsToReset);
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("year", target.getYear());
            meta.put("month", target.getMonthValue());
            meta.put("deleted", before);
            return ResponseEntity.ok(ApiResponse.success("対象月のシフトをリセットしました", meta));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("リセットに失敗しました"));
        }
    }

    @GetMapping("/grid")
    public ResponseEntity<ApiResponse<ScheduleGridResponse>> getGrid(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        try {
            ScheduleGridResponse grid = scheduleService.loadGrid(start, end);
            return ResponseEntity.ok(ApiResponse.success("グリッドを取得しました", grid));
        } catch (BusinessException e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(e.getMessage()));
        } catch (Exception e) {
            logger.error("/api/schedule/grid failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("グリッドの取得に失敗しました"));
        }
    }

    @PostMapping("/grid/bulk")
    public ResponseEntity<ApiResponse<ScheduleGridBulkResult>> applyGrid(
            @RequestBody ScheduleGridBulkRequest request) {
        try {
            ScheduleGridBulkResult result = scheduleService.applyGridChanges(request);
            return ResponseEntity.ok(ApiResponse.success("変更を保存しました", result));
        } catch (BusinessException e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(e.getMessage()));
        } catch (Exception e) {
            logger.error("/api/schedule/grid/bulk failed", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("変更の保存に失敗しました"));
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

    private String boolToStr(Boolean value) {
        return (value != null && value) ? "1" : "0";
    }

    private String formatTime(LocalTime time, DateTimeFormatter formatter) {
        return (time == null) ? "" : formatter.format(time);
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String safeId(Long value) {
        return value == null ? "" : value.toString();
    }
    // Lightweight meta for polling (count only)
    @GetMapping("/meta")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getScheduleMeta(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            var start = target.atDay(1);
            var end = target.atEndOfMonth();
            long count = assignmentRepository.countByWorkDateBetween(start, end);
            Map<String, Object> meta = new HashMap<>();
            meta.put("year", target.getYear());
            meta.put("month", target.getMonthValue());
            meta.put("count", count);
            return ResponseEntity.ok(ApiResponse.success("Schedule meta", meta));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("メタ情報の取得に失敗しました"));
        }
    }

    @GetMapping("/jobs/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getJobStatus(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            var s = jobStatusService.get(target.getYear(), target.getMonthValue());
            long live = 0L;
            try { live = assignmentRepository.countByWorkDateBetween(target.atDay(1), target.atEndOfMonth()); } catch (Exception ignore) {}
            long count = Math.max(s.createdCount, live);
            boolean running = s.running || (!s.done && live > 0 && s.startedAt == null);
            Map<String, Object> data = new HashMap<>();
            data.put("running", running);
            data.put("done", s.done);
            data.put("count", count);
            data.put("startedAt", s.startedAt);
            data.put("finishedAt", s.finishedAt);
            return ResponseEntity.ok(ApiResponse.success("Job status", data));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("ジョブ状況の取得に失敗しました"));
        }
    }

    @PostMapping("/assignments/{id}/convert-leave")
    public ResponseEntity<ApiResponse<ShiftAssignmentDto>> convertFreeToLeave(@PathVariable("id") Long assignmentId) {
        try {
            ShiftAssignment updated = scheduleService.convertFreePlaceholderToPaidLeave(assignmentId);
            return ResponseEntity.ok(ApiResponse.success("FREE枠を有給に変更しました", ShiftAssignmentDto.from(updated)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(ApiResponse.failure(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed converting FREE to leave", e);
            return ResponseEntity.internalServerError().body(ApiResponse.failure("有給変更に失敗しました"));
        }
    }

    @GetMapping(value = "/export/csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(name = "year", required = false) Integer year,
                                            @RequestParam(name = "month", required = false) Integer month) {
        YearMonth target = resolveYearMonth(year, month);
        List<ShiftAssignment> assignments = assignmentRepository.findByWorkDateBetween(target.atDay(1), target.atEndOfMonth());
        DateTimeFormatter dateFmt = DateTimeFormatter.ISO_LOCAL_DATE;
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        StringBuilder sb = new StringBuilder();
        sb.append("date,employeeId,employeeName,shiftName,startTime,endTime,isFree,isOff,isLeave\n");
        for (ShiftAssignment sa : assignments) {
            sb.append(dateFmt.format(sa.getWorkDate())).append(',')
                    .append(safeId(sa.getEmployee() != null ? sa.getEmployee().getId() : null)).append(',')
                    .append(escapeCsv(sa.getEmployee() != null ? sa.getEmployee().getName() : "")).append(',')
                    .append(escapeCsv(sa.getShiftName())).append(',')
                    .append(formatTime(sa.getStartTime(), timeFmt)).append(',')
                    .append(formatTime(sa.getEndTime(), timeFmt)).append(',')
                    .append(boolToStr(sa.getIsFree())).append(',')
                    .append(boolToStr(sa.getIsOff())).append(',')
                    .append(boolToStr(sa.getIsLeave())).append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        String filename = String.format("schedule-%d-%02d.csv", target.getYear(), target.getMonthValue());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(bytes);
    }

    @PostMapping("/housekeeping")
    public ResponseEntity<ApiResponse<Map<String, Object>>> housekeeping(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            scheduleService.ensurePatternOffPlaceholders(target.getYear(), target.getMonthValue());
            scheduleService.ensureWeeklyHolidays(target.getYear(), target.getMonthValue());
            scheduleService.ensureFreePlaceholders(target.getYear(), target.getMonthValue());
            Map<String, Object> meta = new HashMap<>();
            meta.put("year", target.getYear());
            meta.put("month", target.getMonthValue());
            return ResponseEntity.ok(ApiResponse.success("Housekeeping completed", meta));
        } catch (Exception e) {
            logger.error("/api/schedule/housekeeping failed", e);
            try { if (errorLogBuffer != null) errorLogBuffer.addError("/api/schedule/housekeeping failed", e); } catch (Exception ignore) {}
            return ResponseEntity.internalServerError().body(ApiResponse.failure("補完処理に失敗しました"));
        }
    }

    @DeleteMapping("/reset/day")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetDay(@RequestParam("date") LocalDate date) {
        try {
            long before = assignmentRepository.findByWorkDate(date).size();
            assignmentRepository.deleteByWorkDate(date);
            Map<String, Object> meta = new HashMap<>();
            meta.put("date", date.toString());
            meta.put("deleted", before);
            return ResponseEntity.ok(ApiResponse.success("対象日のシフトを削除しました", meta));
        } catch (Exception e) {
            logger.error("/api/schedule/reset/day failed for date={}", date, e);
            try { if (errorLogBuffer != null) errorLogBuffer.addError("/api/schedule/reset/day failed", e); } catch (Exception ignore) {}
            return ResponseEntity.internalServerError().body(ApiResponse.failure("対象日の削除に失敗しました"));
        }
    }
}
