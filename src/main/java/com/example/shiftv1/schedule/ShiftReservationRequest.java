package com.example.shiftv1.schedule;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record ShiftReservationRequest(
        @NotNull Long employeeId,
        Long skillId,
        @NotNull LocalDate workDate,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        String label,
        String note
) {
}
