package com.example.shiftv1.schedule;

import java.time.LocalDate;
import java.time.LocalTime;

public record ScheduleGridAssignmentDto(
        Long id,
        Long employeeId,
        String employeeName,
        LocalDate workDate,
        String shiftName,
        LocalTime startTime,
        LocalTime endTime,
        Boolean isFree,
        Boolean isOff,
        Boolean isLeave) {

    public static ScheduleGridAssignmentDto from(ShiftAssignment assignment) {
        return new ScheduleGridAssignmentDto(
                assignment.getId(),
                assignment.getEmployee() != null ? assignment.getEmployee().getId() : null,
                assignment.getEmployee() != null ? assignment.getEmployee().getName() : null,
                assignment.getWorkDate(),
                assignment.getShiftName(),
                assignment.getStartTime(),
                assignment.getEndTime(),
                normalizeFlag(assignment.getIsFree()),
                normalizeFlag(assignment.getIsOff()),
                normalizeFlag(assignment.getIsLeave())
        );
    }

    private static Boolean normalizeFlag(Boolean flag) {
        return flag != null && flag;
    }
}

