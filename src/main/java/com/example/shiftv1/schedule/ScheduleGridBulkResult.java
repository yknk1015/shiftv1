package com.example.shiftv1.schedule;

import java.util.List;

public record ScheduleGridBulkResult(int created, int updated, int deleted, List<String> warnings) {
    public ScheduleGridBulkResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

