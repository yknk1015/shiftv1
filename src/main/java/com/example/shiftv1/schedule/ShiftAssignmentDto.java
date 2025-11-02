package com.example.shiftv1.schedule;

import java.time.LocalDate;
import java.time.LocalTime;

public record ShiftAssignmentDto(
        Long id,
        LocalDate workDate,
        String shiftName,
        LocalTime startTime,
        LocalTime endTime,
        String employeeName,
        Boolean isFree,
        Boolean isOff) {

    public static ShiftAssignmentDto from(ShiftAssignment assignment) {
        boolean free = safeBool(assignment.getIsFree());
        boolean off = safeBool(assignment.getIsOff());
        // Mask times for FREE/OFF so clients can display them as blank without DB schema change
        java.time.LocalTime st = (free || off) ? null : assignment.getStartTime();
        java.time.LocalTime et = (free || off) ? null : assignment.getEndTime();
        return new ShiftAssignmentDto(
                assignment.getId(),
                assignment.getWorkDate(),
                assignment.getShiftName(),
                st,
                et,
                assignment.getEmployee().getName(),
                free,
                off
        );
    }

    private static Boolean safeBool(Boolean b) { return b != null && b; }
}
