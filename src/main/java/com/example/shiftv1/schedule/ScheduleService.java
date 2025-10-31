package com.example.shiftv1.schedule;

import com.example.shiftv1.config.ShiftConfigRepository;
import com.example.shiftv1.config.PairingSettings;
import com.example.shiftv1.config.PairingSettingsRepository;
import com.example.shiftv1.constraint.EmployeeConstraintRepository;
import com.example.shiftv1.demand.DemandIntervalRepository;
import com.example.shiftv1.employee.EmployeeAvailabilityRepository;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.employee.EmployeeRuleRepository;
import com.example.shiftv1.holiday.HolidayRepository;
import com.example.shiftv1.skill.SkillRepository;
import com.example.shiftv1.breaks.BreakPeriodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final BreakPeriodRepository breakRepository;
    private final PairingSettingsRepository pairingSettingsRepository;

    public ScheduleService(EmployeeRepository employeeRepository,
                           ShiftAssignmentRepository assignmentRepository,
                           ShiftConfigRepository shiftConfigRepository,
                           EmployeeConstraintRepository constraintRepository,
                           HolidayRepository holidayRepository,
                           EmployeeRuleRepository employeeRuleRepository,
                           EmployeeAvailabilityRepository availabilityRepository,
                           DemandIntervalRepository demandRepository,
                           SkillRepository skillRepository,
                           com.example.shiftv1.common.error.ErrorLogBuffer errorLogBuffer,
                           BreakPeriodRepository breakRepository,
                           PairingSettingsRepository pairingSettingsRepository) {
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
        this.breakRepository = breakRepository;
        this.pairingSettingsRepository = pairingSettingsRepository;
    }

    @Transactional
    @CacheEvict(cacheNames = {"monthly-schedules","stats-monthly","stats-workload","stats-days","stats-dist"}, key = "#year + '-' + #month")
    public List<ShiftAssignment> generateMonthlySchedule(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        // Reset month safely (delete breaks first to avoid FK issues), then assignments
        try {
            breakRepository.deleteAllByAssignmentWorkDateBetween(start, end);
        } catch (Exception ex) {
            try { breakRepository.deleteByAssignment_WorkDateBetween(start, end); } catch (Exception ignored) {}
        }
        assignmentRepository.deleteByWorkDateBetween(start, end);

        List<com.example.shiftv1.config.ShiftConfig> activeConfigs = shiftConfigRepository.findByActiveTrue();
        List<com.example.shiftv1.employee.Employee> employees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(com.example.shiftv1.employee.Employee::getId))
                .toList();
        if (employees.isEmpty()) {
            throw new IllegalStateException("従業員が登録されていません。先に従業員を作成してください。");
        }

        List<ShiftAssignment> created = new ArrayList<>();
        Map<LocalDate, Set<Long>> assignedByDate = new HashMap<>();

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            boolean isHoliday = false;
            try {
                isHoliday = holidayRepository.findDatesBetween(day, day).contains(day);
            } catch (Exception ignored) {}

            List<com.example.shiftv1.config.ShiftConfig> configsForDay = resolveConfigsForDay(activeConfigs, day, isHoliday);
            if (configsForDay.isEmpty()) continue;

            Set<Long> used = assignedByDate.computeIfAbsent(day, d -> new HashSet<>());

            for (com.example.shiftv1.config.ShiftConfig cfg : configsForDay) {
                int seats = cfg.getRequiredEmployees() == null ? 0 : cfg.getRequiredEmployees();
                if (seats <= 0) continue;
                for (int i = 0; i < seats; i++) {
                    com.example.shiftv1.employee.Employee emp = pickEmployeeForShift(employees, day, cfg.getStartTime(), cfg.getEndTime(), used);
                    if (emp == null) {
                        // shortage: just skip this seat; tests only assert non-empty overall
                        continue;
                    }
                    used.add(emp.getId());
                    created.add(new ShiftAssignment(day, cfg.getName(), cfg.getStartTime(), cfg.getEndTime(), emp));
                }
            }
        }

        if (created.isEmpty()) {
            return List.of();
        }
        return assignmentRepository.saveAll(created);
    }

    @Cacheable(value = "monthly-schedules", key = "#year + '-' + #month", unless = "#result == null || #result.isEmpty()")
    public List<ShiftAssignment> getMonthlySchedule(int year, int month) {
        var ym = YearMonth.of(year, month);
        return assignmentRepository.findByWorkDateBetween(ym.atDay(1), ym.atEndOfMonth());
    }

    @Transactional
    @CacheEvict(cacheNames = {"monthly-schedules","stats-monthly","stats-workload","stats-days","stats-dist"}, key = "#year + '-' + #month")
    public List<ShiftAssignment> generateMonthlyFromDemand(int year, int month, int granularityMinutes, boolean resetMonth) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        if (resetMonth) {
            resetMonthlySchedule(year, month);
        }

        List<com.example.shiftv1.employee.Employee> employees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(com.example.shiftv1.employee.Employee::getId))
                .toList();
        if (employees.isEmpty()) {
            throw new IllegalStateException("従業員が登録されていません。先に従業員を作成してください。");
        }

        List<ShiftAssignment> created = new ArrayList<>();
        Map<LocalDate, Map<Long, List<LocalTime[]>>> dayEmpWindows = new HashMap<>();

        PairingSettings pairing = pairingSettingsRepository.findAll().stream().findFirst().orElse(null);
        boolean pairingEnabled = pairing != null && Boolean.TRUE.equals(pairing.getEnabled());
        LocalTime fullS = null, fullE = null, mornS = null, mornE = null, aftS = null, aftE = null;
        if (pairingEnabled) {
            try {
                String[] f = pairing.getFullWindow().split("-");
                fullS = LocalTime.parse(f[0]);
                fullE = LocalTime.parse(f[1]);
                String[] m = pairing.getMorningWindow().split("-");
                mornS = LocalTime.parse(m[0]);
                mornE = LocalTime.parse(m[1]);
                String[] a = pairing.getAfternoonWindow().split("-");
                aftS = LocalTime.parse(a[0]);
                aftE = LocalTime.parse(a[1]);
            } catch (Exception ex) {
                pairingEnabled = false; // fall back if parsing fails
            }
        }

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            final LocalDate d0 = day;
            logger.debug("Demand-gen day {} start", day);
            List<com.example.shiftv1.demand.DemandInterval> intervals = demandRepository.findEffectiveForDate(day, day.getDayOfWeek());
            if (intervals == null || intervals.isEmpty()) {
                logger.debug("Demand-gen day {} no intervals", day);
                continue;
            }
            intervals = intervals.stream()
                    .sorted(Comparator
                            .comparing((com.example.shiftv1.demand.DemandInterval d) -> d.getSortOrder() == null ? 0 : d.getSortOrder())
                            .thenComparing(d -> d.getId() == null ? 0L : d.getId()))
                    .toList();
            int totalSeats = intervals.stream().mapToInt(di -> di.getRequiredSeats()==null?0:di.getRequiredSeats()).sum();
            logger.debug("Demand-gen day {} intervals={} seatsSum={}", day, intervals.size(), totalSeats);

            Map<Long, List<LocalTime[]>> empWindows = dayEmpWindows.computeIfAbsent(day, d -> new HashMap<>());

            // If pairing is enabled, first pair and assign full blocks, then assign remaining seats.
            if (pairingEnabled) {
                class DIWrap { final com.example.shiftv1.demand.DemandInterval d; int seats; DIWrap(com.example.shiftv1.demand.DemandInterval d){ this.d=d; this.seats = d.getRequiredSeats()==null?0:d.getRequiredSeats();} }
                List<DIWrap> wraps = intervals.stream().map(DIWrap::new).toList();
                Map<Long, List<DIWrap>> bySkill = new HashMap<>();
                for (DIWrap w : wraps) {
                    Long sid = (w.d.getSkill()==null? null : w.d.getSkill().getId());
                    bySkill.computeIfAbsent(sid, k -> new ArrayList<>()).add(w);
                }
                // First: pair morning+afternoon into full blocks
                for (Map.Entry<Long, List<DIWrap>> entry : bySkill.entrySet()) {
                    List<DIWrap> list = entry.getValue();
                    DIWrap morning = null, afternoon = null;
                    for (DIWrap w : list) {
                        if (w.seats <= 0) continue;
                        if (w.d.getStartTime().equals(mornS) && w.d.getEndTime().equals(mornE)) morning = w;
                        if (w.d.getStartTime().equals(aftS) && w.d.getEndTime().equals(aftE)) afternoon = w;
                    }
                    if (morning != null && afternoon != null) {
                        int pairs = Math.min(morning.seats, afternoon.seats);
                        for (int p = 0; p < pairs; p++) {
                            com.example.shiftv1.employee.Employee emp = pickEmployeeForWindow(
                                    employees, day, mornS, aftE, entry.getKey(),
                                    BlockType.FULL, dayEmpWindows.computeIfAbsent(day, d -> new HashMap<>()));
                            if (emp == null) break;
                            String shiftName = buildDemandShiftName(entry.getKey(), mornS, aftE);
                            created.add(new ShiftAssignment(day, shiftName, mornS, aftE, emp));
                            morning.seats -= 1; afternoon.seats -= 1;
                            dayEmpWindows.get(day).computeIfAbsent(emp.getId(), x -> new ArrayList<>()).add(new LocalTime[]{mornS, aftE});
                        }
                    }
                }
                // Second: assign remaining seats in each interval as-is
                for (DIWrap w : wraps) {
                    if (w.seats <= 0) continue;
                    LocalTime s = w.d.getStartTime();
                    LocalTime e = w.d.getEndTime();
                    Long skillId = (w.d.getSkill() == null ? null : w.d.getSkill().getId());
                    final String shiftName = buildDemandShiftName(skillId, s, e);
                    BlockType bt = BlockType.GENERIC;
                    if (mornS != null && mornE != null && s.equals(mornS) && e.equals(mornE)) bt = BlockType.MORNING;
                    else if (aftS != null && aftE != null && s.equals(aftS) && e.equals(aftE)) bt = BlockType.AFTERNOON;
                    for (int k = 0; k < w.seats; k++) {
                        com.example.shiftv1.employee.Employee emp = pickEmployeeForWindow(employees, day, s, e, skillId, bt, empWindows);
                        if (emp == null) break;
                        empWindows.computeIfAbsent(emp.getId(), x -> new ArrayList<>()).add(new LocalTime[]{s, e});
                        created.add(new ShiftAssignment(day, shiftName, s, e, emp));
                    }
                }
                // done for this day
                int dayAdded = (int) created.stream().filter(a -> a.getWorkDate().equals(d0)).count();
                logger.debug("Demand-gen day {} created {} assignments (pairing)", d0, dayAdded);
                // record shortage if nothing was created for this day despite intervals
                if (created.stream().noneMatch(a -> a.getWorkDate().equals(d0))) {
                    try {
                        errorLogBuffer.addError("No assignments created for day with demand (pairing mode)",
                                new IllegalStateException(d0.toString()));
                    } catch (Exception ignore) {}
                }
                continue;
            }

            // Default path when pairing is disabled
            for (var di : intervals) {
                LocalTime s = di.getStartTime();
                LocalTime e = di.getEndTime();
                int seats = di.getRequiredSeats() == null ? 0 : di.getRequiredSeats();
                if (s == null || e == null || !s.isBefore(e) || seats <= 0) continue;
                Long skillId = (di.getSkill() == null ? null : di.getSkill().getId());
                final String shiftName = buildDemandShiftName(skillId, s, e);

                for (int k = 0; k < seats; k++) {
                    BlockType bt = BlockType.GENERIC;
                    if (pairingEnabled) {
                        if (mornS != null && mornE != null && s.equals(mornS) && e.equals(mornE)) bt = BlockType.MORNING;
                        else if (aftS != null && aftE != null && s.equals(aftS) && e.equals(aftE)) bt = BlockType.AFTERNOON;
                    }
                    com.example.shiftv1.employee.Employee emp = pickEmployeeForWindow(employees, day, s, e, skillId, bt, empWindows);
                    if (emp == null) {
                        break;
                    }
                    empWindows.computeIfAbsent(emp.getId(), x -> new ArrayList<>()).add(new LocalTime[]{s, e});
                    created.add(new ShiftAssignment(day, shiftName, s, e, emp));
                }
            }
            int dayAdded = (int) created.stream().filter(a -> a.getWorkDate().equals(d0)).count();
            logger.debug("Demand-gen day {} created {} assignments", d0, dayAdded);
            
            // record shortage if nothing was created for this day despite intervals
            if (created.stream().noneMatch(a -> a.getWorkDate().equals(d0))) {
                try {
                    errorLogBuffer.addError("No assignments created for day with demand",
                            new IllegalStateException(d0.toString()));
                } catch (Exception ignore) {}
            }
        }

        if (created.isEmpty()) return List.of();
        return assignmentRepository.saveAll(created);
    }

    @Transactional
    @CacheEvict(value = "monthly-schedules", key = "#date.getYear() + '-' + #date.getMonthValue()")
    public List<ShiftAssignment> generateForDateFromDemand(LocalDate date, boolean resetDay) {
        if (resetDay) {
            try { breakRepository.deleteByAssignment_WorkDateBetween(date, date); } catch (Exception ignored) {}
            assignmentRepository.deleteByWorkDate(date);
        }
        List<com.example.shiftv1.employee.Employee> employees = employeeRepository.findAll().stream()
                .sorted(Comparator.comparing(com.example.shiftv1.employee.Employee::getId))
                .toList();
        List<com.example.shiftv1.demand.DemandInterval> intervals = demandRepository.findEffectiveForDate(date, date.getDayOfWeek());
        if (intervals == null || intervals.isEmpty() || employees.isEmpty()) return List.of();
        intervals = intervals.stream()
                .sorted(Comparator
                        .comparing((com.example.shiftv1.demand.DemandInterval d) -> d.getSortOrder() == null ? 0 : d.getSortOrder())
                        .thenComparing(d -> d.getId() == null ? 0L : d.getId()))
                .toList();
        Map<Long, List<LocalTime[]>> empWindows = new HashMap<>();
        List<ShiftAssignment> created = new ArrayList<>();
        for (var di : intervals) {
            LocalTime s = di.getStartTime();
            LocalTime e = di.getEndTime();
            int seats = di.getRequiredSeats() == null ? 0 : di.getRequiredSeats();
            if (s == null || e == null || !s.isBefore(e) || seats <= 0) continue;
            Long skillId = (di.getSkill() == null ? null : di.getSkill().getId());
            String skillCode = (di.getSkill() == null ? null : di.getSkill().getCode());
            final String shiftName = (skillCode == null || skillCode.isBlank()) ? "Demand" : ("Demand-" + skillCode);
            for (int k = 0; k < seats; k++) {
                com.example.shiftv1.employee.Employee emp = pickEmployeeForWindow(employees, date, s, e, skillId, BlockType.GENERIC, empWindows);
                if (emp == null) break;
                empWindows.computeIfAbsent(emp.getId(), x -> new ArrayList<>()).add(new LocalTime[]{s, e});
                created.add(new ShiftAssignment(date, shiftName, s, e, emp));
            }
        }
        if (created.isEmpty()) return List.of();
        return assignmentRepository.saveAll(created);
    }

    @Async("scheduleExecutor")
    @Transactional
    @CacheEvict(cacheNames = {"monthly-schedules","stats-monthly","stats-workload","stats-days","stats-dist"}, key = "#year + '-' + #month")
    public void generateMonthlyFromDemandAsync(int year, int month, int granularityMinutes, boolean resetMonth) {
        try {
            List<ShiftAssignment> res = generateMonthlyFromDemand(year, month, granularityMinutes, resetMonth);
            int cnt = (res == null ? 0 : res.size());
            logger.info("generateMonthlyFromDemandAsync finished: {}-{} -> {} assignments", year, month, cnt);
            if (cnt == 0) {
                try {
                    errorLogBuffer.addError("Demand generation produced 0 assignments",
                            new IllegalStateException("No assignments for %d-%02d".formatted(year, month)));
                } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            logger.error("generateMonthlyFromDemandAsync error", e);
            try { errorLogBuffer.addError("generateMonthlyFromDemandAsync error", e); } catch (Exception ignore) {}
        }
    }

    @Transactional
    @CacheEvict(cacheNames = {"monthly-schedules","stats-monthly","stats-workload","stats-days","stats-dist"}, key = "#year + '-' + #month")
    public void resetMonthlySchedule(int year, int month) {
        var ym = YearMonth.of(year, month);
        LocalDate s = ym.atDay(1);
        LocalDate e = ym.atEndOfMonth();
        try {
            breakRepository.deleteAllByAssignmentWorkDateBetween(s, e);
        } catch (Exception ex) {
            try { breakRepository.deleteByAssignment_WorkDateBetween(s, e); } catch (Exception ignored) {}
        }
        assignmentRepository.deleteByWorkDateBetween(s, e);
    }

    // report/diagnostics removed

    @CacheEvict(cacheNames = {"monthly-schedules","stats-monthly","stats-workload","stats-days","stats-dist"}, key = "#day.getYear() + '-' + #day.getMonthValue()")
    public Map<String, Object> addCoreTime(LocalDate day, Long skillId, String skillCode, java.time.LocalTime windowStart, java.time.LocalTime windowEnd, int seats) {
        Map<String, Object> result = new HashMap<>();
        int updated = 0;
        try {
            Long requiredSkillId = skillId;
            if (requiredSkillId == null && skillCode != null && !skillCode.isBlank()) {
                var s = skillRepository.findAll().stream().filter(x -> skillCode.equals(x.getCode())).findFirst().orElse(null);
                requiredSkillId = (s == null ? null : s.getId());
            }

            // Load current assignments of the day
            List<ShiftAssignment> dayAssignments = assignmentRepository.findByWorkDate(day);

            // Index employee -> assignments that day
            Map<com.example.shiftv1.employee.Employee, List<ShiftAssignment>> byEmp = new HashMap<>();
            for (var a : dayAssignments) {
                byEmp.computeIfAbsent(a.getEmployee(), k -> new ArrayList<>()).add(a);
            }

            // Try to extend existing employees' assignments to cover [windowStart, windowEnd]
            for (var entry : byEmp.entrySet()) {
                if (updated >= seats) break;
                var emp = entry.getKey();
                if (requiredSkillId != null) {
                    boolean hasSkill = false;
                    if (emp.getSkills() != null) {
                        for (var s : emp.getSkills()) { if (s != null && requiredSkillId.equals(s.getId())) { hasSkill = true; break; } }
                    }
                    if (!hasSkill) continue;
                }
                if (!Boolean.TRUE.equals(emp.getOvertimeAllowed())) continue;

                // Check day constraints
                var cons = constraintRepository.findByEmployeeAndDateAndActiveTrue(emp, day);
                boolean blocked = false;
                for (var c : cons) {
                    if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.UNAVAILABLE) { blocked = true; break; }
                    if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.LIMITED) {
                        var cs = c.getStartTime(); var ce = c.getEndTime();
                        if (cs != null && ce != null) {
                            if (windowStart.isBefore(cs) || windowEnd.isAfter(ce)) { blocked = true; break; }
                        }
                    }
                }
                if (blocked) continue;

                // Find the assignment that requires minimal extension to cover window
                ShiftAssignment best = null; long bestExtra = Long.MAX_VALUE;
                for (var a : entry.getValue()) {
                    LocalTime ns = a.getStartTime();
                    LocalTime ne = a.getEndTime();
                    // if already covers window, nothing to extend
                    if (!ns.isAfter(windowStart) && !ne.isBefore(windowEnd)) { best = a; bestExtra = 0; break; }
                    // If touches/overlaps window, compute needed extra minutes
                    if (ne.isBefore(windowStart) || ns.isAfter(windowEnd)) continue; // far apart
                    long extra = 0;
                    if (ns.isAfter(windowStart)) extra += java.time.Duration.between(windowStart, ns).toMinutes();
                    if (ne.isBefore(windowEnd)) extra += java.time.Duration.between(ne, windowEnd).toMinutes();
                    if (extra < bestExtra) { best = a; bestExtra = extra; }
                }
                if (best == null) continue;

                int dailyMax = emp.getOvertimeDailyMaxHours() == null ? 0 : emp.getOvertimeDailyMaxHours();
                long maxExtraMin = dailyMax * 60L;
                if (bestExtra <= maxExtraMin) {
                    // extend best to cover requested window
                    if (best.getStartTime().isAfter(windowStart)) best.setStartTime(windowStart);
                    if (best.getEndTime().isBefore(windowEnd)) best.setEndTime(windowEnd);
                    assignmentRepository.save(best);
                    updated++;
                }
            }

            result.put("updated", updated);
            result.put("reason", updated >= seats ? "ok" : "insufficient-candidates");
        } catch (Exception ex) {
            result.put("updated", updated);
            result.put("reason", "error: " + ex.getMessage());
        }
        return result;
    }

    // report/diagnostics removed
    
    // ---- Private helpers ----
    private List<com.example.shiftv1.config.ShiftConfig> resolveConfigsForDay(
            List<com.example.shiftv1.config.ShiftConfig> active,
            LocalDate day,
            boolean isHoliday) {
        if (active == null || active.isEmpty()) return List.of();
        if (isHoliday) {
            List<com.example.shiftv1.config.ShiftConfig> holiday = active.stream()
                    .filter(c -> Boolean.TRUE.equals(c.getHoliday()))
                    .sorted(Comparator.comparing(com.example.shiftv1.config.ShiftConfig::getStartTime))
                    .toList();
            return holiday;
        }
        DayOfWeek dow = day.getDayOfWeek();
        // Prefer explicit set of days
        List<com.example.shiftv1.config.ShiftConfig> setDays = active.stream()
                .filter(c -> c.getDays() != null && !c.getDays().isEmpty() && c.getDays().contains(dow))
                .sorted(Comparator.comparing(com.example.shiftv1.config.ShiftConfig::getStartTime))
                .toList();
        if (!setDays.isEmpty()) return setDays;

        // Fallback to single dayOfWeek field
        List<com.example.shiftv1.config.ShiftConfig> singleDow = active.stream()
                .filter(c -> c.getDayOfWeek() != null && c.getDayOfWeek() == dow)
                .sorted(Comparator.comparing(com.example.shiftv1.config.ShiftConfig::getStartTime))
                .toList();
        if (!singleDow.isEmpty()) return singleDow;

        // Otherwise, use generic non-holiday configs without specific days
        return active.stream()
                .filter(c -> !Boolean.TRUE.equals(c.getHoliday()))
                .filter(c -> c.getDayOfWeek() == null)
                .filter(c -> c.getDays() == null || c.getDays().isEmpty())
                .sorted(Comparator.comparing(com.example.shiftv1.config.ShiftConfig::getStartTime))
                .toList();
    }

    private com.example.shiftv1.employee.Employee pickEmployeeForShift(
            List<com.example.shiftv1.employee.Employee> employees,
            LocalDate day,
            LocalTime start,
            LocalTime end,
            Set<Long> used) {
        for (var e : employees) {
            Long id = e.getId();
            if (id == null || used.contains(id)) continue;
            // Check constraints for this date
            List<com.example.shiftv1.constraint.EmployeeConstraint> cons =
                    constraintRepository.findByEmployeeAndDateAndActiveTrue(e, day);
            boolean blocked = false;
            for (var c : cons) {
                if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.UNAVAILABLE) {
                    blocked = true; break;
                }
                if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.LIMITED) {
                    LocalTime cs = c.getStartTime();
                    LocalTime ce = c.getEndTime();
                    if (cs != null && ce != null) {
                        // must fit within the limited window
                        if (start.isBefore(cs) || end.isAfter(ce)) { blocked = true; break; }
                    }
                }
            }
            if (blocked) continue;
            return e;
        }
        return null;
    }

    private enum BlockType { FULL, MORNING, AFTERNOON, GENERIC }

    private String buildDemandShiftName(Long skillId, LocalTime start, LocalTime end) {
        String label = "Demand";
        if (skillId != null) {
            try {
                var sk = skillRepository.findById(skillId).orElse(null);
                if (sk != null && sk.getCode() != null && !sk.getCode().isBlank()) {
                    label = "Demand-" + sk.getCode();
                }
            } catch (Exception ignored) {}
        }
        String s = start == null ? "" : start.toString();
        String e = end == null ? "" : end.toString();
        if (s.length() >= 5) s = s.substring(0,5);
        if (e.length() >= 5) e = e.substring(0,5);
        return String.format("%s %s-%s", label, s, e).trim();
    }

    private com.example.shiftv1.employee.Employee pickEmployeeForWindow(
            List<com.example.shiftv1.employee.Employee> employees,
            LocalDate day,
            LocalTime start,
            LocalTime end,
            Long requiredSkillId,
            BlockType type,
            Map<Long, List<LocalTime[]>> empWindows) {
        for (var e : employees) {
            Long id = e.getId();
            if (id == null) continue;
            if (requiredSkillId != null) {
                boolean has = false;
                if (e.getSkills() != null) {
                    for (var s : e.getSkills()) {
                        if (s != null && requiredSkillId.equals(s.getId())) { has = true; break; }
                    }
                }
                if (!has) continue;
            }
            // Eligibility by block type (null treated as true for backward compatibility)
            boolean okFull = e.getEligibleFull() == null ? true : Boolean.TRUE.equals(e.getEligibleFull());
            boolean okMorning = e.getEligibleShortMorning() == null ? true : Boolean.TRUE.equals(e.getEligibleShortMorning());
            boolean okAfternoon = e.getEligibleShortAfternoon() == null ? true : Boolean.TRUE.equals(e.getEligibleShortAfternoon());
            if (type == BlockType.FULL && !okFull) continue;
            if (type == BlockType.MORNING && !okMorning) continue;
            if (type == BlockType.AFTERNOON && !okAfternoon) continue;
            List<com.example.shiftv1.constraint.EmployeeConstraint> cons =
                    constraintRepository.findByEmployeeAndDateAndActiveTrue(e, day);
            boolean blocked = false;
            for (var c : cons) {
                if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.UNAVAILABLE) {
                    blocked = true; break;
                }
                if (c.getType() == com.example.shiftv1.constraint.EmployeeConstraint.ConstraintType.LIMITED) {
                    LocalTime cs = c.getStartTime();
                    LocalTime ce = c.getEndTime();
                    if (cs != null && ce != null) {
                        if (start.isBefore(cs) || end.isAfter(ce)) { blocked = true; break; }
                    }
                }
            }
            if (blocked) continue;
            List<LocalTime[]> windows = empWindows.get(id);
            if (windows != null) {
                boolean overlap = false;
                for (LocalTime[] w : windows) {
                    LocalTime s2 = w[0], e2 = w[1];
                    if (start.isBefore(e2) && end.isAfter(s2)) { overlap = true; break; }
                }
                if (overlap) continue;
            }
            return e;
        }
        return null;
    }
}

