package com.example.shiftv1.schedule;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/schedule/stats")
public class ScheduleStatsController {

    private final ShiftAssignmentRepository assignmentRepository;
    private final com.example.shiftv1.employee.EmployeeRepository employeeRepository;
    private final com.example.shiftv1.employee.EmployeeRuleRepository employeeRuleRepository;

    public ScheduleStatsController(ShiftAssignmentRepository assignmentRepository,
                                   com.example.shiftv1.employee.EmployeeRepository employeeRepository,
                                   com.example.shiftv1.employee.EmployeeRuleRepository employeeRuleRepository) {
        this.assignmentRepository = assignmentRepository;
        this.employeeRepository = employeeRepository;
        this.employeeRuleRepository = employeeRuleRepository;
    }

    @GetMapping("/monthly")
    public ResponseEntity<ApiResponse<MonthlyStatsResponse>> getMonthlyStats(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {

        try {
            YearMonth target = resolveYearMonth(year, month);
            LocalDate start = target.atDay(1);
            LocalDate end = target.atEndOfMonth();

            List<ShiftAssignment> assignments = assignmentRepository.findByWorkDateBetween(start, end);

            MonthlyStatsResponse stats = calculateMonthlyStats(assignments, target);
            return ResponseEntity.ok(ApiResponse.success("月次統計を取得しました", stats));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("月次統計の取得に失敗しました"));
        }
    }

    @GetMapping("/employee-workload")
    public ResponseEntity<ApiResponse<List<EmployeeWorkloadResponse>>> getEmployeeWorkload(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {

        try {
            YearMonth target = resolveYearMonth(year, month);
            LocalDate start = target.atDay(1);
            LocalDate end = target.atEndOfMonth();

            List<ShiftAssignment> assignments = assignmentRepository.findByWorkDateBetween(start, end);

            Map<String, Long> workloadByEmployee = assignments.stream()
                    .collect(Collectors.groupingBy(
                            assignment -> assignment.getEmployee().getName(),
                            Collectors.counting()
                    ));

            List<EmployeeWorkloadResponse> workload = workloadByEmployee.entrySet().stream()
                    .map(entry -> new EmployeeWorkloadResponse(entry.getKey(), entry.getValue()))
                    .sorted((a, b) -> Long.compare(b.shiftCount(), a.shiftCount()))
                    .toList();

            return ResponseEntity.ok(ApiResponse.success("従業員別勤務量を取得しました", workload));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("従業員別勤務量の取得に失敗しました"));
        }
    }

    // Weekly OFF debug view (Sunday-Saturday weeks)
    @GetMapping("/weekly-off")
    public ResponseEntity<ApiResponse<java.util.List<java.util.Map<String, Object>>>> getWeeklyOff(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            LocalDate start = target.atDay(1);
            LocalDate end = target.atEndOfMonth();

            // Preload assignments in month
            java.util.List<ShiftAssignment> all = assignmentRepository.findByWorkDateBetween(start, end);
            java.util.Map<LocalDate, java.util.List<ShiftAssignment>> byDate = all.stream()
                    .collect(java.util.stream.Collectors.groupingBy(ShiftAssignment::getWorkDate));

            // Prepare weeks aligned to Sunday
            int startDow = start.getDayOfWeek().getValue() % 7; // SUNDAY->0
            LocalDate cursor = start.minusDays(startDow);
            java.util.List<java.util.Map<String, Object>> weeks = new java.util.ArrayList<>();
            java.util.List<com.example.shiftv1.employee.Employee> emps = employeeRepository.findAll();

            while (!cursor.isAfter(end)) {
                LocalDate weekStart = cursor;
                LocalDate weekEnd = weekStart.plusDays(6);
                LocalDate rangeStart = weekStart.isBefore(start) ? start : weekStart;
                LocalDate rangeEnd = weekEnd.isAfter(end) ? end : weekEnd;

                java.util.List<java.util.Map<String, Object>> summaries = new java.util.ArrayList<>();
                for (com.example.shiftv1.employee.Employee emp : emps) {
                    Long empId = emp.getId();
                    int targetRest = employeeRuleRepository.findByEmployeeId(empId)
                            .map(com.example.shiftv1.employee.EmployeeRule::getWeeklyRestDays)
                            .filter(v -> v != null && v >= 0)
                            .orElse(2);
                    java.util.List<String> offDates = new java.util.ArrayList<>();
                    int offCount = 0;
                    for (LocalDate d = rangeStart; !d.isAfter(rangeEnd); d = d.plusDays(1)) {
                        java.util.List<ShiftAssignment> dayList = byDate.getOrDefault(d, java.util.Collections.emptyList());
                        boolean hasOff = dayList.stream().anyMatch(sa -> java.util.Objects.equals(sa.getEmployee().getId(), empId) &&
                                (Boolean.TRUE.equals(sa.getIsOff()) || (sa.getShiftName() != null && ("休日".equals(sa.getShiftName()) || "OFF".equalsIgnoreCase(sa.getShiftName())))));
                        if (hasOff) { offCount++; offDates.add(d.toString()); }
                    }
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("employeeId", empId);
                    m.put("employeeName", emp.getName());
                    m.put("targetRestDays", targetRest);
                    m.put("offCount", offCount);
                    m.put("offDates", offDates);
                    m.put("meetsTarget", offCount >= targetRest);
                    summaries.add(m);
                }

                java.util.Map<String, Object> wk = new java.util.HashMap<>();
                wk.put("weekStart", weekStart.toString());
                wk.put("weekEnd", weekEnd.toString());
                wk.put("rangeStart", rangeStart.toString());
                wk.put("rangeEnd", rangeEnd.toString());
                wk.put("employees", summaries);
                weeks.add(wk);
                cursor = weekEnd.plusDays(1);
            }

            return ResponseEntity.ok(ApiResponse.success("weekly off summary", weeks));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("週休日デバッグの取得に失敗しました"));
        }
    }

    @GetMapping("/employee-days")
    public ResponseEntity<ApiResponse<List<EmployeeDaysResponse>>> getEmployeeDays(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            LocalDate start = target.atDay(1);
            LocalDate end = target.atEndOfMonth();

            List<ShiftAssignment> assignments = assignmentRepository.findByWorkDateBetween(start, end);

            Map<Long, Long> workDaysByEmployeeId = assignments.stream()
                    .filter(a -> {
                        Boolean free = null; Boolean off = null;
                        try { free = a.getIsFree(); off = a.getIsOff(); } catch (Exception ignored) {}
                        if (Boolean.TRUE.equals(free) || Boolean.TRUE.equals(off)) return false;
                        String name = a.getShiftName();
                        return !(name != null && ("FREE".equalsIgnoreCase(name) || "休日".equals(name) || "OFF".equalsIgnoreCase(name)));
                    })
                    .collect(Collectors.groupingBy(
                            a -> a.getEmployee().getId(),
                            Collectors.mapping(ShiftAssignment::getWorkDate, Collectors.collectingAndThen(Collectors.toSet(), set -> (long) set.size()))
                    ));

            int daysInMonth = target.lengthOfMonth();

            List<EmployeeDaysResponse> rows = employeeRepository.findAll().stream()
                    .map(e -> {
                        long workDays = workDaysByEmployeeId.getOrDefault(e.getId(), 0L);
                        long restDays = daysInMonth - workDays;
                        return new EmployeeDaysResponse(e.getName(), workDays, restDays, daysInMonth);
                    })
                    .sorted((a, b) -> Long.compare(b.workDays(), a.workDays()))
                    .toList();

            return ResponseEntity.ok(ApiResponse.success("従業員の勤務日数・休日日数を取得しました", rows));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("従業員の勤務/休日統計の取得に失敗しました"));
        }
    }

    @GetMapping("/shift-distribution")
    public ResponseEntity<ApiResponse<ShiftDistributionResponse>> getShiftDistribution(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {

        try {
            YearMonth target = resolveYearMonth(year, month);
            LocalDate start = target.atDay(1);
            LocalDate end = target.atEndOfMonth();

            List<ShiftAssignment> assignments = assignmentRepository.findByWorkDateBetween(start, end);

            Map<String, Long> distribution = assignments.stream()
                    .collect(Collectors.groupingBy(
                            ShiftAssignment::getShiftName,
                            Collectors.counting()
                    ));

            return ResponseEntity.ok(ApiResponse.success("シフト分布を取得しました", new ShiftDistributionResponse(distribution)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("シフト分布の取得に失敗しました"));
        }
    }

    private MonthlyStatsResponse calculateMonthlyStats(List<ShiftAssignment> assignments, YearMonth target) {
        long totalShifts = assignments.size();
        long uniqueEmployees = assignments.stream()
                .map(assignment -> assignment.getEmployee().getId())
                .distinct()
                .count();
        
        Map<String, Long> shiftsByType = assignments.stream()
                .collect(Collectors.groupingBy(
                        ShiftAssignment::getShiftName,
                        Collectors.counting()
                ));
        
        return new MonthlyStatsResponse(
                target.toString(),
                totalShifts,
                uniqueEmployees,
                shiftsByType
        );
    }

    private YearMonth resolveYearMonth(Integer year, Integer month) {
        if (year != null && month != null) {
            return YearMonth.of(year, month);
        }
        LocalDate today = LocalDate.now();
        return YearMonth.of(today.getYear(), today.getMonthValue());
    }

    public record MonthlyStatsResponse(
            String month,
            long totalShifts,
            long uniqueEmployees,
            Map<String, Long> shiftsByType
    ) {}

    public record EmployeeWorkloadResponse(String employeeName, long shiftCount) {}

    public record EmployeeDaysResponse(String employeeName, long workDays, long restDays, int totalDays) {}

    public record ShiftDistributionResponse(Map<String, Long> distribution) {}

    @GetMapping("/free-daily")
    public ResponseEntity<ApiResponse<java.util.List<FreeDailyResponse>>> getFreeDaily(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            LocalDate start = target.atDay(1);
            LocalDate end = target.atEndOfMonth();

            List<ShiftAssignment> assignments = assignmentRepository.findByWorkDateBetween(start, end);
            Map<LocalDate, Long> cnt = assignments.stream()
                    .filter(a -> {
                        Boolean free = null; try { free = a.getIsFree(); } catch (Exception ignored) {}
                        if (Boolean.TRUE.equals(free)) return true;
                        String name = a.getShiftName();
                        return name != null && "FREE".equalsIgnoreCase(name);
                    })
                    .collect(Collectors.groupingBy(ShiftAssignment::getWorkDate, Collectors.counting()));

            java.util.List<FreeDailyResponse> list = java.util.stream.IntStream.rangeClosed(1, target.lengthOfMonth())
                    .mapToObj(d -> target.atDay(d))
                    .map(day -> new FreeDailyResponse(day, cnt.getOrDefault(day, 0L)))
                    .toList();

            return ResponseEntity.ok(ApiResponse.success("フリー勤務数（日別）を取得しました", list));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("フリー勤務数の取得に失敗しました"));
        }
    }

    public record FreeDailyResponse(LocalDate date, long freeCount) {}

    @GetMapping("/off-daily")
    public ResponseEntity<ApiResponse<java.util.List<OffDailyResponse>>> getOffDaily(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        try {
            YearMonth target = resolveYearMonth(year, month);
            LocalDate start = target.atDay(1);
            LocalDate end = target.atEndOfMonth();

            List<ShiftAssignment> assignments = assignmentRepository.findByWorkDateBetween(start, end);
            Map<LocalDate, Long> cnt = assignments.stream()
                    .filter(a -> {
                        Boolean off = null; try { off = a.getIsOff(); } catch (Exception ignored) {}
                        if (Boolean.TRUE.equals(off)) return true;
                        String name = a.getShiftName();
                        return name != null && ("休日".equals(name) || "OFF".equalsIgnoreCase(name));
                    })
                    .collect(Collectors.groupingBy(ShiftAssignment::getWorkDate, Collectors.counting()));

            java.util.List<OffDailyResponse> list = java.util.stream.IntStream.rangeClosed(1, target.lengthOfMonth())
                    .mapToObj(d -> target.atDay(d))
                    .map(day -> new OffDailyResponse(day, cnt.getOrDefault(day, 0L)))
                    .toList();

            return ResponseEntity.ok(ApiResponse.success("休日人数（日別）を取得しました", list));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(ApiResponse.failure("休日人数の取得に失敗しました"));
        }
    }

    public record OffDailyResponse(LocalDate date, long offCount) {}
}
