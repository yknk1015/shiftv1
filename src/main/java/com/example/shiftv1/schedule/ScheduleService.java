package com.example.shiftv1.schedule;

import com.example.shiftv1.demand.DemandInterval;
import com.example.shiftv1.demand.DemandIntervalRepository;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.employee.EmployeeRuleRepository;
import com.example.shiftv1.employee.EmployeeRule;
import com.example.shiftv1.leave.LeaveBalance;
import com.example.shiftv1.leave.LeaveBalanceRepository;
import com.example.shiftv1.leave.LeaveRequest;
import com.example.shiftv1.leave.LeaveRequestRepository;
import com.example.shiftv1.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import com.example.shiftv1.holiday.HolidayRepository;
import com.example.shiftv1.config.FreePlaceholderSettings;
import com.example.shiftv1.constraint.EmployeeConstraint;
import com.example.shiftv1.constraint.EmployeeConstraintRepository;
import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;

@Service
public class ScheduleService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);
    private static final int GRID_RANGE_LIMIT_DAYS = 62;

    private final EmployeeRepository employeeRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final DemandIntervalRepository demandRepository;
    private final SkillRepository skillRepository;
    private final EmployeeRuleRepository employeeRuleRepository;
    private final HolidayRepository holidayRepository;
    private final FreePlaceholderSettings freeSettings;
    private final EmployeeConstraintRepository constraintRepository;
    private final ScheduleJobStatusService jobStatusService;
    private final ShiftReservationRepository reservationRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    @Value("${shift.placeholder.free.start:00:00}")
    private String cfgFreeStart;
    @Value("${shift.placeholder.free.end:00:05}")
    private String cfgFreeEnd;
    @Value("${shift.placeholder.off.start:00:00}")
    private String cfgOffStart;
    @Value("${shift.placeholder.off.end:00:05}")
    private String cfgOffEnd;

    // Deprecated: now taken from FreePlaceholderSettings, kept for defaults
    @Value("${shift.placeholder.free.onlyWeekdays:false}")
    private boolean cfgFreeOnlyWeekdays;
    @Value("${shift.placeholder.free.skipHolidays:false}")
    private boolean cfgFreeSkipHolidays;

    public ScheduleService(EmployeeRepository employeeRepository,
            ShiftAssignmentRepository assignmentRepository,
            DemandIntervalRepository demandRepository,
            SkillRepository skillRepository,
            EmployeeRuleRepository employeeRuleRepository,
            HolidayRepository holidayRepository,
            FreePlaceholderSettings freeSettings,
            EmployeeConstraintRepository constraintRepository,
            ScheduleJobStatusService jobStatusService,
            ShiftReservationRepository reservationRepository,
            LeaveBalanceRepository leaveBalanceRepository,
            LeaveRequestRepository leaveRequestRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.demandRepository = demandRepository;
        this.skillRepository = skillRepository;
        this.employeeRuleRepository = employeeRuleRepository;
        this.holidayRepository = holidayRepository;
        this.freeSettings = freeSettings;
        this.constraintRepository = constraintRepository;
        this.jobStatusService = jobStatusService;
        this.reservationRepository = reservationRepository;
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.leaveRequestRepository = leaveRequestRepository;
    }

    // Legacy wrapper used by older endpoint
    @Transactional
    public List<ShiftAssignment> generateMonthlySchedule(int year, int month) {
        return generateMonthlyFromDemandSimple(year, month, false);
    }

    // Lightweight generator with skill priority + reservation and per-slot caps
    @Transactional
    public List<ShiftAssignment> generateMonthlyFromDemandSimple(int year, int month, boolean resetMonth) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        if (resetMonth)
            assignmentRepository.deleteByWorkDateBetween(start, end);
        List<Employee> employees = employeeRepository.findAll();
        if (employees.isEmpty())
            return Collections.emptyList();

        int rotate = 0;
        List<ShiftAssignment> createdAll = new ArrayList<>();
        int granularity = 60;

        long baselineCount = 0;
        try {
            baselineCount = assignmentRepository.countByWorkDateBetween(start, end);
        } catch (Exception ignore) {
        }
        try {
            jobStatusService.start(year, month, baselineCount);
        } catch (Exception ignore) {
        }

        // Fairness counters (month-to-date): real worked days per employee
        Map<Long, Integer> mtdTotalWorkedDays = new HashMap<>();
        Map<Long, Integer> mtdWeekendHolidayWorkedDays = new HashMap<>();
        // Preload rules once to avoid per-employee DB hits
        Map<Long, EmployeeRule> rulesByEmp = loadRulesByEmployee(employees);

        Map<LocalDate, List<ShiftReservation>> reservationsByDate = reservationRepository
                .findByWorkDateBetweenAndStatusIn(start, end, List.of(ShiftReservation.Status.PENDING))
                .stream()
                .collect(Collectors.groupingBy(ShiftReservation::getWorkDate));

        // Preload per-employee allowed working days per week (7 - weeklyRestDays)
        Map<Long, Integer> allowedWorkDaysPerWeek = new HashMap<>();
        for (var emp : employees) {
            int rest = Optional.ofNullable(rulesByEmp.get(emp.getId()))
                    .map(EmployeeRule::getWeeklyRestDays)
                    .filter(v -> v != null && v >= 0)
                    .orElse(2);
            int allowed = Math.max(0, 7 - rest);
            allowedWorkDaysPerWeek.put(emp.getId(), allowed);
        }
        // Track worked days (real assignments only) per employee across spillover weeks
        // (Sun..Sat)
        LocalDate outerStart = weekStartSunday(start);
        LocalDate outerEnd = weekStartSunday(end).plusDays(6);
        Map<Long, Set<LocalDate>> workedDaysByEmployee = new HashMap<>();
        for (ShiftAssignment sa : assignmentRepository.findByWorkDateBetween(outerStart, outerEnd)) {
            boolean isFree = Boolean.TRUE.equals(sa.getIsFree())
                    || (sa.getShiftName() != null && "FREE".equalsIgnoreCase(sa.getShiftName()));
            boolean isOff = Boolean.TRUE.equals(sa.getIsOff()) || (sa.getShiftName() != null
                    && ("休日".equals(sa.getShiftName()) || "OFF".equalsIgnoreCase(sa.getShiftName())));
            boolean isLeave = Boolean.TRUE.equals(sa.getIsLeave());
            if (isFree || isOff || isLeave)
                continue;
            Long empId = sa.getEmployee() != null ? sa.getEmployee().getId() : null;
            if (empId == null)
                continue;
            workedDaysByEmployee.computeIfAbsent(empId, k -> new HashSet<>()).add(sa.getWorkDate());
        }
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            final boolean isWkHol = isWeekendOrHoliday(day);
            // Track real assignments counted once per day per employee
            Set<Long> assignedToday = new HashSet<>();
            DayContext dayCtx = buildDayContext(day, employees, rulesByEmp);
            boolean isHoliday = isHoliday(day);
            List<DemandInterval> demands = demandRepository.findEffectiveForDate(day, day.getDayOfWeek(), isHoliday);
            // Process skill-specific first (by higher priority), then generic
            demands.sort((a, b) -> {
                boolean aGeneric = (a.getSkill() == null);
                boolean bGeneric = (b.getSkill() == null);
                if (aGeneric != bGeneric)
                    return aGeneric ? 1 : -1; // skill first
                int ap = (a.getSkill() != null && a.getSkill().getPriority() != null) ? a.getSkill().getPriority() : 0;
                int bp = (b.getSkill() != null && b.getSkill().getPriority() != null) ? b.getSkill().getPriority() : 0;
                if (ap != bp)
                    return Integer.compare(bp, ap); // higher first
                int c1 = a.getStartTime().compareTo(b.getStartTime());
                if (c1 != 0)
                    return c1;
                return a.getEndTime().compareTo(b.getEndTime());
            });

            List<LocalTime> slots = buildSlots(granularity);
            Map<LocalTime, Integer> requiredBySlot = new HashMap<>();
            Map<LocalTime, Integer> reservedSkillBySlot = new HashMap<>(); // remaining seats reserved for
                                                                           // skill-specific
            for (LocalTime t : slots) {
                requiredBySlot.put(t, 0);
                reservedSkillBySlot.put(t, 0);
            }
            for (DemandInterval d : demands) {
                int seats = Optional.ofNullable(d.getRequiredSeats()).orElse(0);
                if (seats <= 0)
                    continue;
                List<LocalTime> cov = slotsCoveredBy(d.getStartTime(), d.getEndTime(), granularity);
                for (LocalTime t : cov) {
                    requiredBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
                    if (d.getSkill() != null)
                        reservedSkillBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
                }
            }
            // Build demanded skill IDs per slot for reservation by employee
            Map<LocalTime, Set<Long>> demandedSkillIdsBySlot = new HashMap<>();
            for (LocalTime t : slots)
                demandedSkillIdsBySlot.put(t, new HashSet<>());
            for (DemandInterval d : demands) {
                if (d.getSkill() == null)
                    continue;
                Long sid = d.getSkill().getId();
                if (sid == null)
                    continue;
                for (LocalTime t : slotsCoveredBy(d.getStartTime(), d.getEndTime(), granularity)) {
                    demandedSkillIdsBySlot.get(t).add(sid);
                }
            }

            Map<LocalTime, Integer> assignedBySlot = new HashMap<>();
            for (LocalTime t : slots)
                assignedBySlot.put(t, 0);

            List<ShiftReservation> dayReservations = reservationsByDate.getOrDefault(day, Collections.emptyList());
            List<ShiftAssignment> reservationAssignments = applyReservationsForDay(
                    day,
                    dayReservations,
                    dayCtx,
                    assignedBySlot,
                    reservedSkillBySlot,
                    granularity,
                    workedDaysByEmployee,
                    assignedToday,
                    mtdTotalWorkedDays,
                    mtdWeekendHolidayWorkedDays,
                    isWkHol);
            if (!reservationAssignments.isEmpty()) {
                createdAll.addAll(reservationAssignments);
                rotate += reservationAssignments.size();
            }

            for (DemandInterval d : demands) {
                Integer seatsObj = d.getRequiredSeats();
                if (seatsObj == null || seatsObj <= 0)
                    continue;
                LocalTime s = d.getStartTime();
                LocalTime e = d.getEndTime();
                Skill needSkill = d.getSkill();
                String code = needSkill != null ? safeCode(needSkill.getCode()) : "A";
                String label = String.format("Demand-%s %s-%s", code, timeLabel(s), timeLabel(e));

                List<Employee> avail = assignmentRepository.findAvailableEmployeesForTimeSlot(day, s, e)
                        .stream()
                        .filter(emp -> !dayCtx.excludeByPatternStrict.getOrDefault(emp.getId(), false))
                        .filter(emp -> !dayCtx.hardUnavailable.getOrDefault(emp.getId(), false))
                        .toList();
                if (needSkill != null) {
                    Long sid = needSkill.getId();
                    avail = avail.stream()
                            .filter(emp -> emp.getSkills() != null
                                    && emp.getSkills().stream().anyMatch(sk -> Objects.equals(sk.getId(), sid)))
                            .toList();
                }
                if (avail.isEmpty())
                    continue;

                // For generic demand, preserve skilled employees if critically needed in these
                // slots
                if (needSkill == null) {
                    List<LocalTime> covers = slotsCoveredBy(s, e, granularity);
                    Map<LocalTime, Integer> skilledAvailCount = new HashMap<>();
                    for (LocalTime t : covers)
                        skilledAvailCount.put(t, 0);
                    for (Employee emp : avail) {
                        for (LocalTime t : covers) {
                            Set<Long> ds = demandedSkillIdsBySlot.getOrDefault(t, Collections.emptySet());
                            boolean empSkilled = emp.getSkills() != null
                                    && emp.getSkills().stream().anyMatch(sk -> ds.contains(sk.getId()));
                            if (empSkilled)
                                skilledAvailCount.compute(t, (k, v) -> (v == null ? 0 : v) + 1);
                        }
                    }
                    List<Employee> filtered = new ArrayList<>();
                    for (Employee emp : avail) {
                        boolean reserved = false;
                        for (LocalTime t : covers) {
                            int reservedSeats = reservedSkillBySlot.getOrDefault(t, 0);
                            if (reservedSeats <= 0)
                                continue;
                            Set<Long> ds = demandedSkillIdsBySlot.getOrDefault(t, Collections.emptySet());
                            boolean empSkilled = emp.getSkills() != null
                                    && emp.getSkills().stream().anyMatch(sk -> ds.contains(sk.getId()));
                            if (empSkilled) {
                                int skilledCount = skilledAvailCount.getOrDefault(t, 0);
                                if (skilledCount <= reservedSeats) {
                                    reserved = true;
                                    break;
                                }
                            }
                        }
                        if (!reserved)
                            filtered.add(emp);
                    }
                    if (!filtered.isEmpty())
                        avail = filtered; // fallback to original if empty
                }

                // Enforce weekly rest-days: in a Sunday-Saturday window, limit distinct
                // workdays to allowed
                final LocalDate weekStart = weekStartSunday(day);
                final LocalDate weekEnd = weekStart.plusDays(6);
                List<Employee> weeklyOk = new ArrayList<>();
                for (Employee emp : avail) {
                    Long empId = emp.getId();
                    int allowedDays = allowedWorkDaysPerWeek.getOrDefault(empId, 5);
                    Set<LocalDate> days = workedDaysByEmployee.getOrDefault(empId, Collections.emptySet());
                    int used = 0;
                    for (LocalDate d0 : days) {
                        if (!d0.isBefore(weekStart) && !d0.isAfter(weekEnd))
                            used++;
                    }
                    boolean alreadyToday = days.contains(day);
                    if (used < allowedDays || alreadyToday) {
                        weeklyOk.add(emp);
                    }
                }
                if (weeklyOk.isEmpty())
                    continue;
                avail = weeklyOk;

                List<Employee> rotated = new ArrayList<>(avail);
                if (!rotated.isEmpty()) {
                    int r = rotated.size() == 0 ? 0 : (rotate % rotated.size());
                    if (r != 0)
                        Collections.rotate(rotated, -r);
                    // Fairness sort: prioritize employees with fewer MTD worked days
                    Map<Long, Integer> pos = new HashMap<>();
                    for (int i = 0; i < rotated.size(); i++)
                        pos.put(rotated.get(i).getId(), i);
                    rotated.sort((aEmp, bEmp) -> {
                        long aScore = fairnessScore(aEmp.getId(), isWkHol, mtdTotalWorkedDays,
                                mtdWeekendHolidayWorkedDays, assignedToday, pos);
                        long bScore = fairnessScore(bEmp.getId(), isWkHol, mtdTotalWorkedDays,
                                mtdWeekendHolidayWorkedDays, assignedToday, pos);
                        if (dayCtx.softUnavailable.getOrDefault(aEmp.getId(), false))
                            aScore += 5_000L;
                        if (dayCtx.preferred.getOrDefault(aEmp.getId(), false))
                            aScore -= 100L;
                        if (dayCtx.softUnavailable.getOrDefault(bEmp.getId(), false))
                            bScore += 5_000L;
                        if (dayCtx.preferred.getOrDefault(bEmp.getId(), false))
                            bScore -= 100L;
                        return Long.compare(aScore, bScore);
                    });
                }
                List<LocalTime> covers = slotsCoveredBy(s, e, granularity);
                int newly = 0;
                int seats = seatsObj;
                for (Employee emp : rotated) {
                    if (newly >= seats)
                        break;
                    boolean fits = true;
                    for (LocalTime t : covers) {
                        int req = requiredBySlot.getOrDefault(t, 0);
                        int asn = assignedBySlot.getOrDefault(t, 0);
                        int reserved = reservedSkillBySlot.getOrDefault(t, 0);
                        int cap = (needSkill == null) ? Math.max(0, req - reserved) : req;
                        if (asn >= cap) {
                            fits = false;
                            break;
                        }
                    }
                    if (!fits)
                        continue;
                    // avail は既に空きのため再照会しない
                    ShiftAssignment a = new ShiftAssignment(day, label, s, e, emp);
                    assignmentRepository.save(a);
                    createdAll.add(a);
                    // Mark today's date as a worked day for weekly limit counting (once per
                    // employee)
                    workedDaysByEmployee.computeIfAbsent(emp.getId(), k -> new HashSet<>()).add(day);
                    // Fairness: count once per day per employee
                    Long empId = emp.getId();
                    if (assignedToday.add(empId)) {
                        mtdTotalWorkedDays.merge(empId, 1, Integer::sum);
                        if (isWkHol)
                            mtdWeekendHolidayWorkedDays.merge(empId, 1, Integer::sum);
                    }
                    newly++;
                    for (LocalTime t : covers) {
                        assignedBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + 1);
                        if (needSkill != null)
                            reservedSkillBySlot.compute(t, (k, v) -> Math.max(0, (v == null ? 0 : v) - 1));
                    }
                }
                rotate += newly;
            }
            try {
                jobStatusService.updateCount(year, month, baselineCount + createdAll.size());
            } catch (Exception ignore) {
            }
        }

        ensurePatternOffPlaceholders(year, month);
        ensureWeeklyHolidays(year, month);
        ensureFreePlaceholders(year, month);
        try {
            jobStatusService.finish(year, month, baselineCount + createdAll.size());
        } catch (Exception ignore) {
        }
        logger.info("generateMonthlyFromDemandSimple finished: {}-{} -> {} assignments", year, month,
                createdAll.size());
        return createdAll;
    }

    private LocalDate weekStartSunday(LocalDate d) {
        int dow = d.getDayOfWeek().getValue() % 7; // Sunday=0
        return d.minusDays(dow);
    }

    @Transactional
    public List<ShiftAssignment> generateMonthlyFromDemand(int year, int month, int granularityMinutes,
            boolean resetMonth) {
        return generateMonthlyFromDemandSimple(year, month, resetMonth);
    }

    @Async("scheduleExecutor")
    @Transactional
    public void generateMonthlyFromDemandAsync(int year, int month, int granularityMinutes, boolean resetMonth) {
        generateMonthlyFromDemandSimple(year, month, resetMonth);
    }

    @Transactional
    public List<ShiftAssignment> generateForDateFromDemand(LocalDate date, boolean resetDay) {
        if (resetDay)
            assignmentRepository.deleteByWorkDate(date);
        boolean isHoliday = isHoliday(date);
        List<DemandInterval> demands = demandRepository.findEffectiveForDate(date, date.getDayOfWeek(), isHoliday);
        List<Employee> employees = employeeRepository.findAll();
        if (demands.isEmpty() || employees.isEmpty())
            return Collections.emptyList();

        // Fairness: build month-to-date counters from existing assignments before this
        // date
        YearMonth ym = YearMonth.of(date.getYear(), date.getMonthValue());
        LocalDate monthStart = ym.atDay(1);
        LocalDate prev = date.minusDays(1);
        Map<Long, Integer> mtdTotalWorkedDays = new HashMap<>();
        Map<Long, Integer> mtdWeekendHolidayWorkedDays = new HashMap<>();
        if (!prev.isBefore(monthStart)) {
            for (ShiftAssignment sa : assignmentRepository.findByWorkDateBetween(monthStart, prev)) {
                boolean isFree = Boolean.TRUE.equals(sa.getIsFree())
                        || (sa.getShiftName() != null && "FREE".equalsIgnoreCase(sa.getShiftName()));
                boolean isOff = Boolean.TRUE.equals(sa.getIsOff()) || (sa.getShiftName() != null
                        && ("休日".equals(sa.getShiftName()) || "OFF".equalsIgnoreCase(sa.getShiftName())));
                boolean isLeave = Boolean.TRUE.equals(sa.getIsLeave());
                if (isFree || isOff || isLeave)
                    continue;
                Long empId = sa.getEmployee() != null ? sa.getEmployee().getId() : null;
                if (empId == null)
                    continue;
                mtdTotalWorkedDays.merge(empId, 1, Integer::sum);
                if (isWeekendOrHoliday(sa.getWorkDate()))
                    mtdWeekendHolidayWorkedDays.merge(empId, 1, Integer::sum);
            }
        }
        final boolean isWkHol = isWeekendOrHoliday(date);
        Set<Long> assignedToday = new HashSet<>();
        Map<Long, Set<LocalDate>> workedDaysByEmployee = new HashMap<>();
        // Preload rules/constraints and build per-day context
        Map<Long, EmployeeRule> rulesByEmp = loadRulesByEmployee(employees);
        DayContext dayCtx = buildDayContext(date, employees, rulesByEmp);
        List<ShiftReservation> dayReservations = reservationRepository
                .findByWorkDateBetweenAndStatusIn(date, date, List.of(ShiftReservation.Status.PENDING));

        List<ShiftAssignment> created = new ArrayList<>();
        int rotate = 0;
        int granularity = 60;
        long baselineCount = 0L;
        try {
            baselineCount = assignmentRepository.countByWorkDateBetween(monthStart, ym.atEndOfMonth());
        } catch (Exception ignore) {
        }
        demands.sort((a, b) -> {
            boolean aGeneric = (a.getSkill() == null);
            boolean bGeneric = (b.getSkill() == null);
            if (aGeneric != bGeneric)
                return aGeneric ? 1 : -1;
            int ap = (a.getSkill() != null && a.getSkill().getPriority() != null) ? a.getSkill().getPriority() : 0;
            int bp = (b.getSkill() != null && b.getSkill().getPriority() != null) ? b.getSkill().getPriority() : 0;
            if (ap != bp)
                return Integer.compare(bp, ap);
            int c1 = a.getStartTime().compareTo(b.getStartTime());
            if (c1 != 0)
                return c1;
            return a.getEndTime().compareTo(b.getEndTime());
        });
        List<LocalTime> slots = buildSlots(granularity);
        Map<LocalTime, Integer> requiredBySlot = new HashMap<>();
        Map<LocalTime, Integer> reservedSkillBySlot = new HashMap<>();
        for (LocalTime t : slots) {
            requiredBySlot.put(t, 0);
            reservedSkillBySlot.put(t, 0);
        }
        for (DemandInterval d : demands) {
            int seats = Optional.ofNullable(d.getRequiredSeats()).orElse(0);
            if (seats <= 0)
                continue;
            List<LocalTime> cov = slotsCoveredBy(d.getStartTime(), d.getEndTime(), granularity);
            for (LocalTime t : cov) {
                requiredBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
                if (d.getSkill() != null)
                    reservedSkillBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
            }
        }
        Map<LocalTime, Set<Long>> demandedSkillIdsBySlot = new HashMap<>();
        for (LocalTime t : slots)
            demandedSkillIdsBySlot.put(t, new HashSet<>());
        for (DemandInterval d : demands) {
            if (d.getSkill() == null)
                continue;
            Long sid = d.getSkill().getId();
            if (sid == null)
                continue;
            for (LocalTime t : slotsCoveredBy(d.getStartTime(), d.getEndTime(), granularity)) {
                demandedSkillIdsBySlot.get(t).add(sid);
            }
        }

        Map<LocalTime, Integer> assignedBySlot = new HashMap<>();
        for (LocalTime t : slots)
            assignedBySlot.put(t, 0);

        List<ShiftAssignment> reservationAssignments = applyReservationsForDay(
                date,
                dayReservations,
                dayCtx,
                assignedBySlot,
                reservedSkillBySlot,
                granularity,
                workedDaysByEmployee,
                assignedToday,
                mtdTotalWorkedDays,
                mtdWeekendHolidayWorkedDays,
                isWkHol);
        if (!reservationAssignments.isEmpty()) {
            created.addAll(reservationAssignments);
            rotate += reservationAssignments.size();
        }

        for (DemandInterval d : demands) {
            Integer seatsObj = d.getRequiredSeats();
            if (seatsObj == null || seatsObj <= 0)
                continue;
            LocalTime s = d.getStartTime();
            LocalTime e = d.getEndTime();
            Skill needSkill = d.getSkill();
            String code = needSkill != null ? safeCode(needSkill.getCode()) : "A";
            String label = String.format("Demand-%s %s-%s", code, timeLabel(s), timeLabel(e));

            List<Employee> avail = assignmentRepository.findAvailableEmployeesForTimeSlot(date, s, e)
                    .stream()
                    .filter(emp -> !dayCtx.excludeByPatternStrict.getOrDefault(emp.getId(), false))
                    .filter(emp -> !dayCtx.hardUnavailable.getOrDefault(emp.getId(), false))
                    .toList();
            if (needSkill != null) {
                Long sid = needSkill.getId();
                avail = avail.stream().filter(emp -> emp.getSkills() != null
                        && emp.getSkills().stream().anyMatch(sk -> Objects.equals(sk.getId(), sid))).toList();
            }
            if (avail.isEmpty())
                continue;

            if (needSkill == null) {
                List<LocalTime> covers = slotsCoveredBy(s, e, granularity);
                Map<LocalTime, Integer> skilledAvailCount = new HashMap<>();
                for (LocalTime t : covers)
                    skilledAvailCount.put(t, 0);
                for (Employee emp : avail) {
                    for (LocalTime t : covers) {
                        Set<Long> ds = demandedSkillIdsBySlot.getOrDefault(t, Collections.emptySet());
                        boolean empSkilled = emp.getSkills() != null
                                && emp.getSkills().stream().anyMatch(sk -> ds.contains(sk.getId()));
                        if (empSkilled)
                            skilledAvailCount.compute(t, (k, v) -> (v == null ? 0 : v) + 1);
                    }
                }
                List<Employee> filtered = new ArrayList<>();
                for (Employee emp : avail) {
                    boolean reserved = false;
                    for (LocalTime t : covers) {
                        int reservedSeats = reservedSkillBySlot.getOrDefault(t, 0);
                        if (reservedSeats <= 0)
                            continue;
                        Set<Long> ds = demandedSkillIdsBySlot.getOrDefault(t, Collections.emptySet());
                        boolean empSkilled = emp.getSkills() != null
                                && emp.getSkills().stream().anyMatch(sk -> ds.contains(sk.getId()));
                        if (empSkilled) {
                            int skilledCount = skilledAvailCount.getOrDefault(t, 0);
                            if (skilledCount <= reservedSeats) {
                                reserved = true;
                                break;
                            }
                        }
                    }
                    if (!reserved)
                        filtered.add(emp);
                }
                if (!filtered.isEmpty())
                    avail = filtered;
            }

            List<Employee> rotated = new ArrayList<>(avail);
            if (!rotated.isEmpty()) {
                int r = rotated.size() == 0 ? 0 : (rotate % rotated.size());
                if (r != 0)
                    Collections.rotate(rotated, -r);
                Map<Long, Integer> pos = new HashMap<>();
                for (int i = 0; i < rotated.size(); i++)
                    pos.put(rotated.get(i).getId(), i);
                rotated.sort((aEmp, bEmp) -> {
                    long aScore = fairnessScore(aEmp.getId(), isWkHol, mtdTotalWorkedDays, mtdWeekendHolidayWorkedDays,
                            assignedToday, pos);
                    long bScore = fairnessScore(bEmp.getId(), isWkHol, mtdTotalWorkedDays, mtdWeekendHolidayWorkedDays,
                            assignedToday, pos);
                    if (dayCtx.softUnavailable.getOrDefault(aEmp.getId(), false))
                        aScore += 5_000L;
                    if (dayCtx.preferred.getOrDefault(aEmp.getId(), false))
                        aScore -= 100L;
                    if (dayCtx.softUnavailable.getOrDefault(bEmp.getId(), false))
                        bScore += 5_000L;
                    if (dayCtx.preferred.getOrDefault(bEmp.getId(), false))
                        bScore -= 100L;
                    return Long.compare(aScore, bScore);
                });
            }
            List<LocalTime> covers = slotsCoveredBy(s, e, granularity);
            int newly = 0;
            int seats = seatsObj;
            for (Employee emp : rotated) {
                if (newly >= seats)
                    break;
                boolean fits = true;
                for (LocalTime t : covers) {
                    int req = requiredBySlot.getOrDefault(t, 0);
                    int asn = assignedBySlot.getOrDefault(t, 0);
                    int reserved = reservedSkillBySlot.getOrDefault(t, 0);
                    int cap = (needSkill == null) ? Math.max(0, req - reserved) : req;
                    if (asn >= cap) {
                        fits = false;
                        break;
                    }
                }
                if (!fits)
                    continue;
                // avail は既に空きのため再照会しない
                ShiftAssignment a = new ShiftAssignment(date, label, s, e, emp);
                assignmentRepository.save(a);
                created.add(a);
                Long empId = emp.getId();
                if (assignedToday.add(empId)) {
                    mtdTotalWorkedDays.merge(empId, 1, Integer::sum);
                    if (isWkHol)
                        mtdWeekendHolidayWorkedDays.merge(empId, 1, Integer::sum);
                }
                newly++;
                for (LocalTime t : covers) {
                    assignedBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + 1);
                    if (needSkill != null)
                        reservedSkillBySlot.compute(t, (k, v) -> Math.max(0, (v == null ? 0 : v) - 1));
                }
            }
            rotate += newly;
        }
        try {
            jobStatusService.updateCount(date.getYear(), date.getMonthValue(), baselineCount + created.size());
        } catch (Exception ignore) {
        }
        ensureFreePlaceholders(date.getYear(), date.getMonthValue());
        return created;
    }

    @Transactional(readOnly = true)
    public ScheduleGridResponse loadGrid(LocalDate start, LocalDate end) {
        LocalDate[] normalized = normalizeRange(start, end);
        LocalDate rangeStart = normalized[0];
        LocalDate rangeEnd = normalized[1];
        List<Employee> employees = employeeRepository.findAll();
        employees.sort(Comparator
                .comparing((Employee e) -> e.getAssignPriority() == null ? 0 : e.getAssignPriority())
                .thenComparing(Employee::getName, String.CASE_INSENSITIVE_ORDER));
        List<ScheduleGridEmployeeDto> employeeDtos = employees.stream()
                .map(ScheduleGridEmployeeDto::from)
                .toList();
        List<ScheduleGridAssignmentDto> assignments = assignmentRepository.findWithEmployeeBetween(rangeStart, rangeEnd)
                .stream()
                .map(ScheduleGridAssignmentDto::from)
                .toList();
        Map<String, Object> meta = new HashMap<>();
        meta.put("rangeDays", ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1);
        meta.put("employeeCount", employeeDtos.size());
        meta.put("assignmentCount", assignments.size());
        return new ScheduleGridResponse(rangeStart, rangeEnd, employeeDtos, assignments, meta);
    }

    @Transactional
    public ScheduleGridBulkResult applyGridChanges(ScheduleGridBulkRequest request) {
        if (request == null) {
            throw new BusinessException("GRID_BULK_EMPTY", "更新内容が空です");
        }
        List<ScheduleGridBulkRequest.CreatePayload> creates = Optional.ofNullable(request.getCreate()).orElseGet(List::of);
        List<ScheduleGridBulkRequest.UpdatePayload> updates = Optional.ofNullable(request.getUpdate()).orElseGet(List::of);
        List<Long> deletes = Optional.ofNullable(request.getDelete()).orElseGet(List::of);

        Set<Long> deleteSet = new HashSet<>(deletes);
        if (deleteSet.size() != deletes.size()) {
            throw new BusinessException("GRID_BULK_DUPLICATE_DELETE", "削除対象が重複しています");
        }
        if (updates.stream().anyMatch(u -> deleteSet.contains(u.getId()))) {
            throw new BusinessException("GRID_BULK_CONFLICT", "更新と削除が同一のシフトに指定されています");
        }

        Map<Long, ShiftAssignment> assignmentsById = updates.isEmpty()
                ? Collections.emptyMap()
                : assignmentRepository.findAllById(
                        updates.stream()
                                .map(ScheduleGridBulkRequest.UpdatePayload::getId)
                                .filter(Objects::nonNull)
                                .toList())
                .stream()
                .collect(Collectors.toMap(ShiftAssignment::getId, sa -> sa));
        for (ScheduleGridBulkRequest.UpdatePayload payload : updates) {
            if (payload.getId() == null || !assignmentsById.containsKey(payload.getId())) {
                throw new BusinessException("GRID_UPDATE_NOT_FOUND", "更新対象シフトが見つかりません");
            }
        }

        Set<Long> employeeIds = new HashSet<>();
        creates.stream().map(ScheduleGridBulkRequest.BasePayload::getEmployeeId).filter(Objects::nonNull).forEach(employeeIds::add);
        updates.stream().map(ScheduleGridBulkRequest.BasePayload::getEmployeeId).filter(Objects::nonNull).forEach(employeeIds::add);
        Map<Long, Employee> employeesById = employeeIds.isEmpty()
                ? Collections.emptyMap()
                : employeeRepository.findAllById(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getId, e -> e));
        if (employeeIds.size() != employeesById.size()) {
            throw new BusinessException("GRID_EMPLOYEE_NOT_FOUND", "指定された従業員が見つかりません");
        }

        Map<CacheKey, List<ShiftWindow>> workingState = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int deleted = 0;

        for (ScheduleGridBulkRequest.CreatePayload payload : creates) {
            Employee employee = resolveEmployee(payload.getEmployeeId(), employeesById);
            LocalDate workDate = payload.getWorkDate();
            if (workDate == null) {
                throw new BusinessException("GRID_DATE_REQUIRED", "日付は必須です");
            }
            LocalTime startTime = requireTime(payload.getStartTime(), "startTime");
            LocalTime endTime = requireTime(payload.getEndTime(), "endTime");
            validateTimeRange(startTime, endTime);
            ensureWithinRange(workDate, workDate);
            ensureNoConflict(workingState, employee, workDate, startTime, endTime, null);
            ShiftAssignment entity = new ShiftAssignment(
                    workDate,
                    defaultShiftName(payload.getShiftName()),
                    startTime,
                    endTime,
                    employee);
            applyFlags(entity, payload);
            assignmentRepository.save(entity);
            registerWindow(workingState, employee, workDate, entity);
            created++;
        }

        for (ScheduleGridBulkRequest.UpdatePayload payload : updates) {
            ShiftAssignment entity = assignmentsById.get(payload.getId());
            Employee targetEmployee = payload.getEmployeeId() != null
                    ? resolveEmployee(payload.getEmployeeId(), employeesById)
                    : entity.getEmployee();
            LocalDate targetDate = payload.getWorkDate() != null ? payload.getWorkDate() : entity.getWorkDate();
            LocalTime startTime = payload.getStartTime() != null ? payload.getStartTime() : entity.getStartTime();
            LocalTime endTime = payload.getEndTime() != null ? payload.getEndTime() : entity.getEndTime();
            validateTimeRange(startTime, endTime);
            ensureWithinRange(targetDate, targetDate);
            ensureNoConflict(workingState, targetEmployee, targetDate, startTime, endTime, entity.getId());
            unregisterWindow(workingState, entity);
            entity.setEmployee(targetEmployee);
            entity.setWorkDate(targetDate);
            entity.setStartTime(startTime);
            entity.setEndTime(endTime);
            if (payload.getShiftName() != null && !payload.getShiftName().isBlank()) {
                entity.setShiftName(payload.getShiftName().trim());
            }
            applyFlags(entity, payload);
            assignmentRepository.save(entity);
            registerWindow(workingState, targetEmployee, targetDate, entity);
            updated++;
        }

        if (!deleteSet.isEmpty()) {
            List<ShiftAssignment> toDelete = assignmentRepository.findAllById(deleteSet);
            for (ShiftAssignment entity : toDelete) {
                unregisterWindow(workingState, entity);
            }
            assignmentRepository.deleteAll(toDelete);
            deleted = toDelete.size();
            if (deleted != deleteSet.size()) {
                warnings.add("一部の削除対象が既に存在していませんでした");
            }
        }

        return new ScheduleGridBulkResult(created, updated, deleted, warnings);
    }

    private LocalDate[] normalizeRange(LocalDate start, LocalDate end) {
        LocalDate base = LocalDate.now();
        LocalDate rangeStart = start != null ? start : weekStartSunday(base);
        LocalDate rangeEnd = end != null ? end : rangeStart.plusDays(6);
        if (rangeStart.isAfter(rangeEnd)) {
            LocalDate tmp = rangeStart;
            rangeStart = rangeEnd;
            rangeEnd = tmp;
        }
        ensureWithinRange(rangeStart, rangeEnd);
        return new LocalDate[]{rangeStart, rangeEnd};
    }

    private void ensureWithinRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BusinessException("GRID_RANGE_REQUIRED", "期間の指定が必要です");
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span <= 0) {
            throw new BusinessException("GRID_RANGE_INVALID", "期間の指定が不正です");
        }
        if (span > GRID_RANGE_LIMIT_DAYS) {
            throw new BusinessException("GRID_RANGE_TOO_WIDE", "最大" + GRID_RANGE_LIMIT_DAYS + "日まで選択できます");
        }
    }

    private Employee resolveEmployee(Long employeeId, Map<Long, Employee> cache) {
        if (employeeId == null) {
            throw new BusinessException("GRID_EMPLOYEE_REQUIRED", "従業員を選択してください");
        }
        Employee employee = cache.get(employeeId);
        if (employee != null) {
            return employee;
        }
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new BusinessException("GRID_EMPLOYEE_NOT_FOUND", "従業員が見つかりません (ID=" + employeeId + ")"));
    }

    private LocalTime requireTime(LocalTime time, String fieldName) {
        if (time == null) {
            throw new BusinessException("GRID_TIME_REQUIRED", fieldName + "は必須です");
        }
        return time;
    }

    private void validateTimeRange(LocalTime start, LocalTime end) {
        if (!start.isBefore(end)) {
            throw new BusinessException("GRID_TIME_INVALID", "開始時刻は終了時刻より前である必要があります");
        }
    }

    private void ensureNoConflict(Map<CacheKey, List<ShiftWindow>> state,
                                  Employee employee,
                                  LocalDate date,
                                  LocalTime start,
                                  LocalTime end,
                                  Long ignoreId) {
        List<ShiftWindow> windows = getOrLoadWindows(state, employee, date);
        boolean conflict = windows.stream()
                .filter(w -> !Objects.equals(w.assignmentId(), ignoreId))
                .anyMatch(w -> w.overlaps(start, end));
        if (conflict) {
            throw new BusinessException("GRID_CONFLICT",
                    employee.getName() + " の " + date + " は既存シフトと重複しています");
        }
    }

    private List<ShiftWindow> getOrLoadWindows(Map<CacheKey, List<ShiftWindow>> state,
                                               Employee employee,
                                               LocalDate date) {
        CacheKey key = new CacheKey(employee.getId(), date);
        return state.computeIfAbsent(key, k -> assignmentRepository.findByEmployeeAndWorkDate(employee, date)
                .stream()
                .map(ShiftWindow::from)
                .collect(Collectors.toCollection(ArrayList::new)));
    }

    private void registerWindow(Map<CacheKey, List<ShiftWindow>> state,
                                Employee employee,
                                LocalDate date,
                                ShiftAssignment assignment) {
        List<ShiftWindow> windows = getOrLoadWindows(state, employee, date);
        windows.removeIf(w -> Objects.equals(w.assignmentId(), assignment.getId()));
        windows.add(ShiftWindow.from(assignment));
    }

    private void unregisterWindow(Map<CacheKey, List<ShiftWindow>> state,
                                  ShiftAssignment assignment) {
        Employee employee = assignment.getEmployee();
        if (employee == null) {
            return;
        }
        CacheKey key = new CacheKey(employee.getId(), assignment.getWorkDate());
        List<ShiftWindow> windows = state.get(key);
        if (windows == null) {
            windows = getOrLoadWindows(state, employee, assignment.getWorkDate());
        }
        windows.removeIf(w -> Objects.equals(w.assignmentId(), assignment.getId()));
        if (windows.isEmpty()) {
            state.remove(key);
        }
    }

    private void applyFlags(ShiftAssignment assignment, ScheduleGridBulkRequest.BasePayload payload) {
        if (payload.getIsFree() != null) {
            assignment.setIsFree(payload.getIsFree());
        }
        if (payload.getIsOff() != null) {
            assignment.setIsOff(payload.getIsOff());
        }
        if (payload.getIsLeave() != null) {
            assignment.setIsLeave(payload.getIsLeave());
        }
    }

    private String defaultShiftName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "Manual";
        }
        return candidate.trim();
    }

    private record CacheKey(Long employeeId, LocalDate workDate) {
    }

    private record ShiftWindow(Long assignmentId, LocalTime start, LocalTime end) {
        static ShiftWindow from(ShiftAssignment assignment) {
            return new ShiftWindow(assignment.getId(), assignment.getStartTime(), assignment.getEndTime());
        }

        boolean overlaps(LocalTime otherStart, LocalTime otherEnd) {
            return start.isBefore(otherEnd) && otherStart.isBefore(end);
        }
    }

    private List<LocalTime> buildSlots(int granularityMinutes) {
        List<LocalTime> slots = new ArrayList<>();
        int steps = Math.max(1, (24 * 60) / granularityMinutes);
        for (int i = 0; i < steps; i++)
            slots.add(LocalTime.MIDNIGHT.plusMinutes((long) i * granularityMinutes));
        return slots;
    }

    private List<LocalTime> slotsCoveredBy(LocalTime start, LocalTime end, int granularityMinutes) {
        List<LocalTime> res = new ArrayList<>();
        List<LocalTime> base = buildSlots(granularityMinutes);
        LocalTime lastEnd = LocalTime.of(23, 59);
        for (int i = 0; i < base.size(); i++) {
            LocalTime s = base.get(i);
            LocalTime e = (i == base.size() - 1) ? lastEnd : base.get(i + 1);
            if (start.isBefore(e) && end.isAfter(s))
                res.add(s);
        }
        return res;
    }

    private List<ShiftAssignment> applyReservationsForDay(LocalDate day,
                                                          List<ShiftReservation> reservations,
                                                          DayContext dayCtx,
                                                          Map<LocalTime, Integer> assignedBySlot,
                                                          Map<LocalTime, Integer> reservedSkillBySlot,
                                                          int granularityMinutes,
                                                          Map<Long, Set<LocalDate>> workedDaysByEmployee,
                                                          Set<Long> assignedToday,
                                                          Map<Long, Integer> mtdTotalWorkedDays,
                                                          Map<Long, Integer> mtdWeekendHolidayWorkedDays,
                                                          boolean isWeekendOrHoliday) {
        if (reservations == null || reservations.isEmpty()) {
            return Collections.emptyList();
        }
        List<ShiftAssignment> created = new ArrayList<>();
        for (ShiftReservation reservation : reservations) {
            if (reservation == null || !reservation.isPending()) {
                continue;
            }
            Employee employee = reservation.getEmployee();
            if (employee == null || employee.getId() == null) {
                continue;
            }
            Long empId = employee.getId();
            if (dayCtx.excludeByPatternStrict.getOrDefault(empId, false)) {
                continue;
            }
            if (dayCtx.hardUnavailable.getOrDefault(empId, false)) {
                continue;
            }
            List<ShiftAssignment> dayAssignments = assignmentRepository.findByEmployeeAndWorkDate(employee, day);
            boolean conflict = false;
            for (ShiftAssignment existing : dayAssignments) {
                if (existing.getStartTime() == null || existing.getEndTime() == null) {
                    continue;
                }
                if (overlaps(existing.getStartTime(), existing.getEndTime(), reservation.getStartTime(), reservation.getEndTime())) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                continue;
            }
            ShiftAssignment assignment = new ShiftAssignment(
                    day,
                    reservationLabel(reservation),
                    reservation.getStartTime(),
                    reservation.getEndTime(),
                    employee
            );
            assignmentRepository.save(assignment);
            reservation.setStatus(ShiftReservation.Status.APPLIED);
            reservationRepository.save(reservation);
            created.add(assignment);

            workedDaysByEmployee.computeIfAbsent(empId, k -> new HashSet<>()).add(day);
            if (assignedToday.add(empId)) {
                mtdTotalWorkedDays.merge(empId, 1, Integer::sum);
                if (isWeekendOrHoliday) {
                    mtdWeekendHolidayWorkedDays.merge(empId, 1, Integer::sum);
                }
            }
            List<LocalTime> covers = slotsCoveredBy(reservation.getStartTime(), reservation.getEndTime(), granularityMinutes);
            for (LocalTime slot : covers) {
                assignedBySlot.compute(slot, (k, v) -> (v == null ? 0 : v) + 1);
                if (reservation.getSkill() != null) {
                    reservedSkillBySlot.compute(slot, (k, v) -> Math.max(0, (v == null ? 0 : v) - 1));
                }
            }
        }
        return created;
    }

    private boolean overlaps(LocalTime s1, LocalTime e1, LocalTime s2, LocalTime e2) {
        return s1.isBefore(e2) && s2.isBefore(e1);
    }

    private String reservationLabel(ShiftReservation reservation) {
        if (reservation.getLabel() != null && !reservation.getLabel().isBlank()) {
            return reservation.getLabel();
        }
        if (reservation.getSkill() != null) {
            return "Reservation-" + safeCode(reservation.getSkill().getCode());
        }
        return "Reservation";
    }

    // ---------------- Placeholders ----------------
    @Transactional
    public void ensureFreePlaceholders(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        List<Employee> employees = employeeRepository.findAll();
        if (employees == null || employees.isEmpty())
            return;
        LocalTime freeStart = parseCfgTime(cfgFreeStart, LocalTime.MIDNIGHT);
        LocalTime freeEnd = parseCfgTime(cfgFreeEnd, LocalTime.of(0, 5));
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            boolean onlyWeekdays = freeSettings != null ? freeSettings.isOnlyWeekdays() : cfgFreeOnlyWeekdays;
            boolean skipHolidays = freeSettings != null ? freeSettings.isSkipHolidays() : cfgFreeSkipHolidays;
            if (onlyWeekdays) {
                var dow = day.getDayOfWeek();
                if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) {
                    continue;
                }
            }
            if (skipHolidays) {
                try {
                    if (holidayRepository.existsByDate(day))
                        continue;
                } catch (Exception ignored) {
                }
            }
            List<ShiftAssignment> dayList = assignmentRepository.findByWorkDate(day);
            Set<Long> hasReal = new HashSet<>();
            Set<Long> hasFree = new HashSet<>();
            Set<Long> hasOff = new HashSet<>();
            List<ShiftAssignment> toInsert = new ArrayList<>();
            for (ShiftAssignment sa : dayList) {
                boolean isFree = Boolean.TRUE.equals(sa.getIsFree())
                        || (sa.getShiftName() != null && "FREE".equalsIgnoreCase(sa.getShiftName()));
                boolean isOff = Boolean.TRUE.equals(sa.getIsOff()) || (sa.getShiftName() != null
                        && ("休日".equals(sa.getShiftName()) || "OFF".equalsIgnoreCase(sa.getShiftName())));
                boolean isLeave = Boolean.TRUE.equals(sa.getIsLeave());
                if (isFree)
                    hasFree.add(sa.getEmployee().getId());
                if (isOff || isLeave)
                    hasOff.add(sa.getEmployee().getId());
                if (!isFree && !isOff && !isLeave)
                    hasReal.add(sa.getEmployee().getId());
            }
            for (Employee emp : employees) {
                Long id = emp.getId();
                if (!hasReal.contains(id) && !hasFree.contains(id) && !hasOff.contains(id)) {
                    ShiftAssignment free = new ShiftAssignment(day, "FREE", freeStart, freeEnd, emp);
                    free.setIsFree(true);
                    toInsert.add(free);
                }
            }
            if (!toInsert.isEmpty())
                assignmentRepository.saveAll(toInsert);
        }
    }

    // パターンに基づくOFFの先置き（既に実アサイン/既OFFが無い日だけに作成）
    @Transactional
    public void ensurePatternOffPlaceholders(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        List<Employee> employees = employeeRepository.findAll();
        if (employees == null || employees.isEmpty())
            return;
        Map<Long, EmployeeRule> rulesByEmp = loadRulesByEmployee(employees);
        LocalTime offStart = parseCfgTime(cfgOffStart, LocalTime.MIDNIGHT);
        LocalTime offEnd = parseCfgTime(cfgOffEnd, LocalTime.of(0, 5));
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            List<ShiftAssignment> dayList = assignmentRepository.findByWorkDate(day);
            Map<Long, String> existing = new HashMap<>();
            for (ShiftAssignment sa : dayList) {
                Long empId = sa.getEmployee() != null ? sa.getEmployee().getId() : null;
                if (empId == null)
                    continue;
                boolean isFree = Boolean.TRUE.equals(sa.getIsFree())
                        || (sa.getShiftName() != null && "FREE".equalsIgnoreCase(sa.getShiftName()));
                boolean isOff = Boolean.TRUE.equals(sa.getIsOff()) || (sa.getShiftName() != null
                        && ("休日".equals(sa.getShiftName()) || "OFF".equalsIgnoreCase(sa.getShiftName())));
                boolean isLeave = Boolean.TRUE.equals(sa.getIsLeave());
                if (!isFree && !isOff && !isLeave)
                    existing.put(empId, "REAL");
                if (isOff || isLeave)
                    existing.put(empId, "OFF");
                if (isFree && !existing.containsKey(empId))
                    existing.put(empId, "FREE");
            }
            List<ShiftAssignment> toInsert = new ArrayList<>();
            for (Employee emp : employees) {
                Long empId = emp.getId();
                EmployeeRule rule = rulesByEmp.get(empId);
                if (rule == null)
                    continue;
                if (rule.getWorkOffPattern() == null || rule.getWorkOffPattern().isBlank())
                    continue;
                if (!isPatternOff(rule, day))
                    continue;
                if (existing.containsKey(empId))
                    continue; // already has something
                ShiftAssignment off = new ShiftAssignment(day, "休日", offStart, offEnd, emp);
                off.setIsOff(true);
                toInsert.add(off);
            }
            if (!toInsert.isEmpty())
                assignmentRepository.saveAll(toInsert);
        }
    }

    private boolean isPatternOff(EmployeeRule rule, LocalDate day) {
        String pattern = Optional.ofNullable(rule.getWorkOffPattern()).orElse("").trim();
        if (pattern.isEmpty())
            return false;
        LocalDate anchor = Optional.ofNullable(rule.getPatternAnchorDate()).orElse(LocalDate.of(day.getYear(), 1, 1));
        // Build repeating sequence of 'W' and 'O'
        List<Character> seq = new ArrayList<>();
        for (String token : pattern.split("-")) {
            token = token.trim();
            if (token.isEmpty())
                continue;
            int n = 0;
            char kind = 'W';
            int i = 0;
            while (i < token.length() && Character.isDigit(token.charAt(i))) {
                n = n * 10 + (token.charAt(i) - '0');
                i++;
            }
            if (i < token.length()) {
                kind = Character.toUpperCase(token.charAt(i));
            }
            if (n <= 0)
                n = 1;
            for (int k = 0; k < n; k++)
                seq.add(kind);
        }
        if (seq.isEmpty())
            return false;
        long days = java.time.temporal.ChronoUnit.DAYS.between(anchor, day);
        if (days < 0)
            days = Math.abs(days); // if before anchor, still fold
        int idx = (int) (days % seq.size());
        return seq.get(idx) == 'O';
    }

    private boolean isWeekendOrHoliday(LocalDate d) {
        var dow = d.getDayOfWeek();
        if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY)
            return true;
        return isHoliday(d);
    }

    private boolean isHoliday(LocalDate d) {
        try {
            return holidayRepository.existsByDate(d);
        } catch (Exception e) {
            return false;
        }
    }

    private long fairnessScore(Long empId,
            boolean isWeekendOrHoliday,
            Map<Long, Integer> mtdTotal,
            Map<Long, Integer> mtdWeekend,
            Set<Long> assignedToday,
            Map<Long, Integer> basePos) {
        int total = mtdTotal.getOrDefault(empId, 0);
        int wk = mtdWeekend.getOrDefault(empId, 0);
        long score = isWeekendOrHoliday ? (wk * 1000L + total) : (total * 1000L + wk);
        if (assignedToday.contains(empId))
            score += 1_000_000L; // strongly deprioritize multi-assign in a day
        score = score * 10L + basePos.getOrDefault(empId, 0); // stable tie-break by rotated order
        return score;
    }

    @Transactional
    public void ensureWeeklyHolidays(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        List<Employee> employees = employeeRepository.findAll();
        if (employees == null || employees.isEmpty())
            return;
        Map<Long, EmployeeRule> rulesByEmp = loadRulesByEmployee(employees);
        LocalTime offStart = parseCfgTime(cfgOffStart, LocalTime.MIDNIGHT);
        LocalTime offEnd = parseCfgTime(cfgOffEnd, LocalTime.of(0, 5));

        // Evaluate full weeks spanning the month: Sunday .. Saturday
        LocalDate outerStart = weekStartSunday(monthStart); // may be in previous month
        LocalDate outerEnd = weekStartSunday(monthEnd).plusDays(6); // may be in next month

        // Creation window: allow creating OFF inside the month, and optionally through
        // to the first Saturday after month end
        LocalDate creationEnd = outerEnd; // implement "翌月の土曜日まで" 作成
        LocalDate creationStart = monthStart; // do not touch days before month start
        Map<Long, Integer> targetRestByEmp = new HashMap<>();
        for (Employee emp : employees) {
            int rest = Optional.ofNullable(rulesByEmp.get(emp.getId()))
                    .map(EmployeeRule::getWeeklyRestDays)
                    .filter(v -> v != null && v >= 0)
                    .orElse(2);
            targetRestByEmp.put(emp.getId(), rest);
        }
        Map<LocalDate, List<ShiftAssignment>> assignmentsByDate = new HashMap<>();
        for (ShiftAssignment existing : assignmentRepository.findByWorkDateBetween(outerStart, outerEnd)) {
            assignmentsByDate.computeIfAbsent(existing.getWorkDate(), k -> new ArrayList<>()).add(existing);
        }
        List<ShiftAssignment> pending = new ArrayList<>();

        for (LocalDate cursor = outerStart; !cursor.isAfter(outerEnd); cursor = cursor.plusWeeks(1)) {
            LocalDate weekStart = cursor;
            LocalDate weekEnd = weekStart.plusDays(6);
            for (Employee emp : employees) {
                Long empId = emp.getId();
                int targetRest = targetRestByEmp.getOrDefault(empId, 2);

                int currentOff = 0;
                Set<LocalDate> realAssignedDays = new HashSet<>();
                Set<LocalDate> offDays = new HashSet<>();
                for (LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) {
                    List<ShiftAssignment> dayList = assignmentsByDate.getOrDefault(d, Collections.emptyList());
                    boolean hasReal = false;
                    boolean hasOff = false;
                    for (ShiftAssignment sa : dayList) {
                        if (!Objects.equals(sa.getEmployee().getId(), empId))
                            continue;
                        boolean isFree = Boolean.TRUE.equals(sa.getIsFree())
                                || (sa.getShiftName() != null && "FREE".equalsIgnoreCase(sa.getShiftName()));
                        boolean isOff = Boolean.TRUE.equals(sa.getIsOff()) || (sa.getShiftName() != null
                                && ("休日".equals(sa.getShiftName()) || "OFF".equalsIgnoreCase(sa.getShiftName())));
                        boolean isLeave = Boolean.TRUE.equals(sa.getIsLeave());
                        if (isOff || isLeave) {
                            hasOff = true;
                        }
                        if (!isFree && !isOff && !isLeave) {
                            hasReal = true;
                        }
                    }
                    if (hasOff) {
                        currentOff++;
                        offDays.add(d);
                    }
                    if (hasReal) {
                        realAssignedDays.add(d);
                    }
                }

                int need = Math.max(0, targetRest - currentOff);
                if (need == 0)
                    continue;

                // Select candidate days to mark OFF: prefer days inside current month portion
                // first, then spillover after month end
                List<LocalDate> candidates = new ArrayList<>();
                // 1) within month
                for (LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) {
                    if (d.isBefore(creationStart) || d.isAfter(creationEnd))
                        continue;
                    if (d.isAfter(monthEnd))
                        continue; // handled in second pass
                    if (offDays.contains(d))
                        continue; // already off
                    if (!realAssignedDays.contains(d))
                        candidates.add(d);
                }
                // 2) spillover after month end up to Saturday
                for (LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) {
                    if (d.isBefore(creationStart) || d.isAfter(creationEnd))
                        continue;
                    if (!d.isAfter(monthEnd))
                        continue; // only after month end here
                    if (offDays.contains(d))
                        continue;
                    if (!realAssignedDays.contains(d))
                        candidates.add(d);
                }

                for (int i = 0; i < need && i < candidates.size(); i++) {
                    LocalDate offDay = candidates.get(i);
                    ShiftAssignment off = new ShiftAssignment(offDay, "休日", offStart, offEnd, emp);
                    off.setIsOff(true);
                    pending.add(off);
                    assignmentsByDate.computeIfAbsent(offDay, k -> new ArrayList<>()).add(off);
                }
            }
        }
        if (!pending.isEmpty())
            assignmentRepository.saveAll(pending);
    }

    @Transactional
    public ShiftAssignment convertFreePlaceholderToPaidLeave(Long assignmentId) {
        ShiftAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("対象のシフトが見つかりません"));
        if (!Boolean.TRUE.equals(assignment.getIsFree())) {
            throw new IllegalStateException("FREEプレースホルダー以外は有給に変更できません");
        }
        Employee employee = assignment.getEmployee();
        LeaveBalance balance = leaveBalanceRepository.findTopByEmployeeOrderByIdDesc(employee)
                .orElse(new LeaveBalance(employee, 0, 0, null, null));
        if (balance.remaining() <= 0) {
            throw new IllegalStateException("有給残数が不足しています");
        }
        LeaveRequest request = new LeaveRequest(employee, assignment.getWorkDate());
        request.setStatus(LeaveRequest.Status.APPROVED);
        leaveRequestRepository.save(request);
        EmployeeConstraint constraint = new EmployeeConstraint(employee,
                assignment.getWorkDate(),
                EmployeeConstraint.ConstraintType.VACATION,
                "PTO");
        constraintRepository.save(constraint);
        balance.setUsed((balance.getUsed() == null ? 0 : balance.getUsed()) + 1);
        leaveBalanceRepository.save(balance);
        assignment.setShiftName("有給");
        assignment.setIsFree(false);
        assignment.setIsOff(true);
        assignment.setIsLeave(true);
        return assignmentRepository.save(assignment);
    }

    // Day-level rule and constraint view used during generation
    private static class DayContext {
        final Map<Long, Boolean> excludeByPatternStrict = new HashMap<>();
        final Map<Long, Boolean> hardUnavailable = new HashMap<>();
        final Map<Long, Boolean> softUnavailable = new HashMap<>();
        final Map<Long, Boolean> preferred = new HashMap<>();
    }

    private DayContext buildDayContext(LocalDate day,
                                       List<Employee> employees,
                                       Map<Long, EmployeeRule> rulesByEmp) {
        DayContext ctx = new DayContext();
        if (employees == null) return ctx;

        // 1) Pattern-based OFF (strict): exclude employees if patternStrict=true and the day is OFF by pattern
        for (Employee emp : employees) {
            if (emp == null || emp.getId() == null) continue;
            EmployeeRule rule = rulesByEmp != null ? rulesByEmp.get(emp.getId()) : null;
            boolean exclude = false;
            if (rule != null && Boolean.TRUE.equals(rule.getPatternStrict())) {
                try {
                    if (isPatternOff(rule, day)) exclude = true;
                } catch (Exception ignored) {}
            }
            if (exclude) ctx.excludeByPatternStrict.put(emp.getId(), true);
        }

        // 2) Constraints for the day (single fetch for all employees)
        List<EmployeeConstraint> list;
        try {
            list = constraintRepository.findByDateBetweenAndActiveTrue(day, day);
        } catch (Exception e) {
            list = Collections.emptyList();
        }
        for (EmployeeConstraint ec : list) {
            if (ec == null || ec.getEmployee() == null || ec.getEmployee().getId() == null) continue;
            Long empId = ec.getEmployee().getId();
            EmployeeConstraint.ConstraintType type = ec.getType();
            EmployeeConstraint.Severity sev = ec.getSeverity();

            boolean isHard = (sev == EmployeeConstraint.Severity.HARD)
                    || type == EmployeeConstraint.ConstraintType.VACATION
                    || type == EmployeeConstraint.ConstraintType.SICK_LEAVE;

            switch (type) {
                case UNAVAILABLE, VACATION, SICK_LEAVE -> {
                    if (isHard) ctx.hardUnavailable.put(empId, true);
                    else ctx.softUnavailable.put(empId, true);
                }
                case LIMITED, PERSONAL -> ctx.softUnavailable.put(empId, true);
                case PREFERRED -> ctx.preferred.put(empId, true);
            }
        }
        return ctx;
    }

    private Map<Long, EmployeeRule> loadRulesByEmployee(List<Employee> employees) {
        Map<Long, EmployeeRule> rulesByEmp = new HashMap<>();
        if (employees == null)
            return rulesByEmp;
        for (Employee emp : employees) {
            if (emp == null || emp.getId() == null)
                continue;
            try {
                employeeRuleRepository.findByEmployeeId(emp.getId())
                        .ifPresent(rule -> rulesByEmp.put(emp.getId(), rule));
            } catch (Exception ignore) {
            }
        }
        return rulesByEmp;
    }

    // Utilities
    private String timeLabel(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private String safeCode(String code) {
        return (code == null || code.isBlank()) ? "A" : code.trim();
    }

    private LocalTime parseCfgTime(String v, LocalTime def) {
        try {
            return LocalTime.parse(v.length() == 5 ? v + ":00" : v);
        } catch (Exception e) {
            return def;
        }
    }
}
