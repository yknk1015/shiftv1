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
    private final com.example.shiftv1.skill.SkillPatternRepository skillPatternRepository;
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
                           com.example.shiftv1.skill.SkillPatternRepository skillPatternRepository,
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
        this.skillPatternRepository = skillPatternRepository;
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
            throw new IllegalStateException("蠕捺･ｭ蜩｡縺檎匳骭ｲ縺輔ｌ縺ｦ縺・∪縺帙ｓ縲ょ・縺ｫ蠕捺･ｭ蜩｡繧剃ｽ懈・縺励※縺上□縺輔＞縲・);
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
            // Preload skill patterns for skills used in the day (avoid per-window lookups)
            java.util.Map<Long, java.util.List<com.example.shiftv1.skill.SkillPattern>> patternsBySkill;
            if (skillIdsForDay.isEmpty()) {
                patternsBySkill = java.util.Map.of();
            } else {
                java.util.List<com.example.shiftv1.skill.SkillPattern> activePats =
                        com.example.shiftv1.schedule.ScheduleService.this.skillPatternRepository.findByActiveTrue();
                patternsBySkill = activePats.stream()
                        .filter(p -> p.getSkill() != null && p.getSkill().getId() != null && skillIdsForDay.contains(p.getSkill().getId()))
                        .collect(java.util.stream.Collectors.groupingBy(p -> p.getSkill().getId()));
            }

            // Pre-aggregate seat requirements per slot to avoid per-slot filtering
            final int G = Math.max(1, granularityMinutes);
            final int slots = (int)Math.ceil(24 * 60.0 / G);
            // Global (skill=null) demand is deprecated and ignored
            int[] globalWeekly = new int[0];
            int[] globalDate = new int[0];
            java.util.Map<Long,int[]> skillWeekly = new java.util.HashMap<>();
            java.util.Map<Long,int[]> skillDate = new java.util.HashMap<>();

            for (DemandInterval di : intervals) {
                int sMin = di.getStartTime().getHour() * 60 + di.getStartTime().getMinute();
                int eMin = di.getEndTime().getHour() * 60 + di.getEndTime().getMinute();
                if (eMin <= 0 || sMin >= 24*60) continue;
                int sIdx = Math.max(0, (int)Math.floor(sMin / (double)G));
                int eIdx = Math.min(slots, (int)Math.ceil(eMin / (double)G));
                boolean dateSpecific = di.getDate() != null;
                if (di.getSkill() != null) {
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
            java.util.Map<Key,int[]> combined = new java.util.HashMap<>();
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
                int maxSlotsPerShift = (int)Math.ceil(9 * 60.0 / G); // up to 9h window・・h蜍､蜍・1h莨第・諠ｳ螳夲ｼ・                for (int i=0;i<slots;i++) {
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
            // Build ordered window keys: First (earliest), then Last (latest), then Other (middle)
            java.util.List<java.util.AbstractMap.SimpleEntry<java.time.LocalTime,java.time.LocalTime>> winKeys =
                    new java.util.ArrayList<>(winCounts.keySet());
            java.util.LinkedHashSet<java.util.AbstractMap.SimpleEntry<java.time.LocalTime,java.time.LocalTime>> ordered =
                    new java.util.LinkedHashSet<>();
            // First: earliest start -> end
            winKeys.stream()
                    .sorted(java.util.Comparator
                            .comparing(java.util.AbstractMap.SimpleEntry<java.time.LocalTime,java.time.LocalTime>::getKey)
                            .thenComparing(java.util.AbstractMap.SimpleEntry<java.time.LocalTime,java.time.LocalTime>::getValue))
                    .forEach(ordered::add);
            // Last: latest end first
            winKeys.stream()
                    .sorted((a,b) -> {
                        int c = b.getValue().compareTo(a.getValue());
                        if (c != 0) return c;
                        return b.getKey().compareTo(a.getKey());
                    })
                    .forEach(ordered::add);
            // Other: middle near 12:00
            final int pivot = 12 * 60;
            winKeys.stream()
                    .sorted((a,b) -> {
                        int am = a.getKey().getHour()*60 + a.getKey().getMinute();
                        int aem = a.getValue().getHour()*60 + a.getValue().getMinute();
                        int amid = (am + aem) / 2;
                        int bm = b.getKey().getHour()*60 + b.getKey().getMinute();
                        int bem = b.getValue().getHour()*60 + b.getValue().getMinute();
                        int bmid = (bm + bem) / 2;
                        int dc = Integer.compare(Math.abs(amid - pivot), Math.abs(bmid - pivot));
                        if (dc != 0) return dc;
                        return Integer.compare(amid, bmid);
                    })
                    .forEach(ordered::add);
            for (var pair : ordered) {
                java.time.LocalTime s = pair.getKey();
                java.time.LocalTime tEnd = pair.getValue();
                // skill windows first (deterministic order by skillId nulls last -> globals蠕悟屓縺励↓縺吶ｋ縺ｨ繧ｹ繧ｭ繝ｫ荳崎ｶｳ譎ゅ↓繧ｰ繝ｭ繝ｼ繝舌Ν縺ｧ蝓九ａ繧峨ｌ繧・
                java.util.Map<Key,Integer> perMap = winCounts.get(pair);
                if (perMap == null || perMap.isEmpty()) continue;
                java.util.List<java.util.Map.Entry<Key,Integer>> perSkill = new java.util.ArrayList<>(perMap.entrySet());
                // skill windows first -> by skill priority (1 high .. 10 low), then by id; globals last
                perSkill.sort((a, b) -> {
                    Long as = a.getKey().skillId();
                    Long bs = b.getKey().skillId();
                    if (as == null && bs == null) return 0;
                    if (as == null) return 1; // globals last
                    if (bs == null) return -1;
                    com.example.shiftv1.skill.Skill sa = skillMapForDay.get(as);
                    com.example.shiftv1.skill.Skill sb = skillMapForDay.get(bs);
                    int pa = (sa!=null && sa.getPriority()!=null) ? sa.getPriority() : 5;
                    int pb = (sb!=null && sb.getPriority()!=null) ? sb.getPriority() : 5;
                    if (pa != pb) return Integer.compare(pa, pb); // 1 first
                    return Long.compare(as, bs);
                });
                for (var e2 : perSkill) {
                    Key k = e2.getKey();
                    int seats = e2.getValue();
                    if (seats <= 0) continue;
                    com.example.shiftv1.skill.Skill skill = (k.skillId()==null? null : skillMapForDay.get(k.skillId()));
                    java.util.List<Employee> pool = (k.skillId()==null? employees : employeesBySkill.getOrDefault(k.skillId(), java.util.List.of()));
                    if (pool.isEmpty() && k.skillId()!=null) continue;
                    // Split window into blocks per phase (First->Last->Other), using 8h primary, then 6/4h if gated
                    java.util.List<Integer> lens = new java.util.ArrayList<>();
                    lens.add(8);
                    for (String tok : (shortLengthsCsv==null?"":shortLengthsCsv).split(",")) {
                        tok = tok.trim(); if(tok.isEmpty()) continue;
                        try { int v = Integer.parseInt(tok); if(v>0) lens.add(v); } catch(Exception ignore) {}
                    }
                    // Preloaded patterns per skill (loaded earlier for the day)
                    java.util.List<com.example.shiftv1.skill.SkillPattern> pats = patternsBySkill.getOrDefault(k.skillId(), java.util.List.of());

                    // remaining uncovered intervals within [s, tEnd]
                    java.util.List<java.time.LocalTime[]> remaining = new java.util.ArrayList<>();
                    remaining.add(new java.time.LocalTime[]{s, tEnd});

                    // First (left)
                    java.time.LocalTime lastFirstEnd = null;
                    for (var blk : buildBlocksLeft(day, s, tEnd, skill, pool, consForDay, pats, lens)) {
                        if (consumeIfFits(remaining, blk[0], blk[1])) {
                            results.addAll(assignSeatsForSlot(day, blk[0], blk[1], skill, seats, pool,
                                    monthlyAssignmentCounts, dailyAssigned, consForDay));
                            lastFirstEnd = blk[1];
                        }
                    }
                    // Last (right)
                    for (var blk : buildBlocksRight(day, s, tEnd, skill, pool, consForDay, pats, lens)) {
                        if (consumeIfFits(remaining, blk[0], blk[1])) {
                            results.addAll(assignSeatsForSlot(day, blk[0], blk[1], skill, seats, pool,
                                    monthlyAssignmentCounts, dailyAssigned, consForDay));
                        }
                    }
                    // Other (center) 窶・pivot at end of First if available, else s+8h (or midpoint fallback)
                    java.time.LocalTime pivotTime = (lastFirstEnd != null) ? lastFirstEnd : s.plusHours(8);
                    if (pivotTime.isAfter(tEnd) || pivotTime.isBefore(s)) {
                        int midSec = (s.toSecondOfDay()+tEnd.toSecondOfDay())/2;
                        pivotTime = java.time.LocalTime.ofSecondOfDay(midSec);
                    }
                    for (var blk : buildBlocksCenter(day, s, tEnd, pivotTime, skill, pool, consForDay, pats, lens)) {
                        if (consumeIfFits(remaining, blk[0], blk[1])) {
                            results.addAll(assignSeatsForSlot(day, blk[0], blk[1], skill, seats, pool,
                                    monthlyAssignmentCounts, dailyAssigned, consForDay));
                        }
                    }
                }
            }
        }

        return sortAssignments(assignmentRepository.saveAll(results));
    }

    // Async fire-and-forget starter for UI responsiveness
    @Async("scheduleExecutor")
    @CacheEvict(value = "monthly-schedules", key = "#year + '-' + #month")
    public void generateMonthlyFromDemandAsync(int year, int month, int granularityMinutes, boolean resetMonth) {
        try {
            generateMonthlyFromDemand(year, month, granularityMinutes, resetMonth);
        } catch (Exception e) {
            logger.error("髴隕√・繝ｼ繧ｹ縺ｮ繧ｷ繝輔ヨ逕滓・(髱槫酔譛・縺ｧ繧ｨ繝ｩ繝ｼ", e);
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
        final int blockHours = Math.max(1, (int) java.time.Duration.between(start, end).toHours());
        sorted.sort(Comparator
                // short-shift candidates first for compliance/preferences
                .comparing((Employee e) -> !isShortCandidate(e, blockHours, day))
                // then those preferred by constraints
                .thenComparing((Employee e) -> !preferred.contains(e.getId()))
                // then by fairness accumulated in month
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

    // Configurable short-shift behavior (defaults aligned with request)
    @org.springframework.beans.factory.annotation.Value("${shift.short.enabled:true}")
    private boolean shortEnabled;
    // c_or / a_only / b_only
    @org.springframework.beans.factory.annotation.Value("${shift.short.logic:c_or}")
    private String shortLogic;
    // comma separated hours e.g. 6,4
    @org.springframework.beans.factory.annotation.Value("${shift.short.lengths:6,4}")
    private String shortLengthsCsv;
    @org.springframework.beans.factory.annotation.Value("${shift.short.minLength:4}")
    private int shortMinLengthHours;
    @org.springframework.beans.factory.annotation.Value("${shift.window.roundingMinutes:60}")
    private int windowRoundingMinutes;

    // --- Helpers for short-shift gating and block generation ---
    private boolean isShortCandidate(Employee e, int lengthHours, LocalDate day) {
        if (lengthHours >= 8) return false; // short only matters for < 8h
        boolean a = false, b = false;
        // a) rule-based
        EmployeeRule rule = ruleByEmployee.get(e.getId());
        if (rule != null && rule.getDailyMaxHours() != null) {
            a = rule.getDailyMaxHours() <= lengthHours;
        }
        // b) availability-based (max contiguous availability in minutes on that day)
        int maxAvail = maxAvailableMinutes(e.getId(), day.getDayOfWeek());
        b = maxAvail > 0 && maxAvail <= lengthHours * 60;
        String logic = (shortLogic == null ? "c_or" : shortLogic.trim().toLowerCase());
        return switch (logic) {
            case "a_only" -> a;
            case "b_only" -> b;
            default -> (a || b);
        };
    }

    private int maxAvailableMinutes(Long empId, DayOfWeek dow) {
        List<EmployeeAvailability> list = availabilityByEmployee.getOrDefault(empId, List.of());
        List<int[]> mins = new ArrayList<>();
        for (EmployeeAvailability av : list) {
            if (av.getDayOfWeek() != dow) continue;
            int s = av.getStartTime().getHour()*60 + av.getStartTime().getMinute();
            int e = av.getEndTime().getHour()*60 + av.getEndTime().getMinute();
            mins.add(new int[]{s,e});
        }
        if (mins.isEmpty()) return 0;
        mins.sort(Comparator.comparingInt(a -> a[0]));
        int max = 0; int curS = mins.get(0)[0]; int curE = mins.get(0)[1];
        for (int i=1;i<mins.size();i++){
            int s = mins.get(i)[0], e = mins.get(i)[1];
            if (s <= curE) { curE = Math.max(curE, e); }
            else { max = Math.max(max, curE - curS); curS = s; curE = e; }
        }
        max = Math.max(max, curE - curS);
        return max;
    }

    private boolean patternAllowsLength(List<com.example.shiftv1.skill.SkillPattern> pats,
                                        Long skillId, DayOfWeek dow,
                                        java.time.LocalTime bs, java.time.LocalTime be,
                                        int lengthHours) {
        if (pats == null || pats.isEmpty() || skillId == null) return false;
        for (var p : pats) {
            if (p.getSkill() == null || !Objects.equals(p.getSkill().getId(), skillId)) continue;
            if (Boolean.FALSE.equals(p.getActive())) continue;
            if (p.getDayOfWeek() != null && p.getDayOfWeek() != dow) continue;
            boolean timeOk = true;
            if (p.getStartTime() != null && p.getEndTime() != null) {
                timeOk = !bs.isBefore(p.getStartTime()) && !be.isAfter(p.getEndTime());
            }
            if (!timeOk) continue;
            String csv = p.getAllowedLengthsCsv()==null?"":p.getAllowedLengthsCsv();
            for (String tok : csv.split(",")) {
                try { if (Integer.parseInt(tok.trim()) == lengthHours) return true; } catch(Exception ignore) {}
            }
        }
        return false;
    }

    // Returns list of blocks as [start,end] arrays
    private List<java.time.LocalTime[]> buildBlocksLeft(LocalDate day,
                                                        java.time.LocalTime s, java.time.LocalTime e,
                                                        com.example.shiftv1.skill.Skill skill,
                                                        List<Employee> pool,
                                                        Map<Long, List<EmployeeConstraint>> consForDay,
                                                        List<com.example.shiftv1.skill.SkillPattern> pats,
                                                        List<Integer> lens) {
        List<java.time.LocalTime[]> out = new ArrayList<>();
        java.time.LocalTime cur = s;
        while (cur.plusHours(shortMinLengthHours).isBefore(e) || cur.plusHours(shortMinLengthHours).equals(e)) {
            boolean placed = false;
            for (int L : lens) {
                java.time.LocalTime end = cur.plusHours(L);
                if (end.isAfter(e)) continue;
                if (L < 8 && !allowShortBlock(day, cur, end, skill, pool, consForDay, pats, L)) continue;
                out.add(new java.time.LocalTime[]{cur, end});
                cur = end; placed = true; break;
            }
            if (!placed) break;
        }
        return out;
    }

    private List<java.time.LocalTime[]> buildBlocksRight(LocalDate day,
                                                         java.time.LocalTime s, java.time.LocalTime e,
                                                         com.example.shiftv1.skill.Skill skill,
                                                         List<Employee> pool,
                                                         Map<Long, List<EmployeeConstraint>> consForDay,
                                                         List<com.example.shiftv1.skill.SkillPattern> pats,
                                                         List<Integer> lens) {
        List<java.time.LocalTime[]> out = new ArrayList<>();
        java.time.LocalTime curEnd = e;
        while (s.plusHours(shortMinLengthHours).isBefore(curEnd) || s.plusHours(shortMinLengthHours).equals(curEnd)) {
            boolean placed = false;
            for (int L : lens) {
                java.time.LocalTime start = curEnd.minusHours(L);
                if (start.isBefore(s)) continue;
                if (L < 8 && !allowShortBlock(day, start, curEnd, skill, pool, consForDay, pats, L)) continue;
                out.add(new java.time.LocalTime[]{start, curEnd});
                curEnd = start; placed = true; break;
            }
            if (!placed) break;
        }
        return out;
    }

    private List<java.time.LocalTime[]> buildBlocksCenter(LocalDate day,
                                                          java.time.LocalTime s, java.time.LocalTime e,
                                                          java.time.LocalTime pivot,
                                                          com.example.shiftv1.skill.Skill skill,
                                                          List<Employee> pool,
                                                          Map<Long, List<EmployeeConstraint>> consForDay,
                                                          List<com.example.shiftv1.skill.SkillPattern> pats,
                                                          List<Integer> lens) {
        List<java.time.LocalTime[]> out = new ArrayList<>();
        // left side (ending at pivot)
        java.time.LocalTime rightCursor = (!pivot.isBefore(s) && !pivot.isAfter(e)) ? pivot : s.plusSeconds((e.toSecondOfDay()-s.toSecondOfDay())/2);
        java.time.LocalTime leftCursor = rightCursor;
        // fill leftwards
        while (!leftCursor.minusHours(shortMinLengthHours).isBefore(s)) {
            boolean placed = false;
            for (int L : lens) {
                java.time.LocalTime start = leftCursor.minusHours(L);
                if (start.isBefore(s)) continue;
                if (L < 8 && !allowShortBlock(day, start, leftCursor, skill, pool, consForDay, pats, L)) continue;
                out.add(new java.time.LocalTime[]{start, leftCursor});
                leftCursor = start; placed = true; break;
            }
            if (!placed) break;
        }
        // fill rightwards
        while (!rightCursor.plusHours(shortMinLengthHours).isAfter(e)) {
            boolean placed = false;
            for (int L : lens) {
                java.time.LocalTime end = rightCursor.plusHours(L);
                if (end.isAfter(e)) continue;
                if (L < 8 && !allowShortBlock(day, rightCursor, end, skill, pool, consForDay, pats, L)) continue;
                out.add(new java.time.LocalTime[]{rightCursor, end});
                rightCursor = end; placed = true; break;
            }
            if (!placed) break;
        }
        return out;
    }

    private boolean allowShortBlock(LocalDate day,
                                    java.time.LocalTime bs, java.time.LocalTime be,
                                    com.example.shiftv1.skill.Skill skill,
                                    List<Employee> pool,
                                    Map<Long, List<EmployeeConstraint>> consForDay,
                                    List<com.example.shiftv1.skill.SkillPattern> pats,
                                    int lengthHours) {
        // Skill patterns gate
        boolean patternOk = (skill != null) && patternAllowsLength(pats, skill.getId(), day.getDayOfWeek(), bs, be, lengthHours);
        boolean shortOk = false;
        if (shortEnabled) {
            ShiftConfig tmpCfg = new ShiftConfig("blk", bs, be, 1);
            if (skill != null) tmpCfg.setRequiredSkill(skill);
            for (Employee cand : pool) {
                if (!isShortCandidate(cand, lengthHours, day)) continue;
                if (isEmployeeAvailable(cand, day, tmpCfg, Map.of(day, consForDay))) { shortOk = true; break; }
            }
        }
        return patternOk || shortOk;
    }

    // interval coverage helpers (operate on LocalTime[] {start,end} list assumed non-overlapping, sorted)
    private boolean consumeIfFits(List<java.time.LocalTime[]> remaining,
                                  java.time.LocalTime a, java.time.LocalTime b) {
        int idx = findCoveringIntervalIndex(remaining, a, b);
        if (idx < 0) return false;
        subtractInterval(remaining, idx, a, b);
        return true;
    }

    private int findCoveringIntervalIndex(List<java.time.LocalTime[]> rem,
                                          java.time.LocalTime a, java.time.LocalTime b) {
        for (int i=0;i<rem.size();i++){
            var it = rem.get(i);
            if ((a.equals(it[0]) || a.isAfter(it[0])) && (b.equals(it[1]) || b.isBefore(it[1]))) return i;
        }
        return -1;
    }

    private void subtractInterval(List<java.time.LocalTime[]> rem, int idx,
                                  java.time.LocalTime a, java.time.LocalTime b) {
        var it = rem.remove(idx);
        // left remainder
        if (a.isAfter(it[0])) {
            rem.add(idx++, new java.time.LocalTime[]{it[0], a});
        }
        // right remainder
        if (b.isBefore(it[1])) {
            rem.add(idx, new java.time.LocalTime[]{b, it[1]});
        }
        // keep sorted by start
        rem.sort(java.util.Comparator.comparing(t -> t[0]));
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
            logger.info("繧ｷ繝輔ヨ逕滓・繧帝幕蟋九＠縺ｾ縺・ {}蟷ｴ{}譛・, year, month);
        } catch (Exception e) {
            logger.error("辟｡蜉ｹ縺ｪ蟷ｴ譛医〒縺・ {}蟷ｴ{}譛・, year, month, e);
            throw new IllegalArgumentException("辟｡蜉ｹ縺ｪ蟷ｴ譛医〒縺・ " + year + "蟷ｴ" + month + "譛・);
        }

        List<Employee> baseEmployees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(Employee::getId))
                .toList();
        if (baseEmployees.isEmpty()) {
            logger.error("蠕捺･ｭ蜩｡縺檎匳骭ｲ縺輔ｌ縺ｦ縺・∪縺帙ｓ");
            throw new IllegalStateException("蠕捺･ｭ蜩｡縺檎匳骭ｲ縺輔ｌ縺ｦ縺・∪縺帙ｓ縲ゅす繝輔ヨ逕滓・蜑阪↓蠕捺･ｭ蜩｡繧定ｿｽ蜉縺励※縺上□縺輔＞縲・);
        }

        Optional<ShiftAssignment> lastAssignmentBeforePeriod =
                assignmentRepository.findTopByWorkDateBeforeOrderByWorkDateDesc(start);
        List<Employee> employees = rotateEmployees(baseEmployees,
                determineRotationOffset(lastAssignmentBeforePeriod, baseEmployees));

        List<ShiftAssignment> recentAssignments = loadRecentAssignments(start);

        logger.info("逋ｻ骭ｲ蠕捺･ｭ蜩｡謨ｰ: {}蜷・, employees.size());
        // 螳牙・縺ｮ縺溘ａ縲∵怦谺｡荳諡ｬ蜑企勁縺ｯ陦後ｏ縺ｪ縺・        // assignmentRepository.deleteByWorkDateBetween(start, end);
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            assignmentRepository.deleteByWorkDate(d);
        }
        logger.info("譌｢蟄倥・繧ｷ繝輔ヨ蜑ｲ繧雁ｽ薙※繧貞炎髯､縺励∪縺励◆: {} 縺九ｉ {}", start, end);

        List<ShiftConfig> activeShiftConfigs = shiftConfigRepository.findByActiveTrue();
        if (activeShiftConfigs.isEmpty()) {
            logger.error("繧｢繧ｯ繝・ぅ繝悶↑繧ｷ繝輔ヨ險ｭ螳壹′蟄伜惠縺励∪縺帙ｓ");
            throw new IllegalStateException("繧｢繧ｯ繝・ぅ繝悶↑繧ｷ繝輔ヨ險ｭ螳壹′蟄伜惠縺励∪縺帙ｓ縲ゅす繝輔ヨ險ｭ螳壹ｒ逋ｻ骭ｲ縺励※縺上□縺輔＞縲・);
        }

        // 縲悟ｯｾ雎｡・亥ｹｳ譌･/騾ｱ譛ｫ・峨阪・讎ょｿｵ縺ｯ蟒・ｭ｢縲ゆｻ･髯阪・譖懈律/逾晄律縺ｮ謖・ｮ壹・縺ｿ繧剃ｽｿ逕ｨ縺吶ｋ縲・
        Map<LocalDate, Map<Long, List<EmployeeConstraint>>> constraintsByDate = constraintRepository
                .findByDateBetweenAndActiveTrue(start, end)
                .stream()
                .collect(Collectors.groupingBy(EmployeeConstraint::getDate,
                        Collectors.groupingBy(constraint -> constraint.getEmployee().getId())));

        Map<Long, Integer> monthlyAssignmentCounts = new HashMap<>();
        preloadAssignmentCounts(monthlyAssignmentCounts, recentAssignments);
        Map<LocalDate, Set<Long>> dailyAssignments = new HashMap<>();
        List<ShiftAssignment> results = new ArrayList<>();

        // 逾晄律繝ｻ譖懈律縺ｮ謖・ｮ壹↓蝓ｺ縺･縺・※譌･蛻･縺ｫ驕ｩ逕ｨ險ｭ螳壹ｒ驕ｸ謚・        Map<LocalDate, Boolean> holidayMap = new HashMap<>();
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
        logger.info("繧ｷ繝輔ヨ逕滓・縺悟ｮ御ｺ・＠縺ｾ縺励◆: {}莉ｶ縺ｮ蜑ｲ繧雁ｽ薙※繧堤函謌・, savedAssignments.size());
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
            int required = (requiredSkillId != null) ? skillReq : 0; // global demand abolished
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
            logger.info("繧ｷ繝輔ヨ逕滓・繧帝幕蟋九＠縺ｾ縺・ {}蟷ｴ{}譛・, year, month);
        } catch (Exception e) {
            logger.error("荳肴ｭ｣縺ｪ蟷ｴ譛医〒縺・ {}蟷ｴ{}譛・, year, month, e);
            throw new IllegalArgumentException("荳肴ｭ｣縺ｪ蟷ｴ譛医〒縺・ " + year + "蟷ｴ" + month + "譛・);
        }

        List<Employee> baseEmployees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(Employee::getId))
                .toList();
        if (baseEmployees.isEmpty()) {
            logger.error("蠕捺･ｭ蜩｡縺檎匳骭ｲ縺輔ｌ縺ｦ縺・∪縺帙ｓ");
            throw new IllegalStateException("蠕捺･ｭ蜩｡縺檎匳骭ｲ縺輔ｌ縺ｦ縺・∪縺帙ｓ縲ゅす繝輔ヨ逕滓・縺ｮ蜑阪↓蠕捺･ｭ蜩｡繧定ｿｽ蜉縺励※縺上□縺輔＞縲・);
        }

        Optional<ShiftAssignment> lastAssignmentBeforePeriod =
                assignmentRepository.findTopByWorkDateBeforeOrderByWorkDateDesc(start);
        List<Employee> employees = rotateEmployees(baseEmployees,
                determineRotationOffset(lastAssignmentBeforePeriod, baseEmployees));

        List<ShiftAssignment> recentAssignments = loadRecentAssignments(start);

        logger.info("逋ｻ骭ｲ蠕捺･ｭ蜩｡謨ｰ: {}蜷・, employees.size());
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            assignmentRepository.deleteByWorkDate(d);
        }
        logger.info("蟇ｾ雎｡譛医・譌･蛻･繧ｷ繝輔ヨ繧貞炎髯､縺励∪縺励◆: {} 縺九ｉ {}", start, end);

        List<ShiftConfig> activeShiftConfigs = shiftConfigRepository.findByActiveTrue();
        if (activeShiftConfigs.isEmpty()) {
            logger.error("繧｢繧ｯ繝・ぅ繝悶↑繧ｷ繝輔ヨ險ｭ螳壹′蟄伜惠縺励∪縺帙ｓ");
            throw new IllegalStateException("繧｢繧ｯ繝・ぅ繝悶↑繧ｷ繝輔ヨ險ｭ螳壹′蟄伜惠縺励∪縺帙ｓ縲ゅす繝輔ヨ險ｭ螳壹ｒ逋ｻ骭ｲ縺励※縺上□縺輔＞縲・);
        }

        // 譌ｧ縲悟ｯｾ雎｡・亥ｹｳ譌･/騾ｱ譛ｫ・峨榊玄蛻・・蟒・ｭ｢縲よ屆譌･/逾晄律縺ｮ謖・ｮ壹↓髮・ｴ・・
        // 繝ｫ繝ｼ繝ｫ繧ｹ繝翫ャ繝励す繝ｧ繝・ヨ・亥ｾ捺･ｭ蜩｡縺斐→縺ｮ諱貞ｸｸ繝ｫ繝ｼ繝ｫ・・        this.ruleByEmployee = employeeRepository.findAll().stream()
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

        // 逾晄律繝槭ャ繝励・貅門ｙ
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
        logger.info("繧ｷ繝輔ヨ逕滓・縺悟ｮ御ｺ・＠縺ｾ縺励◆: {}莉ｶ縺ｮ蜑ｲ蠖薙ｒ菴懈・", savedAssignments.size());
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

        // 譌｢蟄倥・蠖捺律蜑ｲ蠖難ｼ亥盾辣ｧ逕ｨ・・        Map<Long, Boolean> assignedToday = assignmentRepository.findByWorkDate(day).stream()
                .collect(Collectors.toMap(a -> a.getEmployee().getId(), a -> true, (a, b) -> true));

        List<ShiftDiagnostics> results = new ArrayList<>();
        for (ShiftConfig config : configsForDay) {
            List<ShiftAssignment> assigned = assignmentRepository.findByEmployeeAndWorkDateBetween(null, day, day);
            // 荳願ｨ倥・菴ｿ縺医↑縺・◆繧√∝ｽ捺律縺九▽蜷後す繝輔ヨ蜷阪・譎る俣縺ｮ蜑ｲ蠖薙ｒ邨槭ｋ
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
                // LIMITED: 蜿ｯ逕ｨ譎る俣縺ｫ蜿弱∪繧峨↑縺・ｴ蜷医ｒ謚ｽ蜃ｺ
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
            logger.warn("蜑ｲ蠖謎ｸ崎ｶｳ: {} 縺ｮ {} 縺ｯ蠢・ｦ・{} 螳滄圀:{}",
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
        logger.info("蟇ｾ雎｡譛医・繧ｷ繝輔ヨ繧貞・譛溷喧: {}-{}", year, month);
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
            // 邏ｯ遨搾ｼ域律・画凾髢薙ｒ險倬鹸
            dailyAssignedHours.computeIfAbsent(day, d -> new HashMap<>())
                    .merge(candidate.getId(), shiftHours, Integer::sum);
        }

        if (assignments.size() < shiftConfig.getRequiredEmployees()) {
            // 荳崎ｶｳ縺後≠縺｣縺ｦ繧ゆｾ句､悶↓縺帙★縲∬ｭｦ蜻翫Ο繧ｰ縺ｮ縺ｿ蜃ｺ縺励※邯夊｡・            logger.warn("蜑ｲ蠖謎ｸ崎ｶｳ: {} 縺ｮ {} 縺ｯ蠢・ｦ・{} 螳滄圀:{}",
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
            // 蜷梧律隍・焚繧ｷ繝輔ヨ蜿ｯ蜷ｦ・医Ν繝ｼ繝ｫ縺ｧ險ｱ蜿ｯ縺輔ｌ縺ｦ縺・ｌ縺ｰ騾壹☆・・            EmployeeRule rule = ruleByEmployee.get(candidateId);
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
        // 諱貞ｸｸ繝ｫ繝ｼ繝ｫ: 逾晄律蜿ｯ蜷ｦ
        EmployeeRule rule = ruleByEmployee.get(employee.getId());
        boolean isHoliday = false;
        try { isHoliday = holidaysThisRun.contains(day);} catch (Exception ignored) {}
        if (rule != null && Boolean.FALSE.equals(rule.getAllowHolidayWork()) && isHoliday) {
            return false;
        }
        // 諱貞ｸｸ繝ｫ繝ｼ繝ｫ: 騾ｱ髢灘庄逕ｨ諤ｧ・亥ｽ捺律譖懈律縺ｫ蜿ｯ逕ｨ繧ｹ繝ｭ繝・ヨ縺悟ｭ伜惠縺励√す繝輔ヨ縺悟ｮ悟・縺ｫ蜀・桁縺輔ｌ繧九°・・        List<EmployeeAvailability> avs = availabilityByEmployee.getOrDefault(employee.getId(), List.of());
        if (avs != null && !avs.isEmpty()) {
            boolean ok = avs.stream()
                    .filter(a -> a.getDayOfWeek() == day.getDayOfWeek())
                    .anyMatch(a -> !shiftConfig.getStartTime().isBefore(a.getStartTime()) && !shiftConfig.getEndTime().isAfter(a.getEndTime()));
            if (!ok) return false;
        }
        // 諱貞ｸｸ繝ｫ繝ｼ繝ｫ: 譌･荳企剞譎る俣・域里蜑ｲ蠖・+ 蠖楢ｩｲ繧ｷ繝輔ヨ・・        if (rule != null && rule.getDailyMaxHours() != null) {
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
                    logger.debug("蠕捺･ｭ蜩｡{}縺ｯ{}縺ｫ{}縺ｮ縺溘ａ蜍､蜍吩ｸ榊庄", employee.getName(), day, constraint.getType());
                    return false;
                }
                case LIMITED -> {
                    LocalTime availableStart = constraint.getStartTime() != null ? constraint.getStartTime() : LocalTime.MIN;
                    LocalTime availableEnd = constraint.getEndTime() != null ? constraint.getEndTime() : LocalTime.MAX;
                    if (shiftConfig.getStartTime().isBefore(availableStart) || shiftConfig.getEndTime().isAfter(availableEnd)) {
                        logger.debug("蠕捺･ｭ蜩｡{}縺ｯ{}縺ｮ繧ｷ繝輔ヨ{}縺ｫ譎る俣蛻ｶ髯舌・縺溘ａ蜑ｲ繧雁ｽ薙※荳榊庄", employee.getName(), day, shiftConfig.getName());
                        return false;
                    }
                }
                case PREFERRED -> {
                    // 蟶梧悍縺ｯ蜆ｪ蜈亥ｺｦ縺ｮ縺ｿ縺ｫ蠖ｱ髻ｿ縺吶ｋ縺溘ａ蜿ｯ蜷ｦ縺ｫ縺ｯ蠖ｱ髻ｿ縺輔○縺ｪ縺・                }
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
        logger.debug("蠕捺･ｭ蜩｡繝ｪ繧ｹ繝医ｒ{}繝昴ず繧ｷ繝ｧ繝ｳ蝗櫁ｻ｢縺励∪縺励◆", normalizedOffset);
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
                logger.debug("蜑榊屓縺ｮ蜑ｲ繧雁ｽ薙※蠕捺･ｭ蜩｡{}縺ｫ蝓ｺ縺･縺阪が繝輔そ繝・ヨ{}繧剃ｽｿ逕ｨ", lastEmployee.getId(), offset);
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
            logger.debug("驕主悉譛滄俣{}縺九ｉ{}縺ｮ{}莉ｶ縺ｮ蜑ｲ繧雁ｽ薙※繧定ｪｭ縺ｿ霎ｼ縺ｿ", historyStart, historyEnd, assignments.size());
            return assignments;
        } catch (DateTimeException e) {
            logger.debug("驕主悉蜑ｲ繧雁ｽ薙※縺ｮ蜿門ｾ励↓螟ｱ謨励＠縺ｾ縺励◆: {}", e.getMessage());
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
            logger.debug("驕主悉縺ｮ蜑ｲ繧雁ｽ薙※繧畜}莉ｶ蛻・・繝ｪ繝ｭ繝ｼ繝峨＠縺ｾ縺励◆", monthlyAssignmentCounts.size());
        }
    }

    // 逾晄律繝ｻ譖懈律縺ｫ蝓ｺ縺･縺剰ｨｭ螳夐∈謚橸ｼ亥ｯｾ雎｡=蟷ｳ譌･/騾ｱ譛ｫ縺ｯ蟒・ｭ｢・・    private List<ShiftConfig> selectConfigsForDay(List<ShiftConfig> activeConfigs, LocalDate day, boolean isHoliday) {
        if (isHoliday) {
            List<ShiftConfig> holidayConfigs = activeConfigs.stream()
                    .filter(c -> Boolean.TRUE.equals(c.getHoliday()))
                    .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                    .toList();
            if (!holidayConfigs.isEmpty()) return holidayConfigs;
        }
        // 縺ｾ縺夊､・焚譖懈律謖・ｮ夲ｼ・ays・峨ｒ蜆ｪ蜈・        List<ShiftConfig> daysConfigs = activeConfigs.stream()
                .filter(c -> c.getDays() != null && !c.getDays().isEmpty() && c.getDays().contains(day.getDayOfWeek()))
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();
        if (!daysConfigs.isEmpty()) return daysConfigs;

        List<ShiftConfig> dowConfigs = activeConfigs.stream()
                .filter(c -> c.getDayOfWeek() != null && c.getDayOfWeek() == day.getDayOfWeek())
                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
                .toList();
        if (!dowConfigs.isEmpty()) return dowConfigs;
        // 迚ｹ縺ｫ譖懈律/逾晄律謖・ｮ壹′辟｡縺・ｨｭ螳壹・縺ｿ・井ｻ･蜑阪・縲悟ｯｾ雎｡縲阪↓縺ｯ萓晏ｭ倥＠縺ｪ縺・ｼ・        return activeConfigs.stream()
                .filter(c -> !Boolean.TRUE.equals(c.getHoliday()))
                .filter(c -> c.getDayOfWeek() == null)
                .filter(c -> c.getDays() == null || c.getDays().isEmpty())
                // 譌｢蟄倥ョ繝ｼ繧ｿ縺ｮ蠕梧婿莠呈鋤: 騾ｱ譛ｫ繝輔Λ繧ｰ縺檎ｫ九▲縺ｦ縺・ｋ險ｭ螳壹・蟷ｳ譌･縺ｧ縺ｯ髯､螟・                .sorted(Comparator.comparing(ShiftConfig::getStartTime))
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
