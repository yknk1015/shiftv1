package com.example.shiftv1.schedule;

public record ScheduleGridEmployeeDto(Long id, String name, String role) {
    public static ScheduleGridEmployeeDto from(com.example.shiftv1.employee.Employee employee) {
        return new ScheduleGridEmployeeDto(
                employee.getId(),
                employee.getName(),
                employee.getRole()
        );
    }
}

