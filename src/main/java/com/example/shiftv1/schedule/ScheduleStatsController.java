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

    public ScheduleStatsController(ShiftAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
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

    public record ShiftDistributionResponse(Map<String, Long> distribution) {}
}
