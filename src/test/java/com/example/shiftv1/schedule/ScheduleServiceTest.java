package com.example.shiftv1.schedule;

import com.example.shiftv1.config.ShiftConfig;
import com.example.shiftv1.config.ShiftConfigRepository;
import com.example.shiftv1.constraint.EmployeeConstraint;
import com.example.shiftv1.constraint.EmployeeConstraintRepository;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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

    @Autowired
    private ShiftConfigRepository shiftConfigRepository;

    @Autowired
    private EmployeeConstraintRepository constraintRepository;

    @BeforeEach
    void setUp() {
        assignmentRepository.deleteAll();
        constraintRepository.deleteAll();
    }

    @Test
    void generateMonthlySchedule_assignsEmployeesForEachShift() {
        int year = 2024;
        int month = 7;
        List<ShiftAssignment> assignments = scheduleService.generateMonthlySchedule(year, month);

        List<ShiftConfig> activeConfigs = shiftConfigRepository.findByActiveTrue();
        Map<String, ShiftConfig> configByName = activeConfigs.stream()
                .collect(Collectors.toMap(ShiftConfig::getName, Function.identity()));

        List<ShiftConfig> weekdayConfigs = activeConfigs.stream()
                .filter(config -> !Boolean.TRUE.equals(config.getWeekend()))
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();

        List<ShiftConfig> weekendConfigs = activeConfigs.stream()
                .filter(config -> Boolean.TRUE.equals(config.getWeekend()))
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();

        if (weekdayConfigs.isEmpty()) {
            weekdayConfigs = activeConfigs;
        }
        if (weekendConfigs.isEmpty()) {
            weekendConfigs = activeConfigs;
        }

        YearMonth target = YearMonth.of(year, month);
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();

        int expected = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            boolean isWeekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            List<ShiftConfig> configsForDay = isWeekend ? weekendConfigs : weekdayConfigs;
            expected += configsForDay.stream().mapToInt(ShiftConfig::getRequiredEmployees).sum();
        }

        assertThat(assignments).hasSize(expected);

        // 各シフトが設定どおりの時間で作成されていることを確認
        assignments.forEach(assignment -> {
            ShiftConfig config = configByName.get(assignment.getShiftName());
            assertThat(config).as("シフト設定が存在すること").isNotNull();
            assertThat(assignment.getStartTime()).isEqualTo(config.getStartTime());
            assertThat(assignment.getEndTime()).isEqualTo(config.getEndTime());
        });

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

    @Test
    void generateMonthlySchedule_respectsEmployeeConstraints() {
        int year = 2024;
        int month = 7;
        List<Employee> employees = employeeRepository.findAll();
        assertThat(employees).isNotEmpty();

        Employee unavailableEmployee = employees.get(0);
        Employee limitedEmployee = employees.get(1);

        LocalDate unavailableDate = LocalDate.of(year, month, 5);
        LocalDate limitedDate = LocalDate.of(year, month, 8);

        constraintRepository.save(new EmployeeConstraint(
                unavailableEmployee,
                unavailableDate,
                EmployeeConstraint.ConstraintType.UNAVAILABLE,
                "休暇"
        ));

        constraintRepository.save(new EmployeeConstraint(
                limitedEmployee,
                limitedDate,
                EmployeeConstraint.ConstraintType.LIMITED,
                "午後のみ勤務可能",
                LocalTime.of(15, 0),
                LocalTime.of(21, 0)
        ));

        List<ShiftAssignment> assignments = scheduleService.generateMonthlySchedule(year, month);

        Map<LocalDate, List<ShiftAssignment>> assignmentsByDate = assignments.stream()
                .collect(Collectors.groupingBy(ShiftAssignment::getWorkDate));

        // 勤務不可日の割り当て確認
        List<ShiftAssignment> unavailableDayAssignments = assignmentsByDate.getOrDefault(unavailableDate, List.of());
        assertThat(unavailableDayAssignments)
                .extracting(assignment -> assignment.getEmployee().getId())
                .doesNotContain(unavailableEmployee.getId());

        // 時間制限のある従業員が午前シフトに割り当てられていないこと
        List<ShiftAssignment> limitedDayAssignments = assignmentsByDate.getOrDefault(limitedDate, List.of());
        Set<Long> limitedEmployeeAssignments = limitedDayAssignments.stream()
                .filter(assignment -> assignment.getEmployee().getId().equals(limitedEmployee.getId()))
                .map(ShiftAssignment::getId)
                .collect(Collectors.toSet());

        limitedDayAssignments.stream()
                .filter(assignment -> assignment.getStartTime().isBefore(LocalTime.of(15, 0)))
                .forEach(assignment ->
                        assertThat(assignment.getEmployee().getId())
                                .as("午後から勤務可能な従業員は午前シフトに入らない")
                                .isNotEqualTo(limitedEmployee.getId())
                );

        // 割り当てが存在する場合は制限時間内であることを確認
        limitedDayAssignments.stream()
                .filter(assignment -> limitedEmployeeAssignments.contains(assignment.getId()))
                .forEach(assignment -> {
                    assertThat(assignment.getStartTime()).isGreaterThanOrEqualTo(LocalTime.of(15, 0));
                    assertThat(assignment.getEndTime()).isLessThanOrEqualTo(LocalTime.of(21, 0));
                });
    }
}
