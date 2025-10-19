package com.example.shiftv1.schedule;

import com.example.shiftv1.config.ShiftConfig;
import com.example.shiftv1.config.ShiftConfigRepository;
import com.example.shiftv1.constraint.EmployeeConstraint;
import com.example.shiftv1.constraint.EmployeeConstraintRepository;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.holiday.HolidayRepository;
import com.example.shiftv1.demand.DemandInterval;
import com.example.shiftv1.demand.DemandIntervalRepository;
import com.example.shiftv1.employee.EmployeeAvailability;
import com.example.shiftv1.employee.EmployeeAvailabilityRepository;
import com.example.shiftv1.employee.EmployeeRule;
import com.example.shiftv1.employee.EmployeeRuleRepository;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.skill.SkillRepository;
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
import java.util.Objects;
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
    private final HolidayRepository holidayRepository;
    private final EmployeeRuleRepository employeeRuleRepository;
    private final EmployeeAvailabilityRepository availabilityRepository;
    private final DemandIntervalRepository demandRepository;
    private final SkillRepository skillRepository;

    public ScheduleService(EmployeeRepository employeeRepository,
                           ShiftAssignmentRepository assignmentRepository,
                           ShiftConfigRepository shiftConfigRepository,
                           EmployeeConstraintRepository constraintRepository,
                           HolidayRepository holidayRepository,
                           EmployeeRuleRepository employeeRuleRepository,
                           EmployeeAvailabilityRepository availabilityRepository,
                           DemandIntervalRepository demandRepository,
                           SkillRepository skillRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftConfigRepository = shiftConfigRepository;
        this.constraintRepository = constraintRepository;
        this.holidayRepository = holidayRepository;
        this.employeeRuleRepository = employeeRuleRepository;
        this.availabilityRepository = availabilityRepository;
        this.demandRepository = demandRepository;
        this.skillRepository = skillRepository;
    }

    @Transactional
    @CacheEvict(value = "monthly-schedules", key = "#year + '-' + #month")
    public List<ShiftAssignment> generateMonthlyFromDemand(int year, int month, int granularityMinutes, boolean resetMonth) {
        YearMonth target = YearMonth.of(year, month);
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();

        List<Employee> employees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(Employee::getId)).toList();
        if (employees.isEmpty()) {
            throw new IllegalStateException("従業員が登録されていません。先に従業員を作成してください。");
        }

        if (resetMonth) {
            assignmentRepository.deleteByWorkDateBetween(start, end);
        }

        // preload rules and availability similar to monthly generation
        ruleByEmployee = employeeRuleRepository.findAll().stream()
                .collect(Collectors.toMap(r -> r.getEmployee().getId(), r -> r));
        availabilityByEmployee = availabilityRepository.findAll().stream()
                .collect(Collectors.groupingBy(a -> a.getEmployee().getId()));
        dailyAssigned.clear();
        dailyAssignedHours.clear();

        Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate = constraintRepository
                .findByDateBetweenAndActiveTrue(start, end)
                .stream()
                .collect(Collectors.groupingBy(EmployeeConstraint::getDate,
                        Collectors.groupingBy(c -> c.getEmployee().getId())));

        Map<Long, Integer> monthlyAssignmentCounts = new HashMap<>();
        List<ShiftAssignment> results = new ArrayList<>();

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            List<DemandInterval> intervals = demandRepository.findEffectiveForDate(day, day.getDayOfWeek());
            // filter active
            intervals = intervals.stream()
                    .filter(di -> di.getActive() == null || di.getActive())
                    .toList();

            for (java.time.LocalTime t = java.time.LocalTime.of(0,0);
                 t.isBefore(java.time.LocalTime.of(23,59));
                 t = t.plusMinutes(granularityMinutes)) {
                java.time.LocalTime tEnd = t.plusMinutes(granularityMinutes);
                final java.time.LocalTime slotStart = t;
                final java.time.LocalTime slotEnd = tEnd;

                // per-skill requirements (date-specific override weekly)
                Map<Long, Integer> perSkillReq = intervals.stream()
                        .filter(di -> di.getSkill() != null)
                        .filter(di -> di.getStartTime().isBefore(slotEnd) && di.getEndTime().isAfter(slotStart))
                        .collect(Collectors.groupingBy(di -> di.getSkill().getId(), Collectors.collectingAndThen(Collectors.toList(), list -> {
                            boolean hasDate = list.stream().anyMatch(x -> x.getDate() != null);
                            return (hasDate ? list.stream().filter(x -> x.getDate() != null) : list.stream())
                                    .mapToInt(DemandInterval::getRequiredSeats).sum();
                        })));

                // global requirements
                List<DemandInterval> globals = intervals.stream()
                        .filter(di -> di.getSkill() == null)
                        .filter(di -> di.getStartTime().isBefore(slotEnd) && di.getEndTime().isAfter(slotStart))
                        .toList();
                boolean hasDateGlobal = globals.stream().anyMatch(x -> x.getDate() != null);
                int globalReq = (hasDateGlobal ? globals.stream().filter(x -> x.getDate() != null) : globals.stream())
                        .mapToInt(DemandInterval::getRequiredSeats).sum();

                // Assignments per slot: assign skill seats first
                // track today's assigned for allowMultipleShiftsPerDay rule
                Set<Long> assignedToday = dailyAssigned.computeIfAbsent(day, d -> new HashSet<>());
                Map<Long, List<EmployeeConstraint>> consForDay = constraintsByDate.getOrDefault(day, Collections.emptyMap());

                // skills first
                if (!perSkillReq.isEmpty()) {
                    for (Map.Entry<Long, Integer> e : perSkillReq.entrySet()) {
                        if (e.getValue() == null || e.getValue() <= 0) continue;
                        com.example.shiftv1.skill.Skill skill = skillRepository.findById(e.getKey()).orElse(null);
                        if (skill == null) continue;
                        results.addAll(assignSeatsForSlot(day, t, tEnd, skill, e.getValue(), employees,
                                monthlyAssignmentCounts, dailyAssigned, consForDay));
                    }
                }
                // then global
                if (globalReq > 0) {
                    results.addAll(assignSeatsForSlot(day, t, tEnd, null, globalReq, employees,
                            monthlyAssignmentCounts, dailyAssigned, consForDay));
                }
            }
        }

        return sortAssignments(assignmentRepository.saveAll(results));
    }

    private List<ShiftAssignment> assignSeatsForSlot(LocalDate day,
                                                     java.time.LocalTime start,
                                                     java.time.LocalTime end,
                                                     com.example.shiftv1.skill.Skill skill,
                                                     int seats,
                                                     List<Employee> employees,
                                                     Map<Long, Integer> monthlyAssignmentCounts,
                                                     Map<LocalDate, Set<Long>> dailyAssigned,
                                                     Map<Long, List<EmployeeConstraint>> consForDay) {
        List<ShiftAssignment> created = new ArrayList<>();
        Set<Long> assignedToday = dailyAssigned.computeIfAbsent(day, d -> new HashSet<>());
        Set<Long> preferred = getPreferredEmployeesForShift(day, new ShiftConfig("slot", start, end, seats),
                Map.of(day, consForDay));
        List<Employee> sorted = new ArrayList<>(employees);
        sorted.sort(Comparator
                .comparing((Employee e) -> !preferred.contains(e.getId()))
                .thenComparing(e -> monthlyAssignmentCounts.getOrDefault(e.getId(), 0))
                .thenComparing(Employee::getId));
        while (created.size() < seats) {
            Employee chosen = null;
            for (Employee cand : sorted) {
                Long cid = cand.getId();
                EmployeeRule rule = ruleByEmployee.get(cid);
                boolean allowMulti = rule != null && Boolean.TRUE.equals(rule.getAllowMultipleShiftsPerDay());
                if (!allowMulti && assignedToday.contains(cid)) continue;
                if (skill != null) {
                    boolean hasSkill = cand.getSkills() != null && cand.getSkills().stream().anyMatch(s -> Objects.equals(s.getId(), skill.getId()));
                    if (!hasSkill) continue;
                }
                ShiftConfig tmpCfg = new ShiftConfig("slot", start, end, seats);
                if (skill != null) tmpCfg.setRequiredSkill(skill);
                if (!isEmployeeAvailable(cand, day, tmpCfg, Map.of(day, consForDay))) continue;
                chosen = cand; break;
            }
            if (chosen == null) break;
            String shiftName = (skill != null && skill.getCode() != null)
                    ? ("H%02d-" + skill.getCode()).formatted(start.getHour())
                    : "H%02d".formatted(start.getHour());
            created.add(new ShiftAssignment(day, shiftName, start, end, chosen));
            assignedToday.add(chosen.getId());
            preferred.remove(chosen.getId());
            monthlyAssignmentCounts.merge(chosen.getId(), 1, Integer::sum);
            int addHours = Math.max(1, (int) java.time.Duration.between(start, end).toHours());
            dailyAssignedHours.computeIfAbsent(day, d -> new HashMap<>())
                    .merge(chosen.getId(), addHours, Integer::sum);
        }
        return created;
    }

    // Rules snapshot for generation
    private Map<Long, EmployeeRule> ruleByEmployee = Map.of();
    private Map<Long, List<EmployeeAvailability>> availabilityByEmployee = Map.of();
    private Map<LocalDate, Set<Long>> dailyAssigned = new HashMap<>();
    private Map<LocalDate, Map<Long, Integer>> dailyAssignedHours = new HashMap<>();

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

        // 「対象（平日/週末）」の概念は廃止。以降は曜日/祝日の指定のみを使用する。

        Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate = constraintRepository
                .findByDateBetweenAndActiveTrue(start, end)
                .stream()
                .collect(Collectors.groupingBy(EmployeeConstraint::getDate,
                        Collectors.groupingBy(constraint -> constraint.getEmployee().getId())));

        Map<Long, Integer> monthlyAssignmentCounts = new HashMap<>();
        preloadAssignmentCounts(monthlyAssignmentCounts, recentAssignments);
        Map<LocalDate, Set<Long>> dailyAssignments = new HashMap<>();
        List<ShiftAssignment> results = new ArrayList<>();

        // 祝日・曜日の指定に基づいて日別に適用設定を選択
        Map<LocalDate, Boolean> holidayMap = new HashMap<>();
        try {
            List<LocalDate> holidays = holidayRepository.findDatesBetween(start, end);
            for (LocalDate d0 = start; !d0.isAfter(end); d0 = d0.plusDays(1)) { holidayMap.put(d0, false); }
            for (LocalDate hd : holidays) holidayMap.put(hd, true);
        } catch (Exception ignored) {}

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            List<ShiftConfig> configsForDay = selectConfigsForDay(activeShiftConfigs, day, holidayMap.getOrDefault(day, false));
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

    @Transactional
    public List<ShiftAssignment> generateHourlyForDay(LocalDate day, int startHour, int endHour, Long requiredSkillId) {
        List<Employee> employees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(Employee::getId)).toList();
        // Clear the day to avoid duplicates
        assignmentRepository.deleteByWorkDate(day);

        Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate = constraintRepository
                .findByDateBetweenAndActiveTrue(day, day)
                .stream()
                .collect(Collectors.groupingBy(EmployeeConstraint::getDate,
                        Collectors.groupingBy(constraint -> constraint.getEmployee().getId())));

        Map<Long, Integer> monthlyAssignmentCounts = new HashMap<>();
        Map<LocalDate, Set<Long>> dailyAssignments = new HashMap<>();
        List<ShiftAssignment> results = new ArrayList<>();

        for (int h = startHour; h < endHour; h++) {
            java.time.LocalTime s = java.time.LocalTime.of(h, 0);
            java.time.LocalTime e = java.time.LocalTime.of(Math.min(h+1, 23), (h+1>23?59:0));
            java.util.List<DemandInterval> allIntervals = demandRepository.findEffectiveForDate(day, day.getDayOfWeek());
            java.util.List<DemandInterval> globalIntervals = allIntervals.stream()
                    .filter(di -> di.getActive() == null || di.getActive())
                    .filter(di -> di.getSkill() == null)
                    .filter(di -> di.getStartTime().isBefore(e) && di.getEndTime().isAfter(s))
                    .toList();
            boolean hasDateSpecificGlobal = globalIntervals.stream().anyMatch(di -> di.getDate() != null);
            int global = (hasDateSpecificGlobal ? globalIntervals.stream().filter(di -> di.getDate() != null) : globalIntervals.stream())
                    .mapToInt(DemandInterval::getRequiredSeats).sum();
            int skillReq = 0;
            com.example.shiftv1.skill.Skill reqSkill = null;
            if (requiredSkillId != null) {
                reqSkill = skillRepository.findById(requiredSkillId).orElse(null);
                java.util.List<DemandInterval> skillIntervals = allIntervals.stream()
                        .filter(di -> di.getActive() == null || di.getActive())
                        .filter(di -> di.getSkill() != null && requiredSkillId.equals(di.getSkill().getId()))
                        .filter(di -> di.getStartTime().isBefore(e) && di.getEndTime().isAfter(s))
                        .toList();
                boolean hasDateSpecificSkill = skillIntervals.stream().anyMatch(di -> di.getDate() != null);
                skillReq = (hasDateSpecificSkill ? skillIntervals.stream().filter(di -> di.getDate() != null) : skillIntervals.stream())
                        .mapToInt(DemandInterval::getRequiredSeats).sum();
            }
            int required = (requiredSkillId != null)
                    ? (skillReq > 0 ? skillReq : global)
                    : global;
            if (required <= 0) continue;
            // Ephemeral config
            ShiftConfig cfg = new ShiftConfig("H%02d".formatted(h), s, e, required);
            if (reqSkill != null) cfg.setRequiredSkill(reqSkill);

            // assignment loop (permit multiple hourly assignments per day)
            List<ShiftAssignment> hourAssignments = new ArrayList<>();
            Set<Long> assignedToday = dailyAssignments.computeIfAbsent(day, d -> new HashSet<>()); // only used for map presence
            Set<Long> preferredEmployees = getPreferredEmployeesForShift(day, cfg, constraintsByDate);

            while (hourAssignments.size() < required) {
                // copy of selectNextCandidate but ignore per-day multiple restriction
                List<Employee> sortedCandidates = new ArrayList<>(employees);
                sortedCandidates.sort(Comparator
                        .comparing((Employee e0) -> !preferredEmployees.contains(e0.getId()))
                        .thenComparing(e0 -> monthlyAssignmentCounts.getOrDefault(e0.getId(), 0))
                        .thenComparing(Employee::getId));
                Employee chosen = null;
                for (Employee cand : sortedCandidates) {
                    // required skill check
                    if (cfg.getRequiredSkill() != null) {
                        boolean hasSkill = cand.getSkills() != null && cand.getSkills().stream().anyMatch(s1 -> s1.getId().equals(cfg.getRequiredSkill().getId()));
                        if (!hasSkill) continue;
                    }
                    if (!isEmployeeAvailable(cand, day, cfg, constraintsByDate)) continue;
                    chosen = cand; break;
                }
                if (chosen == null) break;
                hourAssignments.add(new ShiftAssignment(day, cfg.getName(), cfg.getStartTime(), cfg.getEndTime(), chosen));
                preferredEmployees.remove(chosen.getId());
                monthlyAssignmentCounts.merge(chosen.getId(), 1, Integer::sum);
            }
            results.addAll(hourAssignments);
        }

        return sortAssignments(assignmentRepository.saveAll(results));
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

    // ===== Diagnostics DTOs =====
    public static class ShiftDiagnostics {
        public final String shiftName;
        public final LocalTime startTime;
        public final LocalTime endTime;
        public final int required;
        public final int assigned;
        public final List<String> assignedEmployees;
