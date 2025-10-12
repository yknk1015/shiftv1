package com.example.shiftv1.schedule;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
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
