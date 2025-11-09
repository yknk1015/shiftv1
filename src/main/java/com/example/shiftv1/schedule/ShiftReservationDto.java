package com.example.shiftv1.schedule;

import java.time.LocalDate;
import java.time.LocalTime;

public record ShiftReservationDto(
        Long id,
        Long employeeId,
        String employeeName,
        Long skillId,
        String skillCode,
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        String label,
        String note,
        ShiftReservation.Status status
) {
    public static ShiftReservationDto from(ShiftReservation reservation) {
        return new ShiftReservationDto(
                reservation.getId(),
                reservation.getEmployee() != null ? reservation.getEmployee().getId() : null,
                reservation.getEmployee() != null ? reservation.getEmployee().getName() : null,
                reservation.getSkill() != null ? reservation.getSkill().getId() : null,
                reservation.getSkill() != null ? reservation.getSkill().getCode() : null,
                reservation.getWorkDate(),
                reservation.getStartTime(),
                reservation.getEndTime(),
                reservation.getLabel(),
                reservation.getNote(),
                reservation.getStatus()
        );
    }
}
