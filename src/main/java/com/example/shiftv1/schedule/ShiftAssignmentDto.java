package com.example.shiftv1.schedule;

import java.time.LocalDate;
import java.time.LocalTime;

public record ShiftAssignmentDto(
        Long id,
        LocalDate workDate,
        String shiftName,
        LocalTime startTime,
        LocalTime endTime,
        String employeeName) {

    public static ShiftAssignmentDto from(ShiftAssignment assignment) {
        return new ShiftAssignmentDto(
                assignment.getId(),
                assignment.getWorkDate(),
                assignment.getShiftName(),
                assignment.getStartTime(),
                assignment.getEndTime(),
                assignment.getEmployee().getName()
        );
    }
}
