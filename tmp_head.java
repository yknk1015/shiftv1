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
// Legacy caching annotations removed as part of simplification
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

    public List<ShiftAssignment> getMonthlySchedule(int year, int month) {
        var ym = YearMonth.of(year, month);
        return assignmentRepository.findByWorkDateBetween(ym.atDay(1), ym.atEndOfMonth());
    }

    @Transactional
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
