package com.example.shiftv1.schedule;

import com.example.shiftv1.employee.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ScheduleServiceTest {

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ShiftAssignmentRepository assignmentRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @BeforeEach
    void setUp() {
        assignmentRepository.deleteAll();
    }

    @Test
    void generateMonthlySchedule_assignsEmployeesForEachShift() {
        int year = 2024;
        int month = 7;
        List<ShiftAssignment> assignments = scheduleService.generateMonthlySchedule(year, month);

        YearMonth target = YearMonth.of(year, month);
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();

        int expected = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                expected += ScheduleService.HOLIDAY_EMPLOYEES_PER_SHIFT;
            } else {
                expected += ScheduleService.WEEKDAY_EMPLOYEES_PER_SHIFT * 2;
            }
        }

        assertThat(assignments).hasSize(expected);

        Map<LocalDate, List<ShiftAssignment>> byDate = assignments.stream()
                .collect(Collectors.groupingBy(ShiftAssignment::getWorkDate));

        byDate.forEach((date, dailyAssignments) -> {
            long uniqueEmployees = dailyAssignments.stream()
                    .map(assignment -> assignment.getEmployee().getId())
                    .distinct()
                    .count();
            assertThat(uniqueEmployees).isEqualTo(dailyAssignments.size());
        });

        assertThat(employeeRepository.count()).isEqualTo(30);
    }
}
