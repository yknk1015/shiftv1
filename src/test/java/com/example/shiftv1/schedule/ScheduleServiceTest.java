package com.example.shiftv1.schedule;

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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        // デフォルト設定を使用して期待値を計算
        ShiftConfiguration config = ShiftConfiguration.DEFAULT;
        int expected = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                expected += config.getWeekendEmployeesPerShift();
            } else {
                expected += config.getWeekdayEmployeesPerShift() * 2;
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

    @Test
    void generateMonthlySchedule_respectsEmployeeConstraints() {
        // 土日勤務不可の従業員を作成
        Employee weekendUnavailableEmployee = new Employee("土日不可従業員", "スタッフ", 3, false, true, 5);
        employeeRepository.save(weekendUnavailableEmployee);

        int year = 2024;
        int month = 7;
        List<ShiftAssignment> assignments = scheduleService.generateMonthlySchedule(year, month);

        // 土日のシフトに土日勤務不可の従業員が割り当てられていないことを確認
        List<ShiftAssignment> weekendAssignments = assignments.stream()
                .filter(a -> a.getWorkDate().getDayOfWeek() == DayOfWeek.SATURDAY || 
                           a.getWorkDate().getDayOfWeek() == DayOfWeek.SUNDAY)
                .filter(a -> a.getEmployee().getName().equals("土日不可従業員"))
                .toList();

        assertThat(weekendAssignments).isEmpty();
    }

    @Test
    void generateMonthlySchedule_prioritizesHigherSkillLevel() {
        // 異なるスキルレベルの従業員を作成
        Employee lowSkillEmployee = new Employee("低スキル従業員", "スタッフ", 1, true, true, 5);
        Employee highSkillEmployee = new Employee("高スキル従業員", "マネージャー", 5, true, true, 5);
        
        employeeRepository.save(lowSkillEmployee);
        employeeRepository.save(highSkillEmployee);

        int year = 2024;
        int month = 7;
        List<ShiftAssignment> assignments = scheduleService.generateMonthlySchedule(year, month);

        // 高スキルの従業員がより多くのシフトに割り当てられていることを確認
        long highSkillAssignments = assignments.stream()
                .filter(a -> a.getEmployee().getName().equals("高スキル従業員"))
                .count();
        
        long lowSkillAssignments = assignments.stream()
                .filter(a -> a.getEmployee().getName().equals("低スキル従業員"))
                .count();

        assertThat(highSkillAssignments).isGreaterThanOrEqualTo(lowSkillAssignments);
    }

    @Test
    void updateShiftConfiguration_updatesConfigurationSuccessfully() {
        ShiftConfiguration newConfig = ShiftConfiguration.builder()
                .weekdayAmStart(LocalTime.of(8, 0))
                .weekdayAmEnd(LocalTime.of(16, 0))
                .weekdayPmStart(LocalTime.of(16, 0))
                .weekdayPmEnd(LocalTime.of(24, 0))
                .weekdayEmployeesPerShift(3)
                .weekendStart(LocalTime.of(10, 0))
                .weekendEnd(LocalTime.of(20, 0))
                .weekendEmployeesPerShift(4)
                .build();

        scheduleService.updateShiftConfiguration(newConfig);
        ShiftConfiguration currentConfig = scheduleService.getCurrentShiftConfiguration();

        assertThat(currentConfig.getWeekdayAmStart()).isEqualTo(LocalTime.of(8, 0));
        assertThat(currentConfig.getWeekdayEmployeesPerShift()).isEqualTo(3);
        assertThat(currentConfig.getWeekendEmployeesPerShift()).isEqualTo(4);
    }

    @Test
    void generateMonthlySchedule_withInvalidYearMonth_throwsException() {
        assertThatThrownBy(() -> scheduleService.generateMonthlySchedule(2024, 13))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("無効な年月です");
    }

    @Test
    void generateMonthlySchedule_withNoEmployees_throwsException() {
        employeeRepository.deleteAll();
        
        assertThatThrownBy(() -> scheduleService.generateMonthlySchedule(2024, 7))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("従業員が登録されていません");
    }
}
