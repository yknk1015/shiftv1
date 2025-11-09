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
        Boolean isOff,
        Boolean isLeave) {

    public static ShiftAssignmentDto from(ShiftAssignment assignment) {
        boolean free = safeBool(assignment.getIsFree());
        boolean off = safeBool(assignment.getIsOff());
        boolean leave = safeBool(assignment.getIsLeave());
        // Mask times for FREE/OFF so clients can display them as blank without DB schema change
        java.time.LocalTime st = (free || off || leave) ? null : assignment.getStartTime();
        java.time.LocalTime et = (free || off || leave) ? null : assignment.getEndTime();
        return new ShiftAssignmentDto(
                assignment.getId(),
                assignment.getWorkDate(),
                assignment.getShiftName(),
                st,
                et,
                assignment.getEmployee().getName(),
                free,
                off,
                leave
        );
    }

    private static Boolean safeBool(Boolean b) { return b != null && b; }
}
