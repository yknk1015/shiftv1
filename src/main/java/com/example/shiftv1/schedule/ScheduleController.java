package com.example.shiftv1.schedule;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PostMapping("/generate")
    public ResponseEntity<List<ShiftAssignmentDto>> generateSchedule(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        YearMonth target = resolveYearMonth(year, month);
        List<ShiftAssignment> assignments = scheduleService.generateMonthlySchedule(target.getYear(), target.getMonthValue());
        return ResponseEntity.ok(assignments.stream().map(ShiftAssignmentDto::from).collect(Collectors.toList()));
    }

    @GetMapping
    public ResponseEntity<List<ShiftAssignmentDto>> getSchedule(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        YearMonth target = resolveYearMonth(year, month);
        List<ShiftAssignment> assignments = scheduleService.getMonthlySchedule(target.getYear(), target.getMonthValue());
        return ResponseEntity.ok(assignments.stream().map(ShiftAssignmentDto::from).collect(Collectors.toList()));
    }

    /**
     * 現在のシフト設定を取得（学習用）
     */
    @GetMapping("/configuration")
    public ResponseEntity<ShiftConfiguration> getShiftConfiguration() {
        return ResponseEntity.ok(scheduleService.getCurrentShiftConfiguration());
    }

    /**
     * シフト設定を更新（学習用）
     */
    @PutMapping("/configuration")
    public ResponseEntity<String> updateShiftConfiguration(@RequestBody ShiftConfigurationRequest request) {
        try {
            ShiftConfiguration config = ShiftConfiguration.builder()
                .weekdayAmStart(LocalTime.parse(request.weekdayAmStart()))
                .weekdayAmEnd(LocalTime.parse(request.weekdayAmEnd()))
                .weekdayPmStart(LocalTime.parse(request.weekdayPmStart()))
                .weekdayPmEnd(LocalTime.parse(request.weekdayPmEnd()))
                .weekdayEmployeesPerShift(request.weekdayEmployeesPerShift())
                .weekendStart(LocalTime.parse(request.weekendStart()))
                .weekendEnd(LocalTime.parse(request.weekendEnd()))
                .weekendEmployeesPerShift(request.weekendEmployeesPerShift())
                .build();
            
            scheduleService.updateShiftConfiguration(config);
            return ResponseEntity.ok("シフト設定を更新しました");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("設定の更新に失敗しました: " + e.getMessage());
        }
    }

    /**
     * シフト生成の統計情報を取得（学習用）
     */
    @GetMapping("/generation-stats")
    public ResponseEntity<GenerationStatsResponse> getGenerationStats(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        YearMonth target = resolveYearMonth(year, month);
        List<ShiftAssignment> assignments = scheduleService.getMonthlySchedule(target.getYear(), target.getMonthValue());
        
        // 統計情報を計算
        long totalShifts = assignments.size();
        long uniqueEmployees = assignments.stream()
            .map(a -> a.getEmployee().getId())
            .distinct()
            .count();
        
        double avgShiftsPerEmployee = uniqueEmployees > 0 ? (double) totalShifts / uniqueEmployees : 0;
        
        return ResponseEntity.ok(new GenerationStatsResponse(
            target.toString(),
            totalShifts,
            uniqueEmployees,
            avgShiftsPerEmployee
        ));
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

    // リクエスト/レスポンス用のレコード
    public record ShiftConfigurationRequest(
        String weekdayAmStart,
        String weekdayAmEnd,
        String weekdayPmStart,
        String weekdayPmEnd,
        int weekdayEmployeesPerShift,
        String weekendStart,
        String weekendEnd,
        int weekendEmployeesPerShift
    ) {}

    public record GenerationStatsResponse(
        String month,
        long totalShifts,
        long uniqueEmployees,
        double avgShiftsPerEmployee
    ) {}
}
