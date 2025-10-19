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
import org.springframework.scheduling.annotation.Async;
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
    private final com.example.shiftv1.common.error.ErrorLogBuffer errorLogBuffer;

    public ScheduleService(EmployeeRepository employeeRepository,
                           ShiftAssignmentRepository assignmentRepository,
                           ShiftConfigRepository shiftConfigRepository,
                           EmployeeConstraintRepository constraintRepository,
                           HolidayRepository holidayRepository,
                           EmployeeRuleRepository employeeRuleRepository,
                           EmployeeAvailabilityRepository availabilityRepository,
                           DemandIntervalRepository demandRepository,
                           SkillRepository skillRepository,
                           com.example.shiftv1.common.error.ErrorLogBuffer errorLogBuffer) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.shiftConfigRepository = shiftConfigRepository;
        this.constraintRepository = constraintRepository;
        this.holidayRepository = holidayRepository;
        this.employeeRuleRepository = employeeRuleRepository;
        this.availabilityRepository = availabilityRepository;
        this.demandRepository = demandRepository;
        this.skillRepository = skillRepository;
        this.errorLogBuffer = errorLogBuffer;
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
        // Preload holidays once per run
        try {
            java.util.List<LocalDate> hol = holidayRepository.findDatesBetween(start, end);
            this.holidaysThisRun = new java.util.HashSet<>(hol);
        } catch (Exception ex) {
            this.holidaysThisRun = java.util.Set.of();
        }

        Map<Long, Integer> monthlyAssignmentCounts = new HashMap<>();
        List<ShiftAssignment> results = new ArrayList<>();
        // Precompute employees by skill to reduce per-slot filtering
        java.util.Map<Long, java.util.List<Employee>> employeesBySkill = new java.util.HashMap<>();
        for (Employee e : employees) {
            if (e.getSkills() == null) continue;
            for (com.example.shiftv1.skill.Skill s : e.getSkills()) {
                if (s == null || s.getId() == null) continue;
                employeesBySkill.computeIfAbsent(s.getId(), k -> new java.util.ArrayList<>()).add(e);
            }
        }

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            List<DemandInterval> intervals = demandRepository.findEffectiveForDate(day, day.getDayOfWeek());
            // filter active
            intervals = intervals.stream()
                    .filter(di -> di.getActive() == null || di.getActive())
                    .toList();

            // Preload all skills referenced for the day to avoid N+1 lookups
            java.util.Set<Long> skillIdsForDay = intervals.stream()
                    .map(DemandInterval::getSkill)
                    .filter(java.util.Objects::nonNull)
                    .map(s -> s.getId())
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Map<Long, com.example.shiftv1.skill.Skill> skillMapForDay = skillIdsForDay.isEmpty()
                    ? java.util.Map.of()
                    : skillRepository.findAllById(skillIdsForDay).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                com.example.shiftv1.skill.Skill::getId,
                                java.util.function.Function.identity()
                        ));

            // Pre-aggregate seat requirements per slot to avoid per-slot filtering
            final int G = Math.max(1, granularityMinutes);
            final int slots = (int)Math.ceil(24 * 60.0 / G);
            int[] globalWeekly = new int[slots];
            int[] globalDate = new int[slots];
            java.util.Map<Long,int[]> skillWeekly = new java.util.HashMap<>();
            java.util.Map<Long,int[]> skillDate = new java.util.HashMap<>();

            for (DemandInterval di : intervals) {
                int sMin = di.getStartTime().getHour() * 60 + di.getStartTime().getMinute();
                int eMin = di.getEndTime().getHour() * 60 + di.getEndTime().getMinute();
                if (eMin <= 0 || sMin >= 24*60) continue;
                int sIdx = Math.max(0, (int)Math.floor(sMin / (double)G));
                int eIdx = Math.min(slots, (int)Math.ceil(eMin / (double)G));
                boolean dateSpecific = di.getDate() != null;
                if (di.getSkill() == null) {
                    int[] arr = dateSpecific ? globalDate : globalWeekly;
                    for (int i=sIdx;i<eIdx;i++) arr[i] += Math.max(0, di.getRequiredSeats());
                } else {
                    Long sid = di.getSkill().getId();
                    if (sid == null) continue;
                    java.util.Map<Long,int[]> targetMap = dateSpecific ? skillDate : skillWeekly;
                    int[] arr = targetMap.computeIfAbsent(sid, k -> new int[slots]);
                    for (int i=sIdx;i<eIdx;i++) arr[i] += Math.max(0, di.getRequiredSeats());
                }
            }

            for (int idx=0; idx<slots; idx++) {
                java.time.LocalTime t = java.time.LocalTime.ofSecondOfDay((long)idx * G * 60L);
                java.time.LocalTime tEnd = t.plusMinutes(G);

                // Resolve per-skill requirements with date override
                java.util.Map<Long,Integer> perSkillReq = new java.util.HashMap<>();
                for (Long sid : skillIdsForDay) {
                    int req = 0;
                    int[] dArr = skillDate.get(sid);
                    int[] wArr = skillWeekly.get(sid);
                    if (dArr != null && dArr[idx] > 0) req = dArr[idx];
                    else if (wArr != null && wArr[idx] > 0) req = wArr[idx];
                    if (req > 0) perSkillReq.put(sid, req);
                }
                int globalReq = (globalDate[idx] > 0) ? globalDate[idx] : globalWeekly[idx];

                // Assignments per slot: assign skill seats first
                Set<Long> assignedToday = dailyAssigned.computeIfAbsent(day, d -> new HashSet<>());
                Map<Long, List<EmployeeConstraint>> consForDay = constraintsByDate.getOrDefault(day, Collections.emptyMap());

                if (!perSkillReq.isEmpty()) {
                    for (Map.Entry<Long, Integer> e : perSkillReq.entrySet()) {
                        if (e.getValue() == null || e.getValue() <= 0) continue;
                        com.example.shiftv1.skill.Skill skill = skillMapForDay.get(e.getKey());
                        if (skill == null) continue;
                        java.util.List<Employee> skillPool = employeesBySkill.getOrDefault(e.getKey(), java.util.List.of());
                        if (skillPool.isEmpty()) continue; // nobody has this skill
                        results.addAll(assignSeatsForSlot(day, t, tEnd, skill, e.getValue(), skillPool,
                                monthlyAssignmentCounts, dailyAssigned, consForDay));
                    }
                }
                if (globalReq > 0) {
                    results.addAll(assignSeatsForSlot(day, t, tEnd, null, globalReq, employees,
                            monthlyAssignmentCounts, dailyAssigned, consForDay));
                }
            }
        }

        return sortAssignments(assignmentRepository.saveAll(results));
    }

    // Async fire-and-forget starter for UI responsiveness
    @Async("scheduleExecutor")
    public void generateMonthlyFromDemandAsync(int year, int month, int granularityMinutes, boolean resetMonth) {
        try {
            generateMonthlyFromDemand(year, month, granularityMinutes, resetMonth);
        } catch (Exception e) {
            logger.error("需要ベースのシフト生成(非同期)でエラー", e);
        }
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
    // Cache demand intervals per date during generation to reduce DB calls
    private Map<LocalDate, java.util.List<com.example.shiftv1.demand.DemandInterval>> demandCache = java.util.Map.of();
    // Holidays snapshot for the current generation run
    private java.util.Set<LocalDate> holidaysThisRun = java.util.Set.of();

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

        // Preload demand intervals per date to avoid N*days DB queries
        try {
            java.util.Map<java.time.LocalDate, java.util.List<com.example.shiftv1.demand.DemandInterval>> tmp = new java.util.HashMap<>();
            for (LocalDate d0 = start; !d0.isAfter(end); d0 = d0.plusDays(1)) {
                tmp.put(d0, demandRepository.findEffectiveForDate(d0, d0.getDayOfWeek()));
            }
            demandCache = tmp;
        } catch (Exception ex) {
            demandCache = java.util.Map.of();
        }

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
        public final List<String> unavailableEmployees;
        public final List<String> limitedMismatchEmployees;
        public final List<String> alreadyAssignedToday;
        public final List<String> preferredEmployees;

        public ShiftDiagnostics(String shiftName,
                                LocalTime startTime,
                                LocalTime endTime,
                                int required,
                                int assigned,
                                List<String> assignedEmployees,
                                List<String> unavailableEmployees,
                                List<String> limitedMismatchEmployees,
                                List<String> alreadyAssignedToday,
                                List<String> preferredEmployees) {
            this.shiftName = shiftName;
            this.startTime = startTime;
            this.endTime = endTime;
            this.required = required;
            this.assigned = assigned;
            this.assignedEmployees = assignedEmployees;
            this.unavailableEmployees = unavailableEmployees;
            this.limitedMismatchEmployees = limitedMismatchEmployees;
            this.alreadyAssignedToday = alreadyAssignedToday;
            this.preferredEmployees = preferredEmployees;
        }
    }

    public static class DiagnosticReport {
        public final LocalDate date;
        public final List<ShiftDiagnostics> shifts;

        public DiagnosticReport(LocalDate date, List<ShiftDiagnostics> shifts) {
            this.date = date;
            this.shifts = shifts;
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

        // 旧「対象（平日/週末）」区分は廃止。曜日/祝日の指定に集約。

        // ルールスナップショット（従業員ごとの恒常ルール）
        this.ruleByEmployee = employeeRepository.findAll().stream()
                .collect(Collectors.toMap(Employee::getId,
                        e -> employeeRuleRepository.findByEmployeeId(e.getId()).orElse(null)));
        this.availabilityByEmployee = employeeRepository.findAll().stream()
                .collect(Collectors.toMap(Employee::getId,
                        e -> availabilityRepository.findByEmployeeId(e.getId())));
        this.dailyAssigned = new HashMap<>();
        this.dailyAssignedHours = new HashMap<>();

        Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate = constraintRepository
                .findByDateBetweenAndActiveTrue(start, end)
                .stream()
                .collect(Collectors.groupingBy(EmployeeConstraint::getDate,
                        Collectors.groupingBy(constraint -> constraint.getEmployee().getId())));

        Map<Long, Integer> monthlyAssignmentCounts = new HashMap<>();
        preloadAssignmentCounts(monthlyAssignmentCounts, recentAssignments);
        Map<LocalDate, Set<Long>> dailyAssignments = new HashMap<>();
        this.dailyAssigned = dailyAssignments;
        List<ShiftAssignment> results = new ArrayList<>();
        List<ShortageInfo> shortages = new ArrayList<>();

        // 祝日マップの準備
        Map<LocalDate, Boolean> holidayMap2 = new HashMap<>();
        try {
            List<LocalDate> holidays = holidayRepository.findDatesBetween(start, end);
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) { holidayMap2.put(d, false); }
            for (LocalDate hd : holidays) holidayMap2.put(hd, true);
        } catch (Exception ignored) {}

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            List<ShiftConfig> configsForDay = selectConfigsForDay(activeShiftConfigs, day, holidayMap2.getOrDefault(day, false));
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

    @Transactional(readOnly = true)
    public DiagnosticReport diagnoseDay(LocalDate day) {
        YearMonth ym = YearMonth.from(day);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        List<Employee> employees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(Employee::getId))
                .toList();
        List<ShiftConfig> activeShiftConfigs = shiftConfigRepository.findByActiveTrue();
        if (activeShiftConfigs.isEmpty()) {
            return new DiagnosticReport(day, List.of());
        }
        boolean isHoliday = false;
        try {
            isHoliday = holidayRepository.findDatesBetween(day, day).contains(day);
        } catch (Exception ignored) {}

        List<ShiftConfig> configsForDay = selectConfigsForDay(activeShiftConfigs, day, isHoliday);

        Map<Long, List<EmployeeConstraint>> constraintsForDay = constraintRepository
                .findByDateBetweenAndActiveTrue(day, day)
                .stream()
                .collect(Collectors.groupingBy(c -> c.getEmployee().getId()));

        // 既存の当日割当（参照用）
        Map<Long, Boolean> assignedToday = assignmentRepository.findByWorkDate(day).stream()
                .collect(Collectors.toMap(a -> a.getEmployee().getId(), a -> true, (a, b) -> true));

        List<ShiftDiagnostics> results = new ArrayList<>();
        for (ShiftConfig config : configsForDay) {
            List<ShiftAssignment> assigned = assignmentRepository.findByEmployeeAndWorkDateBetween(null, day, day);
            // 上記は使えないため、当日かつ同シフト名・時間の割当を絞る
            List<ShiftAssignment> assignedForShift = assignmentRepository.findByWorkDate(day).stream()
                    .filter(a -> a.getShiftName().equals(config.getName()))
                    .filter(a -> a.getStartTime().equals(config.getStartTime()))
                    .filter(a -> a.getEndTime().equals(config.getEndTime()))
                    .toList();

            List<String> assignedNames = assignedForShift.stream()
                    .map(a -> a.getEmployee().getName())
                    .toList();

            Set<Long> preferred = getPreferredEmployeesForShift(day, config,
                    Map.of(day, constraintsForDay));

            List<String> unavailableNames = new ArrayList<>();
            List<String> limitedMismatchNames = new ArrayList<>();
            List<String> alreadyAssignedNames = new ArrayList<>();

            for (Employee e : employees) {
                Long id = e.getId();
                if (assignedToday.getOrDefault(id, false)) {
                    alreadyAssignedNames.add(e.getName());
                    continue;
                }
                List<EmployeeConstraint> ecs = constraintsForDay.getOrDefault(id, List.of());
                boolean hasUnavailable = ecs.stream().anyMatch(c -> c.getType() == EmployeeConstraint.ConstraintType.UNAVAILABLE
                        || c.getType() == EmployeeConstraint.ConstraintType.VACATION
                        || c.getType() == EmployeeConstraint.ConstraintType.SICK_LEAVE
                        || c.getType() == EmployeeConstraint.ConstraintType.PERSONAL);
                if (hasUnavailable) {
                    unavailableNames.add(e.getName());
                    continue;
                }
                // LIMITED: 可用時間に収まらない場合を抽出
                boolean limitedMismatch = ecs.stream()
                        .filter(c -> c.getType() == EmployeeConstraint.ConstraintType.LIMITED)
                        .anyMatch(c -> {
                            LocalTime s = c.getStartTime() != null ? c.getStartTime() : LocalTime.MIN;
                            LocalTime t = c.getEndTime() != null ? c.getEndTime() : LocalTime.MAX;
                            return config.getStartTime().isBefore(s) || config.getEndTime().isAfter(t);
                        });
                if (limitedMismatch) {
                    limitedMismatchNames.add(e.getName());
                }
            }

            int requiredForShift = computeRequiredForShift(day, config);
            results.add(new ShiftDiagnostics(
                    config.getName(),
                    config.getStartTime(),
                    config.getEndTime(),
                    requiredForShift,
                    assignedForShift.size(),
                    assignedNames,
                    unavailableNames,
                    limitedMismatchNames,
                    alreadyAssignedNames,
                    employees.stream().filter(e -> preferred.contains(e.getId())).map(Employee::getName).toList()
            ));
        }
        return new DiagnosticReport(day, results);
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

        int requiredForShift = computeRequiredForShift(day, shiftConfig);
        while (assignments.size() < requiredForShift) {
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

        int shiftHours = java.time.Duration.between(shiftConfig.getStartTime(), shiftConfig.getEndTime()).toHoursPart();
        if (shiftHours == 0) {
            shiftHours = (int) java.time.Duration.between(shiftConfig.getStartTime(), shiftConfig.getEndTime()).toHours();
        }
        int requiredForShift = computeRequiredForShift(day, shiftConfig);
        while (assignments.size() < requiredForShift) {
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
            // 累積（日）時間を記録
            dailyAssignedHours.computeIfAbsent(day, d -> new HashMap<>())
                    .merge(candidate.getId(), shiftHours, Integer::sum);
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
            // 同日複数シフト可否（ルールで許可されていれば通す）
            EmployeeRule rule = ruleByEmployee.get(candidateId);
            boolean allowMulti = rule != null && Boolean.TRUE.equals(rule.getAllowMultipleShiftsPerDay());
            if (!allowMulti && assignedToday.contains(candidateId)) {
                continue;
            }
            // required skill check
            if (shiftConfig.getRequiredSkill() != null) {
                boolean hasSkill = candidate.getSkills() != null &&
                        candidate.getSkills().stream().anyMatch(s -> s.getId().equals(shiftConfig.getRequiredSkill().getId()));
                if (!hasSkill) {
                    continue;
                }
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
        // 恒常ルール: 祝日可否
        EmployeeRule rule = ruleByEmployee.get(employee.getId());
        boolean isHoliday = false;
        try { isHoliday = holidaysThisRun.contains(day);} catch (Exception ignored) {}
        if (rule != null && Boolean.FALSE.equals(rule.getAllowHolidayWork()) && isHoliday) {
            return false;
        }
        // 恒常ルール: 週間可用性（当日曜日に可用スロットが存在し、シフトが完全に内包されるか）
        List<EmployeeAvailability> avs = availabilityByEmployee.getOrDefault(employee.getId(), List.of());
        if (avs != null && !avs.isEmpty()) {
            boolean ok = avs.stream()
                    .filter(a -> a.getDayOfWeek() == day.getDayOfWeek())
                    .anyMatch(a -> !shiftConfig.getStartTime().isBefore(a.getStartTime()) && !shiftConfig.getEndTime().isAfter(a.getEndTime()));
            if (!ok) return false;
        }
        // 恒常ルール: 日上限時間（既割当 + 当該シフト）
        if (rule != null && rule.getDailyMaxHours() != null) {
            int used = dailyAssignedHours.getOrDefault(day, Collections.emptyMap()).getOrDefault(employee.getId(), 0);
            int add = (int) java.time.Duration.between(shiftConfig.getStartTime(), shiftConfig.getEndTime()).toHours();
            if (used + add > rule.getDailyMaxHours()) {
                return false;
            }
        }

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

    // 祝日・曜日に基づく設定選択（対象=平日/週末は廃止）
    private List<ShiftConfig> selectConfigsForDay(List<ShiftConfig> activeConfigs, LocalDate day, boolean isHoliday) {
        if (isHoliday) {
            List<ShiftConfig> holidayConfigs = activeConfigs.stream()
                    .filter(c -> Boolean.TRUE.equals(c.getHoliday()))
                    .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                    .toList();
            if (!holidayConfigs.isEmpty()) return holidayConfigs;
        }
        // まず複数曜日指定（days）を優先
        List<ShiftConfig> daysConfigs = activeConfigs.stream()
                .filter(c -> c.getDays() != null && !c.getDays().isEmpty() && c.getDays().contains(day.getDayOfWeek()))
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();
        if (!daysConfigs.isEmpty()) return daysConfigs;

        List<ShiftConfig> dowConfigs = activeConfigs.stream()
                .filter(c -> c.getDayOfWeek() != null && c.getDayOfWeek() == day.getDayOfWeek())
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();
        if (!dowConfigs.isEmpty()) return dowConfigs;
        // 特に曜日/祝日指定が無い設定のみ（以前の「対象」には依存しない）
        return activeConfigs.stream()
                .filter(c -> !Boolean.TRUE.equals(c.getHoliday()))
                .filter(c -> c.getDayOfWeek() == null)
                .filter(c -> c.getDays() == null || c.getDays().isEmpty())
                // 既存データの後方互換: 週末フラグが立っている設定は平日では除外
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();
    }

    // Demand integration: compute required seats for a shift based on DemandInterval
    private int computeRequiredForShift(LocalDate day, ShiftConfig shiftConfig) {
        try {
            List<com.example.shiftv1.demand.DemandInterval> intervals = demandCache.getOrDefault(day,
                    demandRepository.findEffectiveForDate(day, day.getDayOfWeek()));
            if (intervals == null || intervals.isEmpty()) {
                return Math.max(1, java.util.Optional.ofNullable(shiftConfig.getRequiredEmployees()).orElse(1));
            }
            java.time.LocalTime s = shiftConfig.getStartTime();
            java.time.LocalTime e = shiftConfig.getEndTime();
            Long requiredSkillId = shiftConfig.getRequiredSkill() != null ? shiftConfig.getRequiredSkill().getId() : null;
            // Build critical points (shift start/end + interval boundaries)
            java.util.TreeSet<java.time.LocalTime> points = new java.util.TreeSet<>();
            points.add(s);
            points.add(e);
            for (com.example.shiftv1.demand.DemandInterval di : intervals) {
                if (Boolean.FALSE.equals(di.getActive())) continue;
                if (di.getEndTime().isAfter(s) && di.getStartTime().isBefore(e)) {
                    points.add(di.getStartTime().isBefore(s) ? s : di.getStartTime());
                    points.add(di.getEndTime().isAfter(e) ? e : di.getEndTime());
                }
            }
            int maxRequired = 0;
            java.time.LocalTime prev = null;
            for (java.time.LocalTime t : points) {
                if (prev != null) {
                    java.time.LocalTime segStart = prev;
                    java.time.LocalTime segEnd = t;
                    if (!segStart.isBefore(segEnd)) continue;
                    // Compute global seats with date-specific overriding weekly definitions
                    java.util.List<com.example.shiftv1.demand.DemandInterval> globalIntervals = intervals.stream()
                            .filter(di -> di.getSkill() == null)
                            .filter(di -> di.getEndTime().isAfter(segStart) && di.getStartTime().isBefore(segEnd))
                            .toList();
                    boolean hasDateSpecificGlobal = globalIntervals.stream().anyMatch(di -> di.getDate() != null);
                    int global = (hasDateSpecificGlobal
                            ? globalIntervals.stream().filter(di -> di.getDate() != null)
                            : globalIntervals.stream())
                            .mapToInt(com.example.shiftv1.demand.DemandInterval::getRequiredSeats)
                            .sum();
                    int skillReq = 0;
                    if (requiredSkillId != null) {
                        java.util.List<com.example.shiftv1.demand.DemandInterval> skillIntervals = intervals.stream()
                                .filter(di -> di.getSkill() != null && requiredSkillId.equals(di.getSkill().getId()))
                                .filter(di -> di.getEndTime().isAfter(segStart) && di.getStartTime().isBefore(segEnd))
                                .toList();
                        boolean hasDateSpecificSkill = skillIntervals.stream().anyMatch(di -> di.getDate() != null);
                        skillReq = (hasDateSpecificSkill
                                ? skillIntervals.stream().filter(di -> di.getDate() != null)
                                : skillIntervals.stream())
                                .mapToInt(com.example.shiftv1.demand.DemandInterval::getRequiredSeats)
                                .sum();
                    }
                    int segmentRequired;
                    if (requiredSkillId != null) {
                        // Skill-specific shift: use skill-specific demand if present, otherwise fallback to global.
                        segmentRequired = (skillReq > 0 ? skillReq : global);
                    } else {
                        // Non-skill shift: use global demand only.
                        segmentRequired = global;
                    }
                    maxRequired = Math.max(maxRequired, segmentRequired);
                }
                prev = t;
            }
            if (maxRequired <= 0) {
                return Math.max(1, java.util.Optional.ofNullable(shiftConfig.getRequiredEmployees()).orElse(1));
            }
            return maxRequired;
        } catch (Exception ex) {
            return Math.max(1, java.util.Optional.ofNullable(shiftConfig.getRequiredEmployees()).orElse(1));
        }
    }
}
