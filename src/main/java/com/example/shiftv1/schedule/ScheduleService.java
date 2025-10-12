package com.example.shiftv1.schedule;

import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ScheduleService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);

    private ShiftConfiguration shiftConfiguration;

    private final EmployeeRepository employeeRepository;
    private final ShiftAssignmentRepository assignmentRepository;

    public ScheduleService(EmployeeRepository employeeRepository, ShiftAssignmentRepository assignmentRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftConfiguration = ShiftConfiguration.DEFAULT;
    }

    @Transactional
    public List<ShiftAssignment> generateMonthlySchedule(int year, int month) {
        YearMonth target;
        LocalDate start;
        LocalDate end;
        
        try {
            target = YearMonth.of(year, month);
            start = target.atDay(1);
            end = target.atEndOfMonth();
            logger.info("シフト生成を開始します: {}年{}月", year, month);
        } catch (Exception e) {
            logger.error("無効な年月です: {}年{}月", year, month, e);
            throw new IllegalArgumentException("無効な年月です: " + year + "年" + month + "月");
        }

        List<Employee> employees = employeeRepository.findAll();
        if (employees.isEmpty()) {
            logger.error("従業員が登録されていません");
            throw new IllegalStateException("従業員が登録されていません。シフト生成前に従業員を追加してください。");
        }

        logger.info("登録従業員数: {}名", employees.size());
        assignmentRepository.deleteByWorkDateBetween(start, end);
        logger.info("既存のシフト割り当てを削除しました: {} から {}", start, end);

        Map<LocalDate, Set<Long>> dailyAssignments = new HashMap<>();
        Map<Long, Integer> monthlyAssignmentCounts = new HashMap<>();
        Map<String, Map<Long, Integer>> weeklyAssignmentCounts = new HashMap<>();
        List<ShiftAssignment> results = new ArrayList<>();

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            boolean isWeekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (isWeekend) {
                results.addAll(assignEmployeesForShift(day, "Weekend",
                        shiftConfiguration.getWeekendStart(), shiftConfiguration.getWeekendEnd(),
                        shiftConfiguration.getWeekendEmployeesPerShift(), employees, dailyAssignments,
                        monthlyAssignmentCounts, weeklyAssignmentCounts));
            } else {
                results.addAll(assignEmployeesForShift(day, "Weekday AM",
                        shiftConfiguration.getWeekdayAmStart(), shiftConfiguration.getWeekdayAmEnd(),
                        shiftConfiguration.getWeekdayEmployeesPerShift(), employees, dailyAssignments,
                        monthlyAssignmentCounts, weeklyAssignmentCounts));
                results.addAll(assignEmployeesForShift(day, "Weekday PM",
                        shiftConfiguration.getWeekdayPmStart(), shiftConfiguration.getWeekdayPmEnd(),
                        shiftConfiguration.getWeekdayEmployeesPerShift(), employees, dailyAssignments,
                        monthlyAssignmentCounts, weeklyAssignmentCounts));
            }
        }

        List<ShiftAssignment> savedAssignments = assignmentRepository.saveAll(results);
        logger.info("シフト生成が完了しました: {}件の割り当てを生成", savedAssignments.size());
        return savedAssignments;
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
                                                          List<Employee> candidates,
                                                          Map<LocalDate, Set<Long>> dailyAssignments,
                                                          Map<Long, Integer> monthlyAssignmentCounts,
                                                          Map<String, Map<Long, Integer>> weeklyAssignmentCounts) {
        List<ShiftAssignment> assignments = new ArrayList<>();
        Set<Long> assignedToday = dailyAssignments.computeIfAbsent(day, d -> new HashSet<>());

        // 従業員の適性を考慮したフィルタリング
        List<Employee> eligibleEmployees = candidates.stream()
            .filter(emp -> isEmployeeEligibleForShift(emp, day, shiftName, start, end))
            .filter(emp -> !assignedToday.contains(emp.getId()))
            .filter(emp -> !hasReachedWeeklyLimit(emp, day, weeklyAssignmentCounts))
            .sorted(buildCandidateComparator(day, monthlyAssignmentCounts, weeklyAssignmentCounts))
            .toList();

        for (Employee candidate : eligibleEmployees) {
            if (assignments.size() >= requiredEmployees) {
                break;
            }

            assignments.add(new ShiftAssignment(day, shiftName, start, end, candidate));
            assignedToday.add(candidate.getId());
            monthlyAssignmentCounts.merge(candidate.getId(), 1, Integer::sum);
            incrementWeeklyAssignmentCount(day, candidate, weeklyAssignmentCounts);

            logger.debug("従業員 {} をシフト {} に割り当て (スキルレベル: {})",
                candidate.getName(), shiftName, candidate.getSkillLevel());
        }

        if (assignments.size() < requiredEmployees) {
            logger.warn("十分な従業員が見つかりませんでした。必要: {}, 割り当て可能: {}", 
                requiredEmployees, assignments.size());
            throw new IllegalStateException(
                String.format("十分な従業員が見つかりませんでした。%s の %s シフトに %d 名必要ですが、%d 名しか割り当てできませんでした。", 
                day, shiftName, requiredEmployees, assignments.size()));
        }

        return assignments;
    }
    
    /**
     * 従業員が特定のシフトに適しているかを判定
     */
    private boolean isEmployeeEligibleForShift(Employee employee, LocalDate day, String shiftName,
                                             LocalTime start, LocalTime end) {
        // 土日勤務チェック
        boolean isWeekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
        if (isWeekend && !employee.getCanWorkWeekends()) {
            return false;
        }

        // 夜勤チェック（午後シフトの場合）
        if (shiftName.contains("PM") && !employee.getCanWorkEvenings()) {
            return false;
        }

        // 基本的なスキルレベルチェック
        if (employee.getSkillLevel() == null || employee.getSkillLevel() < 1) {
            return false;
        }

        return true;
    }

    private Comparator<Employee> buildCandidateComparator(LocalDate day,
                                                           Map<Long, Integer> monthlyAssignmentCounts,
                                                           Map<String, Map<Long, Integer>> weeklyAssignmentCounts) {
        return Comparator
            .comparing((Employee e) -> getWeeklyAssignmentCount(day, e, weeklyAssignmentCounts))
            .thenComparing(e -> monthlyAssignmentCounts.getOrDefault(e.getId(), 0))
            .thenComparing(e -> e.getSkillLevel() == null ? 0 : e.getSkillLevel(), Comparator.reverseOrder());
    }

    private boolean hasReachedWeeklyLimit(Employee employee, LocalDate day,
                                          Map<String, Map<Long, Integer>> weeklyAssignmentCounts) {
        Integer preferredWorkingDays = employee.getPreferredWorkingDays();
        if (preferredWorkingDays == null || preferredWorkingDays <= 0) {
            return false;
        }

        return getWeeklyAssignmentCount(day, employee, weeklyAssignmentCounts) >= preferredWorkingDays;
    }

    private int getWeeklyAssignmentCount(LocalDate day, Employee employee,
                                         Map<String, Map<Long, Integer>> weeklyAssignmentCounts) {
        String weekKey = weekKey(day);
        return weeklyAssignmentCounts
            .getOrDefault(weekKey, Collections.emptyMap())
            .getOrDefault(employee.getId(), 0);
    }

    private void incrementWeeklyAssignmentCount(LocalDate day, Employee employee,
                                                Map<String, Map<Long, Integer>> weeklyAssignmentCounts) {
        String weekKey = weekKey(day);
        Map<Long, Integer> weeklyCounts = weeklyAssignmentCounts
            .computeIfAbsent(weekKey, key -> new HashMap<>());
        weeklyCounts.merge(employee.getId(), 1, Integer::sum);
    }

    private String weekKey(LocalDate day) {
        int weekBasedYear = day.get(WeekFields.ISO.weekBasedYear());
        int weekOfYear = day.get(WeekFields.ISO.weekOfWeekBasedYear());
        return weekBasedYear + "-W" + weekOfYear;
    }
    
    /**
     * シフト設定を更新するメソッド（学習用）
     */
    public void updateShiftConfiguration(ShiftConfiguration newConfiguration) {
        this.shiftConfiguration = newConfiguration;
        logger.info("シフト設定を更新しました: 平日午前 {}-{}, 平日午後 {}-{}, 休日 {}-{}", 
            newConfiguration.getWeekdayAmStart(), newConfiguration.getWeekdayAmEnd(),
            newConfiguration.getWeekdayPmStart(), newConfiguration.getWeekdayPmEnd(),
            newConfiguration.getWeekendStart(), newConfiguration.getWeekendEnd());
    }
    
    /**
     * 現在のシフト設定を取得するメソッド（学習用）
     */
    public ShiftConfiguration getCurrentShiftConfiguration() {
        return shiftConfiguration;
    }
}
