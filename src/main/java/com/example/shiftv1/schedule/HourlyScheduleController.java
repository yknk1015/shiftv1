package com.example.shiftv1.schedule;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schedule")
public class HourlyScheduleController {

    private final ScheduleService scheduleService;

    public HourlyScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PostMapping("/generate-hourly")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentDto>>> generateHourly(
            @RequestParam("date") LocalDate date,
            @RequestParam(name = "startHour", defaultValue = "9") int startHour,
            @RequestParam(name = "endHour", defaultValue = "18") int endHour,
            @RequestParam(name = "skillId", required = false) Long skillId
    ) {
        List<ShiftAssignment> assignments = scheduleService.generateHourlyForDay(date, startHour, endHour, skillId);
        List<ShiftAssignmentDto> data = assignments.stream().map(ShiftAssignmentDto::from).toList();
        Map<String, Object> meta = new HashMap<>();
        meta.put("count", data.size());
        return ResponseEntity.ok(ApiResponse.success("時間単位のシフトを作成しました", data, meta));
    }
}

