package com.example.shiftv1.schedule;

import com.example.shiftv1.config.ShiftConfig;
import com.example.shiftv1.config.ShiftConfigRepository;
import com.example.shiftv1.constraint.EmployeeConstraint;
import com.example.shiftv1.constraint.EmployeeConstraintRepository;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ScheduleService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);

    private final EmployeeRepository employeeRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final ShiftConfigRepository shiftConfigRepository;
    private final EmployeeConstraintRepository constraintRepository;

    public ScheduleService(EmployeeRepository employeeRepository,
                           ShiftAssignmentRepository assignmentRepository,
                           ShiftConfigRepository shiftConfigRepository,
                           EmployeeConstraintRepository constraintRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftConfigRepository = shiftConfigRepository;
        this.constraintRepository = constraintRepository;
    }

    @Transactional
    @CacheEvict(value = "monthly-schedules", key = "#year + '-' + #month")
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

        List<Employee> baseEmployees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(Employee::getId))
                .toList();
        if (baseEmployees.isEmpty()) {
            logger.error("従業員が登録されていません");
            throw new IllegalStateException("従業員が登録されていません。シフト生成前に従業員を追加してください。");
        }

        Optional<ShiftAssignment> lastAssignmentBeforePeriod =
                assignmentRepository.findTopByWorkDateBeforeOrderByWorkDateDesc(start);
        List<Employee> employees = rotateEmployees(baseEmployees,
                determineRotationOffset(lastAssignmentBeforePeriod, baseEmployees));

        List<ShiftAssignment> recentAssignments = loadRecentAssignments(start);

        logger.info("登録従業員数: {}名", employees.size());
        // 安全のため、月次一括削除は行わない
        // assignmentRepository.deleteByWorkDateBetween(start, end);
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            assignmentRepository.deleteByWorkDate(d);
        }
        logger.info("既存のシフト割り当てを削除しました: {} から {}", start, end);

        List<ShiftConfig> activeShiftConfigs = shiftConfigRepository.findByActiveTrue();
        if (activeShiftConfigs.isEmpty()) {
            logger.error("アクティブなシフト設定が存在しません");
            throw new IllegalStateException("アクティブなシフト設定が存在しません。シフト設定を登録してください。");
        }

        List<ShiftConfig> weekdayShiftConfigs = activeShiftConfigs.stream()
                .filter(config -> !Boolean.TRUE.equals(config.getWeekend()))
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();

        List<ShiftConfig> weekendShiftConfigs = activeShiftConfigs.stream()
                .filter(config -> Boolean.TRUE.equals(config.getWeekend()))
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();

        if (weekdayShiftConfigs.isEmpty()) {
            logger.warn("平日用のシフト設定が見つかりません。全アクティブ設定を使用します");
            weekdayShiftConfigs = activeShiftConfigs;
        }
        if (weekendShiftConfigs.isEmpty()) {
            logger.warn("週末用のシフト設定が見つかりません。全アクティブ設定を使用します");
            weekendShiftConfigs = activeShiftConfigs;
        }

        Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate = constraintRepository
                .findByDateBetweenAndActiveTrue(start, end)
                .stream()
                .collect(Collectors.groupingBy(EmployeeConstraint::getDate,
                        Collectors.groupingBy(constraint -> constraint.getEmployee().getId())));

        Map<Long, Integer> monthlyAssignmentCounts = new HashMap<>();
        preloadAssignmentCounts(monthlyAssignmentCounts, recentAssignments);
        Map<LocalDate, Set<Long>> dailyAssignments = new HashMap<>();
        List<ShiftAssignment> results = new ArrayList<>();

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            boolean isWeekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            List<ShiftConfig> configsForDay = isWeekend ? weekendShiftConfigs : weekdayShiftConfigs;
            for (ShiftConfig config : configsForDay) {
                results.addAll(assignEmployeesForShift(day, config, employees, monthlyAssignmentCounts,
                        dailyAssignments, constraintsByDate));
            }
        }

        results.sort(getAssignmentComparator());

        List<ShiftAssignment> savedAssignments = assignmentRepository.saveAll(results);
        logger.info("シフト生成が完了しました: {}件の割り当てを生成", savedAssignments.size());
        return sortAssignments(savedAssignments);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "monthly-schedules", key = "#year + '-' + #month")
    public List<ShiftAssignment> getMonthlySchedule(int year, int month) {
        YearMonth target = YearMonth.of(year, month);
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();
        return sortAssignments(assignmentRepository.findByWorkDateBetween(start, end));
    }

    // ===== Generation report (shortage details) support =====
    public static class ShortageInfo {
        public final LocalDate workDate;
        public final String shiftName;
        public final int required;
        public final int assigned;

        public ShortageInfo(LocalDate workDate, String shiftName, int required, int assigned) {
            this.workDate = workDate;
            this.shiftName = shiftName;
            this.required = required;
            this.assigned = assigned;
        }
    }

    public static class GenerationReport {
        public final List<ShiftAssignment> assignments;
        public final List<ShortageInfo> shortages;

        public GenerationReport(List<ShiftAssignment> assignments, List<ShortageInfo> shortages) {
            this.assignments = assignments;
            this.shortages = shortages;
        }
    }

    @Transactional
    @CacheEvict(value = "monthly-schedules", key = "#year + '-' + #month")
    public GenerationReport generateMonthlyScheduleWithReport(int year, int month) {
        YearMonth target;
        LocalDate start;
        LocalDate end;

        try {
            target = YearMonth.of(year, month);
            start = target.atDay(1);
            end = target.atEndOfMonth();
            logger.info("シフト生成を開始します: {}年{}月", year, month);
        } catch (Exception e) {
            logger.error("不正な年月です: {}年{}月", year, month, e);
            throw new IllegalArgumentException("不正な年月です: " + year + "年" + month + "月");
        }

        List<Employee> baseEmployees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(Employee::getId))
                .toList();
        if (baseEmployees.isEmpty()) {
            logger.error("従業員が登録されていません");
            throw new IllegalStateException("従業員が登録されていません。シフト生成の前に従業員を追加してください。");
        }

        Optional<ShiftAssignment> lastAssignmentBeforePeriod =
                assignmentRepository.findTopByWorkDateBeforeOrderByWorkDateDesc(start);
        List<Employee> employees = rotateEmployees(baseEmployees,
                determineRotationOffset(lastAssignmentBeforePeriod, baseEmployees));

        List<ShiftAssignment> recentAssignments = loadRecentAssignments(start);

        logger.info("登録従業員数: {}名", employees.size());
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            assignmentRepository.deleteByWorkDate(d);
        }
        logger.info("対象月の日別シフトを削除しました: {} から {}", start, end);

        List<ShiftConfig> activeShiftConfigs = shiftConfigRepository.findByActiveTrue();
        if (activeShiftConfigs.isEmpty()) {
            logger.error("アクティブなシフト設定が存在しません");
            throw new IllegalStateException("アクティブなシフト設定が存在しません。シフト設定を登録してください。");
        }

        List<ShiftConfig> weekdayShiftConfigs = activeShiftConfigs.stream()
                .filter(config -> !Boolean.TRUE.equals(config.getWeekend()))
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();

        List<ShiftConfig> weekendShiftConfigs = activeShiftConfigs.stream()
                .filter(config -> Boolean.TRUE.equals(config.getWeekend()))
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();

        if (weekdayShiftConfigs.isEmpty()) {
            logger.warn("平日用のシフト設定がありません。全アクティブ設定を使用します");
            weekdayShiftConfigs = activeShiftConfigs;
        }
        if (weekendShiftConfigs.isEmpty()) {
            logger.warn("週末用のシフト設定がありません。全アクティブ設定を使用します");
            weekendShiftConfigs = activeShiftConfigs;
        }

        Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate = constraintRepository
                .findByDateBetweenAndActiveTrue(start, end)
                .stream()
                .collect(Collectors.groupingBy(EmployeeConstraint::getDate,
                        Collectors.groupingBy(constraint -> constraint.getEmployee().getId())));

        Map<Long, Integer> monthlyAssignmentCounts = new HashMap<>();
        preloadAssignmentCounts(monthlyAssignmentCounts, recentAssignments);
        Map<LocalDate, Set<Long>> dailyAssignments = new HashMap<>();
        List<ShiftAssignment> results = new ArrayList<>();
        List<ShortageInfo> shortages = new ArrayList<>();

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            boolean isWeekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            List<ShiftConfig> configsForDay = isWeekend ? weekendShiftConfigs : weekdayShiftConfigs;
            for (ShiftConfig config : configsForDay) {
                results.addAll(assignEmployeesForShiftWithReport(day, config, employees, monthlyAssignmentCounts,
                        dailyAssignments, constraintsByDate, shortages));
            }
        }

        results.sort(getAssignmentComparator());
        List<ShiftAssignment> savedAssignments = assignmentRepository.saveAll(results);
        logger.info("シフト生成が完了しました: {}件の割当を作成", savedAssignments.size());
        return new GenerationReport(sortAssignments(savedAssignments), shortages);
    }

    private List<ShiftAssignment> assignEmployeesForShiftWithReport(LocalDate day,
                                                                    ShiftConfig shiftConfig,
                                                                    List<Employee> employees,
                                                                    Map<Long, Integer> monthlyAssignmentCounts,
                                                                    Map<LocalDate, Set<Long>> dailyAssignments,
                                                                    Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate,
                                                                    List<ShortageInfo> shortages) {
        List<ShiftAssignment> assignments = new ArrayList<>();
        Set<Long> assignedToday = dailyAssignments.computeIfAbsent(day, d -> new HashSet<>());
        Set<Long> preferredEmployees = getPreferredEmployeesForShift(day, shiftConfig, constraintsByDate);

        while (assignments.size() < shiftConfig.getRequiredEmployees()) {
            Employee candidate = selectNextCandidate(employees, assignedToday, preferredEmployees,
                    monthlyAssignmentCounts, day, shiftConfig, constraintsByDate);
            if (candidate == null) {
                break;
            }

            assignments.add(new ShiftAssignment(day,
                    shiftConfig.getName(),
                    shiftConfig.getStartTime(),
                    shiftConfig.getEndTime(),
                    candidate));
            assignedToday.add(candidate.getId());
            preferredEmployees.remove(candidate.getId());
            monthlyAssignmentCounts.merge(candidate.getId(), 1, Integer::sum);
        }

        if (assignments.size() < shiftConfig.getRequiredEmployees()) {
            logger.warn("割当不足: {} の {} は必要:{} 実際:{}",
                    day, shiftConfig.getName(), shiftConfig.getRequiredEmployees(), assignments.size());
            shortages.add(new ShortageInfo(day, shiftConfig.getName(),
                    shiftConfig.getRequiredEmployees(), assignments.size()));
        }

        return assignments;
    }

    @Transactional
    @CacheEvict(value = "monthly-schedules", key = "#year + '-' + #month")
    public void resetMonthlySchedule(int year, int month) {
        YearMonth target = YearMonth.of(year, month);
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            assignmentRepository.deleteByWorkDate(d);
        }
        logger.info("対象月のシフトを初期化: {}-{}", year, month);
    }

    private List<ShiftAssignment> sortAssignments(List<ShiftAssignment> assignments) {
        return assignments.stream()
                .sorted(getAssignmentComparator())
                .toList();
    }

    private Comparator<ShiftAssignment> getAssignmentComparator() {
        return Comparator
                .comparing(ShiftAssignment::getWorkDate)
                .thenComparing(ShiftAssignment::getStartTime)
                .thenComparing(ShiftAssignment::getShiftName)
                .thenComparing(assignment -> assignment.getEmployee().getId());
    }

    private List<ShiftAssignment> assignEmployeesForShift(LocalDate day,
                                                          ShiftConfig shiftConfig,
                                                          List<Employee> employees,
                                                          Map<Long, Integer> monthlyAssignmentCounts,
                                                          Map<LocalDate, Set<Long>> dailyAssignments,
                                                          Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate) {
        List<ShiftAssignment> assignments = new ArrayList<>();
        Set<Long> assignedToday = dailyAssignments.computeIfAbsent(day, d -> new HashSet<>());
        Set<Long> preferredEmployees = getPreferredEmployeesForShift(day, shiftConfig, constraintsByDate);

        while (assignments.size() < shiftConfig.getRequiredEmployees()) {
            Employee candidate = selectNextCandidate(employees, assignedToday, preferredEmployees,
                    monthlyAssignmentCounts, day, shiftConfig, constraintsByDate);
            if (candidate == null) {
                break;
            }

            assignments.add(new ShiftAssignment(day,
                    shiftConfig.getName(),
                    shiftConfig.getStartTime(),
                    shiftConfig.getEndTime(),
                    candidate));
            assignedToday.add(candidate.getId());
            preferredEmployees.remove(candidate.getId());
            monthlyAssignmentCounts.merge(candidate.getId(), 1, Integer::sum);
        }

        if (assignments.size() < shiftConfig.getRequiredEmployees()) {
            // 不足があっても例外にせず、警告ログのみ出して続行
            logger.warn("割当不足: {} の {} は必要:{} 実際:{}",
                    day, shiftConfig.getName(), shiftConfig.getRequiredEmployees(), assignments.size());
        }

        return assignments;
    }

    private Employee selectNextCandidate(List<Employee> employees,
                                         Set<Long> assignedToday,
                                         Set<Long> preferredEmployees,
                                         Map<Long, Integer> monthlyAssignmentCounts,
                                         LocalDate day,
                                         ShiftConfig shiftConfig,
                                         Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate) {

        List<Employee> sortedCandidates = new ArrayList<>(employees);
        sortedCandidates.sort(Comparator
                .comparing((Employee e) -> !preferredEmployees.contains(e.getId()))
                .thenComparing(e -> monthlyAssignmentCounts.getOrDefault(e.getId(), 0))
                .thenComparing(Employee::getId));

        for (Employee candidate : sortedCandidates) {
            Long candidateId = candidate.getId();
            if (assignedToday.contains(candidateId)) {
                continue;
            }
            if (!isEmployeeAvailable(candidate, day, shiftConfig, constraintsByDate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private boolean isEmployeeAvailable(Employee employee,
                                        LocalDate day,
                                        ShiftConfig shiftConfig,
                                        Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate) {
        Map<Long, List<EmployeeConstraint>> constraintsForDay = constraintsByDate.getOrDefault(day, Collections.emptyMap());
        List<EmployeeConstraint> employeeConstraints = constraintsForDay.get(employee.getId());
        if (employeeConstraints == null || employeeConstraints.isEmpty()) {
            return true;
        }

        for (EmployeeConstraint constraint : employeeConstraints) {
            if (constraint.getType() == null) {
                continue;
            }
            switch (constraint.getType()) {
                case UNAVAILABLE, VACATION, SICK_LEAVE, PERSONAL -> {
                    logger.debug("従業員{}は{}に{}のため勤務不可", employee.getName(), day, constraint.getType());
                    return false;
                }
                case LIMITED -> {
                    LocalTime availableStart = constraint.getStartTime() != null ? constraint.getStartTime() : LocalTime.MIN;
                    LocalTime availableEnd = constraint.getEndTime() != null ? constraint.getEndTime() : LocalTime.MAX;
                    if (shiftConfig.getStartTime().isBefore(availableStart) || shiftConfig.getEndTime().isAfter(availableEnd)) {
                        logger.debug("従業員{}は{}のシフト{}に時間制限のため割り当て不可", employee.getName(), day, shiftConfig.getName());
                        return false;
                    }
                }
                case PREFERRED -> {
                    // 希望は優先度のみに影響するため可否には影響させない
                }
            }
        }
        return true;
    }

    private Set<Long> getPreferredEmployeesForShift(LocalDate day,
                                                     ShiftConfig shiftConfig,
                                                     Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate) {
        Map<Long, List<EmployeeConstraint>> constraintsForDay = constraintsByDate.get(day);
        if (constraintsForDay == null || constraintsForDay.isEmpty()) {
            return new HashSet<>();
        }

        Set<Long> preferred = new HashSet<>();
        for (Map.Entry<Long, List<EmployeeConstraint>> entry : constraintsForDay.entrySet()) {
            Long employeeId = entry.getKey();
            for (EmployeeConstraint constraint : entry.getValue()) {
                if (constraint.getType() == EmployeeConstraint.ConstraintType.PREFERRED &&
                        matchesPreferredTime(constraint, shiftConfig.getStartTime(), shiftConfig.getEndTime())) {
                    preferred.add(employeeId);
                    break;
                }
            }
        }
        return preferred;
    }

    private boolean matchesPreferredTime(EmployeeConstraint constraint, LocalTime shiftStart, LocalTime shiftEnd) {
        LocalTime preferredStart = constraint.getStartTime() != null ? constraint.getStartTime() : LocalTime.MIN;
        LocalTime preferredEnd = constraint.getEndTime() != null ? constraint.getEndTime() : LocalTime.MAX;
        return !shiftEnd.isBefore(preferredStart) && !shiftStart.isAfter(preferredEnd);
    }

    private List<Employee> rotateEmployees(List<Employee> employees, int offset) {
        if (employees.isEmpty()) {
            return employees;
        }

        int normalizedOffset = offset % employees.size();
        if (normalizedOffset == 0) {
            return employees;
        }

        List<Employee> rotated = new ArrayList<>(employees.size());
        for (int i = 0; i < employees.size(); i++) {
            rotated.add(employees.get((i + normalizedOffset) % employees.size()));
        }
        logger.debug("従業員リストを{}ポジション回転しました", normalizedOffset);
        return rotated;
    }

    private int determineRotationOffset(Optional<ShiftAssignment> lastAssignmentBeforePeriod, List<Employee> employees) {
        if (lastAssignmentBeforePeriod.isEmpty() || employees.isEmpty()) {
            return 0;
        }

        Employee lastEmployee = lastAssignmentBeforePeriod.get().getEmployee();
        if (lastEmployee == null || lastEmployee.getId() == null) {
            return 0;
        }

        for (int i = 0; i < employees.size(); i++) {
            if (lastEmployee.getId().equals(employees.get(i).getId())) {
                int offset = (i + 1) % employees.size();
                logger.debug("前回の割り当て従業員{}に基づきオフセット{}を使用", lastEmployee.getId(), offset);
                return offset;
            }
        }
        return 0;
    }

    private List<ShiftAssignment> loadRecentAssignments(LocalDate startDate) {
        try {
            LocalDate historyEnd = startDate.minusDays(1);
            YearMonth previousMonth = YearMonth.from(startDate).minusMonths(1);
            LocalDate historyStart = previousMonth.atDay(1);

            if (historyEnd.isBefore(historyStart)) {
                return List.of();
            }

            List<ShiftAssignment> assignments = assignmentRepository.findByWorkDateBetween(historyStart, historyEnd);
            logger.debug("過去期間{}から{}の{}件の割り当てを読み込み", historyStart, historyEnd, assignments.size());
            return assignments;
        } catch (DateTimeException e) {
            logger.debug("過去割り当ての取得に失敗しました: {}", e.getMessage());
            return List.of();
        }
    }

    private void preloadAssignmentCounts(Map<Long, Integer> monthlyAssignmentCounts, List<ShiftAssignment> recentAssignments) {
        for (ShiftAssignment assignment : recentAssignments) {
            Employee employee = assignment.getEmployee();
            if (employee != null && employee.getId() != null) {
                monthlyAssignmentCounts.merge(employee.getId(), 1, Integer::sum);
            }
        }
        if (!monthlyAssignmentCounts.isEmpty()) {
            logger.debug("過去の割り当てを{}件分プリロードしました", monthlyAssignmentCounts.size());
        }
    }
}
