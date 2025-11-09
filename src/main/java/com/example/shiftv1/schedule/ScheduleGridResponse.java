package com.example.shiftv1.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ScheduleGridResponse(
        LocalDate startDate,
        LocalDate endDate,
        List<ScheduleGridEmployeeDto> employees,
        List<ScheduleGridAssignmentDto> assignments,
        Map<String, Object> meta) {
}

