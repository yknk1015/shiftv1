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

            // Build continuous seat tracks per skill (including global null skill)
            record Key(Long skillId) {}
            java.util.Set<Long> skillsForTracks = new java.util.HashSet<>(skillIdsForDay);
            // include null for global if any demand
            boolean hasGlobal = java.util.Arrays.stream(globalWeekly).anyMatch(v -> v > 0)
                    || java.util.Arrays.stream(globalDate).anyMatch(v -> v > 0);
            java.util.Map<Key,int[]> combined = new java.util.HashMap<>();
            if (hasGlobal) {
                int[] arr = new int[slots];
                for (int i=0;i<slots;i++) arr[i] = (globalDate[i] > 0 ? globalDate[i] : globalWeekly[i]);
                combined.put(new Key(null), arr);
            }
            for (Long sid : skillsForTracks) {
                int[] dArr = skillDate.get(sid);
                int[] wArr = skillWeekly.get(sid);
                int[] arr = new int[slots];
                for (int i=0;i<slots;i++) {
                    int dv = (dArr!=null?dArr[i]:0);
                    int wv = (wArr!=null?wArr[i]:0);
                    arr[i] = (dv>0?dv:wv);
                }
                combined.put(new Key(sid), arr);
            }

            // Convert seat arrays to windows via seat-tracking
            class Track { final int startIdx; Track(int s){ this.startIdx=s; } }
            java.util.Map<java.util.AbstractMap.SimpleEntry<java.time.LocalTime,java.time.LocalTime>, java.util.Map<Key,Integer>> winCounts = new java.util.HashMap<>();
            for (var entry : combined.entrySet()) {
                Key key = entry.getKey();
                int[] arr = entry.getValue();
                java.util.Deque<Track> open = new java.util.ArrayDeque<>();
                int maxSlotsPerShift = (int)Math.ceil(9 * 60.0 / G); // up to 9h window（8h勤務+1h休憩想定）
                for (int i=0;i<slots;i++) {
                    int need = arr[i];
                    // close if open exceeds need
                    while (open.size() > need) {
                        Track tr = open.removeLast();
                        java.time.LocalTime s = java.time.LocalTime.ofSecondOfDay((long)tr.startIdx * G * 60L);
                        java.time.LocalTime e = java.time.LocalTime.ofSecondOfDay((long)i * G * 60L);
                        var pair = new java.util.AbstractMap.SimpleEntry<>(s,e);
                        winCounts.computeIfAbsent(pair, k -> new java.util.HashMap<>())
                                .merge(key, 1, Integer::sum);
                    }
                    // enforce max shift length: roll over tracks that exceeded
                    if (!open.isEmpty()) {
                        java.util.List<Track> toClose = new java.util.ArrayList<>();
                        for (Track tr : open) {
                            if (i - tr.startIdx >= maxSlotsPerShift) {
                                toClose.add(tr);
                            }
                        }
                        if (!toClose.isEmpty()) {
                            for (Track tr : toClose) {
                                open.remove(tr);
                                java.time.LocalTime s = java.time.LocalTime.ofSecondOfDay((long)tr.startIdx * G * 60L);
                                java.time.LocalTime e = java.time.LocalTime.ofSecondOfDay((long)i * G * 60L);
                                var pair = new java.util.AbstractMap.SimpleEntry<>(s,e);
                                winCounts.computeIfAbsent(pair, k -> new java.util.HashMap<>())
                                        .merge(key, 1, Integer::sum);
                            }
                        }
                    }
                    // open if need exceeds open size
                    while (open.size() < need) {
                        open.addLast(new Track(i));
                    }
                }
                // close remaining at day end
                while (!open.isEmpty()) {
                    Track tr = open.removeLast();
                    java.time.LocalTime s = java.time.LocalTime.ofSecondOfDay((long)tr.startIdx * G * 60L);
                    java.time.LocalTime e = java.time.LocalTime.ofSecondOfDay((long)slots * G * 60L);
                    var pair = new java.util.AbstractMap.SimpleEntry<>(s,e);
                    winCounts.computeIfAbsent(pair, k -> new java.util.HashMap<>())
                            .merge(key, 1, Integer::sum);
                }
            }

            // Assign employees per window (sorted by start -> end to prioritize earlier windows)
            Map<Long, List<EmployeeConstraint>> consForDay = constraintsByDate.getOrDefault(day, Collections.emptyMap());
            java.util.List<java.util.Map.Entry<java.util.AbstractMap.SimpleEntry<java.time.LocalTime,java.time.LocalTime>, java.util.Map<Key,Integer>>> windows = new java.util.ArrayList<>(winCounts.entrySet());
            windows.sort(java.util.Comparator
                    .comparing((java.util.Map.Entry<java.util.AbstractMap.SimpleEntry<java.time.LocalTime,java.time.LocalTime>, ?> e) -> e.getKey().getKey())
                    .thenComparing(e -> e.getKey().getValue()));
            