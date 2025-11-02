package com.example.shiftv1.schedule;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.constraint.EmployeeConstraintRepository;
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
    private final ShiftAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeConstraintRepository constraintRepository;
    private final com.example.shiftv1.common.error.ErrorLogBuffer errorLogBuffer;

    public ScheduleController(ScheduleService scheduleService,
                              ShiftAssignmentRepository assignmentRepository,
                              EmployeeRepository employeeRepository,
                              EmployeeConstraintRepository constraintRepository,
                              com.example.shiftv1.common.error.ErrorLogBuffer errorLogBuffer) {
        this.scheduleService = scheduleService;
        this.assignmentRepository = assignmentRepository;
        this.employeeRepository = employeeRepository;
        this.constraintRepository = constraintRepository;
        this.errorLogBuffer = errorLogBuffer;
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentDto>>> generateSchedule(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            List<ShiftAssignment> assignments = scheduleService.generateMonthlySchedule(target.getYear(), target.getMonthValue());
            // Ensure weekly rest-days as explicit 休日 placeholders first
            scheduleService.ensureWeeklyHolidays(target.getYear(), target.getMonthValue());
            // Then fill remaining with FREE placeholders (00:00-00:05) for unassigned employees per day
            scheduleService.ensureFreePlaceholders(target.getYear(), target.getMonthValue());
            // Reload assignments to include FREE in response/meta
            assignments = assignmentRepository.findByWorkDateBetween(target.atDay(1), target.atEndOfMonth());
            Map<String, Object> meta = buildScheduleMeta(assignments, target);
            meta.put("generatedCount", assignments.size());
            List<ShiftAssignmentDto> data = assignments.stream().map(ShiftAssignmentDto::from).collect(Collectors.toList());
            return ResponseEntity.ok(ApiResponse.success("シフトを生成しました", data, meta));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("シフト生成に失敗しました"));
        }
    }

    // --- Manual assignment CRUD ---
    public static class CreateAssignmentRequest {
        public String date;      // yyyy-MM-dd
        public Long employeeId;
        public String startTime; // HH:mm
        public String endTime;   // HH:mm
        public String shiftName; // optional
    }

    public static class UpdateAssignmentRequest {
        public Long employeeId;     // optional
        public String startTime;    // optional
        public String endTime;      // optional
        public String shiftName;    // optional
    }

    private static java.time.LocalTime parseTime(String t) {
        if (t == null) return null;
        String s = t.length() == 5 ? t + ":00" : t;
        return java.time.LocalTime.parse(s);
    }

    @PostMapping("/assignments")
    public ResponseEntity<ApiResponse<ShiftAssignmentDto>> createAssignment(@RequestBody CreateAssignmentRequest req) {
        try {
            if (req == null || req.date == null || req.employeeId == null || req.startTime == null || req.endTime == null) {
                return ResponseEntity.badRequest().body(ApiResponse.failure("必須項目が不足しています"));
            }
            java.time.LocalDate date = java.time.LocalDate.parse(req.date);
            java.time.LocalTime s = parseTime(req.startTime);
            java.time.LocalTime e = parseTime(req.endTime);
            if (s == null || e == null || !s.isBefore(e)) {
                return ResponseEntity.badRequest().body(ApiResponse.failure("時間帯が不正です"));
            }
            Employee emp = employeeRepository.findById(req.employeeId)
                    .orElse(null);
            if (emp == null) return ResponseEntity.status(404).body(ApiResponse.failure("従業員が見つかりません"));

            // Constraint check (UNAVAILABLE, LIMITED window, VACATION/SICK/PERSONAL)
            var cons = constraintRepository.findByEmployeeAndDateAndActiveTrue(emp, date);
            for (var c : cons) {
                var type = c.getType();
                if (type == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.UNAVAILABLE
                        || type == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.VACATION
                        || type == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.SICK_LEAVE
                        || type == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.PERSONAL) {
                    return ResponseEntity.badRequest().body(ApiResponse.failure("この日は勤務不可の制約があります"));
                }
                if (type == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.LIMITED) {
                    var cs = c.getStartTime(); var ce = c.getEndTime();
                    if (cs != null && ce != null && (s.isBefore(cs) || e.isAfter(ce))) {
                        return ResponseEntity.badRequest().body(ApiResponse.failure("制約時間外です"));
                    }
                }
            }

            // Overlap check for employee on the date
            var sameDay = assignmentRepository.findByEmployeeAndWorkDate(emp, date);
            for (var a : sameDay) {
                if (s.isBefore(a.getEndTime()) && e.isAfter(a.getStartTime())) {
                    return ResponseEntity.badRequest().body(ApiResponse.failure("既存シフトと重複しています"));
                }
            }

            String name = (req.shiftName == null || req.shiftName.isBlank()) ? "Manual" : req.shiftName.trim();
            ShiftAssignment saved = assignmentRepository.save(new ShiftAssignment(date, name, s, e, emp));
            return ResponseEntity.status(201).body(ApiResponse.success("シフトを作成しました", ShiftAssignmentDto.from(saved)));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("作成に失敗しました"));
        }
    }

    @PutMapping("/assignments/{id}")
    public ResponseEntity<ApiResponse<ShiftAssignmentDto>> updateAssignment(@PathVariable Long id, @RequestBody UpdateAssignmentRequest req) {
        try {
            var opt = assignmentRepository.findById(id);
            if (opt.isEmpty()) return ResponseEntity.status(404).body(ApiResponse.failure("シフトが見つかりません"));
            ShiftAssignment a = opt.get();
            Employee emp = a.getEmployee();
            if (req.employeeId != null) {
                var e2 = employeeRepository.findById(req.employeeId).orElse(null);
                if (e2 == null) return ResponseEntity.status(404).body(ApiResponse.failure("従業員が見つかりません"));
                emp = e2;
            }
            java.time.LocalTime s = req.startTime != null ? parseTime(req.startTime) : a.getStartTime();
            java.time.LocalTime e = req.endTime != null ? parseTime(req.endTime) : a.getEndTime();
            if (!s.isBefore(e)) return ResponseEntity.badRequest().body(ApiResponse.failure("時間帯が不正です"));

            // Constraint check at the assignment date
            var date = a.getWorkDate();
            var cons = constraintRepository.findByEmployeeAndDateAndActiveTrue(emp, date);
            for (var c : cons) {
                var type = c.getType();
                if (type == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.UNAVAILABLE
                        || type == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.VACATION
                        || type == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.SICK_LEAVE
                        || type == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.PERSONAL) {
                    return ResponseEntity.badRequest().body(ApiResponse.failure("この日は勤務不可の制約があります"));
                }
                if (type == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.LIMITED) {
                    var cs = c.getStartTime(); var ce = c.getEndTime();
                    if (cs != null && ce != null && (s.isBefore(cs) || e.isAfter(ce))) {
                        return ResponseEntity.badRequest().body(ApiResponse.failure("制約時間外です"));
                    }
                }
            }

            // Overlap with other assignments of the same employee on that day
            var sameDay = assignmentRepository.findByEmployeeAndWorkDate(emp, a.getWorkDate());
            for (var x : sameDay) {
                if (x.getId().equals(a.getId())) continue;
                if (s.isBefore(x.getEndTime()) && e.isAfter(x.getStartTime())) {
                    return ResponseEntity.badRequest().body(ApiResponse.failure("既存シフトと重複しています"));
                }
            }

            a.setEmployee(emp);
            a.setStartTime(s);
            a.setEndTime(e);
            if (req.shiftName != null && !req.shiftName.isBlank()) a.setShiftName(req.shiftName.trim());
            ShiftAssignment saved = assignmentRepository.save(a);
            return ResponseEntity.ok(ApiResponse.success("シフトを更新しました", ShiftAssignmentDto.from(saved)));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("更新に失敗しました"));
        }
    }

    @DeleteMapping("/assignments/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAssignment(@PathVariable Long id) {
        try {
            if (!assignmentRepository.existsById(id)) {
                return ResponseEntity.status(404).body(ApiResponse.failure("シフトが見つかりません"));
            }
            assignmentRepository.deleteById(id);
            return ResponseEntity.ok(ApiResponse.success("シフトを削除しました", null));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("削除に失敗しました"));
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
            List<ShiftAssignmentDto> data = assignments.stream().map(ShiftAssignmentDto::from).collect(Collectors.toList());
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
            LocalDate day = LocalDate.parse(req.date);
            java.time.LocalTime s = java.time.LocalTime.parse(req.startTime.length()==5? req.startTime+":00": req.startTime);
            java.time.LocalTime e = java.time.LocalTime.parse(req.endTime.length()==5? req.endTime+":00": req.endTime);
            int n = req.seats == null ? 1 : Math.max(0, req.seats);
            if (!s.isBefore(e) || n <= 0) {
                return ResponseEntity.badRequest().body(ApiResponse.failure("時間範囲または人数が不正です"));
            }
            Map<String,Object> result = scheduleService.addCoreTime(day, req.skillId, req.skillCode, s, e, n);
            return ResponseEntity.ok(ApiResponse.success("コアタイムを追加しました", result));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("コアタイム追加に失敗しました: " + ex.getMessage()));
        }
    }

    // Legacy demand endpoint (kept for compatibility in UI templates)
    @PreAuthorize("hasRole('ADMIN')")
    @RequestMapping(value = "/generate-from-demand-async", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<ApiResponse<List<ShiftAssignmentDto>>> legacyGenerateFromDemandAsync(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "granularity", defaultValue = "60") int granularity,
            @RequestParam(name = "reset", defaultValue = "true") boolean reset) {
        return generateDemandAsyncStable(year, month, granularity, reset);
    }

    // Stable demand endpoints
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/generate/demand")
    public ResponseEntity<ApiResponse<Map<String,Object>>> generateDemandStable(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            @RequestParam(name = "granularity", defaultValue = "60") int granularity,
            @RequestParam(name = "reset", defaultValue = "true") boolean reset) {
        YearMonth target = resolveYearMonth(year, month);
        List<ShiftAssignment> res = scheduleService.generateMonthlyFromDemand(target.getYear(), target.getMonthValue(), granularity, reset);
        Map<String, Object> meta = new HashMap<>();
        meta.put("year", target.getYear());
        meta.put("month", target.getMonthValue());
        meta.put("generated", res == null ? 0 : res.size());
        return ResponseEntity.ok(ApiResponse.success("デマンドベース同期生成が完了しました", meta));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/generate/demand/day")
    public ResponseEntity<ApiResponse<Map<String,Object>>> generateDemandDayStable(
            @RequestParam(name = "date") LocalDate date,
            @RequestParam(name = "reset", defaultValue = "true") boolean reset) {
        List<ShiftAssignment> res = scheduleService.generateForDateFromDemand(date, reset);
        Map<String, Object> meta = new HashMap<>();
        meta.put("date", date.toString());
        meta.put("generated", res == null ? 0 : res.size());
        return ResponseEntity.ok(ApiResponse.success("一日分の同期生成が完了しました", meta));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/generate/demand/async")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentDto>>> generateDemandAsyncStable(
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
            return ResponseEntity.ok(ApiResponse.success("対象月のシフトをリセットしました", Map.of(), meta));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("シフトのリセットに失敗しました"));
        }
    }

    private YearMonth resolveYearMonth(Integer year, Integer month) {
        if (year != null && month != null) {
            return YearMonth.of(year, month);
        }
        LocalDate today = LocalDate.now();
        return YearMonth.of(today.getYear(), today.getMonthValue());
    }

    private Map<String, Object> buildScheduleMeta(List<ShiftAssignment> assignments, YearMonth target) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("year", target.getYear());
        meta.put("month", target.getMonthValue());
        meta.put("count", assignments.size());
        long uniqueEmployees = assignments.stream().map(a -> a.getEmployee().getId()).distinct().count();
        long workingDays = assignments.stream().map(ShiftAssignment::getWorkDate).distinct().count();
        meta.put("uniqueEmployees", uniqueEmployees);
        meta.put("workingDays", workingDays);
        return meta;
    }
}
