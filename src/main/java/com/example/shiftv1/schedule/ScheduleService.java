package com.example.shiftv1.schedule;

import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ScheduleService {

    static final LocalTime WEEKDAY_START_AM = LocalTime.of(9, 0);
    static final LocalTime WEEKDAY_END_AM = LocalTime.of(15, 0);
    static final LocalTime WEEKDAY_START_PM = LocalTime.of(15, 0);
    static final LocalTime WEEKDAY_END_PM = LocalTime.of(21, 0);
    static final LocalTime HOLIDAY_START = LocalTime.of(9, 0);
    static final LocalTime HOLIDAY_END = LocalTime.of(18, 0);
    static final int WEEKDAY_EMPLOYEES_PER_SHIFT = 4;
    static final int HOLIDAY_EMPLOYEES_PER_SHIFT = 5;

    private final EmployeeRepository employeeRepository;
    private final ShiftAssignmentRepository assignmentRepository;

    public ScheduleService(EmployeeRepository employeeRepository, ShiftAssignmentRepository assignmentRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Transactional
    public List<ShiftAssignment> generateMonthlySchedule(int year, int month) {
        YearMonth target = YearMonth.of(year, month);
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();

        List<Employee> employees = employeeRepository.findAll();
        if (employees.isEmpty()) {
            throw new IllegalStateException("No employees registered. Please add employees before generating schedule.");
        }

        assignmentRepository.deleteByWorkDateBetween(start, end);

        Deque<Employee> rotation = new ArrayDeque<>(employees);
        Map<LocalDate, Set<Long>> dailyAssignments = new HashMap<>();
        List<ShiftAssignment> results = new ArrayList<>();

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            boolean isWeekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (isWeekend) {
                results.addAll(assignEmployeesForShift(day, "Weekend", HOLIDAY_START, HOLIDAY_END,
                        HOLIDAY_EMPLOYEES_PER_SHIFT, rotation, dailyAssignments));
            } else {
                results.addAll(assignEmployeesForShift(day, "Weekday AM", WEEKDAY_START_AM, WEEKDAY_END_AM,
                        WEEKDAY_EMPLOYEES_PER_SHIFT, rotation, dailyAssignments));
                results.addAll(assignEmployeesForShift(day, "Weekday PM", WEEKDAY_START_PM, WEEKDAY_END_PM,
                        WEEKDAY_EMPLOYEES_PER_SHIFT, rotation, dailyAssignments));
            }
        }

        return assignmentRepository.saveAll(results);
    }

    public List<ShiftAssignment> getMonthlySchedule(int year, int month) {
        YearMonth target = YearMonth.of(year, month);
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();
        return assignmentRepository.findByWorkDateBetween(start, end);
    }

    private List<ShiftAssignment> assignEmployeesForShift(LocalDate day,
                                                          String shiftName,
                                                          LocalTime start,
                                                          LocalTime end,
                                                          int requiredEmployees,
                                                          Deque<Employee> rotation,
                                                          Map<LocalDate, Set<Long>> dailyAssignments) {
        List<ShiftAssignment> assignments = new ArrayList<>();
        Set<Long> assignedToday = dailyAssignments.computeIfAbsent(day, d -> new HashSet<>());
        int attempts = 0;
        while (assignments.size() < requiredEmployees && attempts < rotation.size() * 2) {
            Employee candidate = rotation.pollFirst();
            if (candidate == null) {
                break;
            }
            rotation.offerLast(candidate);
            attempts++;

            if (assignedToday.contains(candidate.getId())) {
                continue;
            }

            assignments.add(new ShiftAssignment(day, shiftName, start, end, candidate));
            assignedToday.add(candidate.getId());
        }

        if (assignments.size() < requiredEmployees) {
            throw new IllegalStateException("Not enough employees to fill " + shiftName + " on " + day);
        }

        return assignments;
    }
}
