package com.example.shiftv1.schedule;

import com.example.shiftv1.breaks.BreakPeriod;

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
        LocalTime breakStart,
        LocalTime breakEnd,
        Integer breakMinutes,
        Boolean isFree,
        Boolean isOff,
        Boolean isLeave) {

    public static ScheduleGridAssignmentDto from(ShiftAssignment assignment, BreakPeriod breakPeriod) {
        LocalTime breakStart = null;
        LocalTime breakEnd = null;
        Integer breakMinutes = null;
        if (breakPeriod != null) {
            breakStart = breakPeriod.getStartTime();
            breakEnd = breakPeriod.getEndTime();
            if (breakStart != null && breakEnd != null) {
                breakMinutes = (int) java.time.temporal.ChronoUnit.MINUTES.between(breakStart, breakEnd);
            }
        }
        return new ScheduleGridAssignmentDto(
                assignment.getId(),
                assignment.getEmployee() != null ? assignment.getEmployee().getId() : null,
                assignment.getEmployee() != null ? assignment.getEmployee().getName() : null,
                assignment.getWorkDate(),
                assignment.getShiftName(),
                assignment.getStartTime(),
                assignment.getEndTime(),
                breakStart,
                breakEnd,
                breakMinutes,
                normalizeFlag(assignment.getIsFree()),
                normalizeFlag(assignment.getIsOff()),
                normalizeFlag(assignment.getIsLeave())
        );
    }

    private static Boolean normalizeFlag(Boolean flag) {
        return flag != null && flag;
    }
}
