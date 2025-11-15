package com.example.shiftv1.schedule;

import com.example.shiftv1.breaks.BreakPeriod;
import com.example.shiftv1.breaks.BreakPeriodRepository;
import com.example.shiftv1.breaks.BreakRules;
import com.example.shiftv1.config.PairingSettings;
import com.example.shiftv1.config.PairingSettingsRepository;
import com.example.shiftv1.config.BreakSettings;
import com.example.shiftv1.config.BreakSettingsRepository;
import com.example.shiftv1.demand.DemandInterval;
import com.example.shiftv1.demand.DemandIntervalRepository;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.employee.EmployeeFixedShift;
import com.example.shiftv1.employee.EmployeeFixedShiftRepository;
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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private static final int SHORT_BREAK_INCREMENT_MINUTES = 5;
    private static final ObjectMapper PAIRING_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<PairingDefinitionPayload>> PAIRING_TYPE = new TypeReference<>() {
    };

    private final EmployeeRepository employeeRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final DemandIntervalRepository demandRepository;
    private final SkillRepository skillRepository;
    private final EmployeeRuleRepository employeeRuleRepository;
    private final HolidayRepository holidayRepository;
    private final FreePlaceholderSettings freeSettings;
    private final EmployeeConstraintRepository constraintRepository;
    private final BreakPeriodRepository breakRepository;
    private final BreakSettingsRepository breakSettingsRepository;
    private final ScheduleJobStatusService jobStatusService;
    private final ShiftReservationRepository reservationRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeFixedShiftRepository fixedShiftRepository;
    private final PairingSettingsRepository pairingSettingsRepository;

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
            BreakPeriodRepository breakRepository,
            BreakSettingsRepository breakSettingsRepository,
            ScheduleJobStatusService jobStatusService,
            ShiftReservationRepository reservationRepository,
            LeaveBalanceRepository leaveBalanceRepository,
            LeaveRequestRepository leaveRequestRepository,
            PairingSettingsRepository pairingSettingsRepository,
            EmployeeFixedShiftRepository fixedShiftRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.demandRepository = demandRepository;
        this.skillRepository = skillRepository;
        this.employeeRuleRepository = employeeRuleRepository;
        this.holidayRepository = holidayRepository;
        this.freeSettings = freeSettings;
        this.constraintRepository = constraintRepository;
        this.breakRepository = breakRepository;
        this.breakSettingsRepository = breakSettingsRepository;
        this.jobStatusService = jobStatusService;
        this.reservationRepository = reservationRepository;
        this.leaveBalanceRepository = leaveBalanceRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.pairingSettingsRepository = pairingSettingsRepository;
        this.fixedShiftRepository = fixedShiftRepository;
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
        if (resetMonth) {
            try {
                breakRepository.deleteByAssignment_WorkDateBetween(start, end);
            } catch (Exception e) {
                logger.warn("Failed to delete breaks for {} - {} during reset", start, end, e);
            }
            assignmentRepository.deleteByWorkDateBetween(start, end);
        }
        List<Employee> employees = fetchOrderedEmployees();
        if (employees.isEmpty())
            return Collections.emptyList();
        Map<Long, List<EmployeeFixedShift>> fixedShiftsByEmployee = loadFixedShiftsByEmployee(employees);

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
        PairingRuntime pairingRuntime = loadPairingRuntime();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            final boolean isWkHol = isWeekendOrHoliday(day);
            final boolean dayIsHoliday = isHoliday(day);
            // Track real assignments counted once per day per employee
            Set<Long> assignedToday = new HashSet<>();
            DayContext dayCtx = buildDayContext(day, dayIsHoliday, employees, rulesByEmp);
            List<DemandInterval> demands = demandRepository.findEffectiveForDate(day, day.getDayOfWeek(), dayIsHoliday);
            List<DemandBlock> demandBlocks = prepareDemandBlocks(demands, pairingRuntime);
            if (demandBlocks.isEmpty()) {
                continue;
            }

            List<LocalTime> slots = buildSlots(granularity);
            Map<LocalTime, Integer> requiredBySlot = new HashMap<>();
            Map<LocalTime, Integer> reservedSkillBySlot = new HashMap<>(); // remaining seats reserved for
                                                                           // skill-specific
            for (LocalTime t : slots) {
                requiredBySlot.put(t, 0);
                reservedSkillBySlot.put(t, 0);
            }
            for (DemandBlock block : demandBlocks) {
                int seats = block.seats();
                if (seats <= 0)
                    continue;
                List<LocalTime> cov = slotsCoveredBy(block.start(), block.end(), granularity);
                for (LocalTime t : cov) {
                    requiredBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
                    if (block.skill() != null)
                        reservedSkillBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
                }
            }
            // Build demanded skill IDs per slot for reservation by employee
            Map<LocalTime, Set<Long>> demandedSkillIdsBySlot = new HashMap<>();
            for (LocalTime t : slots)
                demandedSkillIdsBySlot.put(t, new HashSet<>());
            for (DemandBlock block : demandBlocks) {
                Skill blockSkill = block.skill();
                if (blockSkill == null)
                    continue;
                Long sid = blockSkill.getId();
                if (sid == null)
                    continue;
                for (LocalTime t : slotsCoveredBy(block.start(), block.end(), granularity)) {
                    demandedSkillIdsBySlot.get(t).add(sid);
                }
            }

            Map<LocalTime, Integer> assignedBySlot = new HashMap<>();
            for (LocalTime t : slots)
                assignedBySlot.put(t, 0);

            List<ShiftAssignment> fixedAssignments = applyFixedShiftsForDay(
                    day,
                    fixedShiftsByEmployee,
                    dayCtx,
                    assignedBySlot,
                    granularity,
                    workedDaysByEmployee,
                    assignedToday,
                    mtdTotalWorkedDays,
                    mtdWeekendHolidayWorkedDays,
                    isWkHol);
            if (!fixedAssignments.isEmpty()) {
                createdAll.addAll(fixedAssignments);
                rotate += fixedAssignments.size();
            }

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

            for (DemandBlock block : demandBlocks) {
                int seats = block.seats();
                if (seats <= 0)
                    continue;
                LocalTime s = block.start();
                LocalTime e = block.end();
                Skill needSkill = block.skill();
                int blockBreakMinutes = block.breakMinutes();
                String label = buildDemandLabel(needSkill, s, e);

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
                    autoAssignBreaks(a, newly, blockBreakMinutes);
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
        if (resetDay) {
            try {
                breakRepository.deleteByAssignment_WorkDateBetween(date, date);
            } catch (Exception e) {
                logger.warn("Failed to delete breaks for {}", date, e);
            }
            assignmentRepository.deleteByWorkDate(date);
        }
        boolean dateIsHoliday = isHoliday(date);
        List<DemandInterval> demands = demandRepository.findEffectiveForDate(date, date.getDayOfWeek(), dateIsHoliday);
        List<Employee> employees = fetchOrderedEmployees();
        if (employees.isEmpty())
            return Collections.emptyList();
        Map<Long, List<EmployeeFixedShift>> fixedShiftsByEmployee = loadFixedShiftsByEmployee(employees);
        PairingRuntime pairingRuntime = loadPairingRuntime();
        List<DemandBlock> demandBlocks = prepareDemandBlocks(demands, pairingRuntime);
        if (demandBlocks.isEmpty())
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
        DayContext dayCtx = buildDayContext(date, dateIsHoliday, employees, rulesByEmp);
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
        List<LocalTime> slots = buildSlots(granularity);
        Map<LocalTime, Integer> requiredBySlot = new HashMap<>();
        Map<LocalTime, Integer> reservedSkillBySlot = new HashMap<>();
        for (LocalTime t : slots) {
            requiredBySlot.put(t, 0);
            reservedSkillBySlot.put(t, 0);
        }
        for (DemandBlock block : demandBlocks) {
            int seats = block.seats();
            if (seats <= 0)
                continue;
            List<LocalTime> cov = slotsCoveredBy(block.start(), block.end(), granularity);
            for (LocalTime t : cov) {
                requiredBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
                if (block.skill() != null)
                    reservedSkillBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
            }
        }
        Map<LocalTime, Set<Long>> demandedSkillIdsBySlot = new HashMap<>();
        for (LocalTime t : slots)
            demandedSkillIdsBySlot.put(t, new HashSet<>());
        for (DemandBlock block : demandBlocks) {
            Skill blockSkill = block.skill();
            if (blockSkill == null)
                continue;
            Long sid = blockSkill.getId();
            if (sid == null)
                continue;
            for (LocalTime t : slotsCoveredBy(block.start(), block.end(), granularity)) {
                demandedSkillIdsBySlot.get(t).add(sid);
            }
        }

        Map<LocalTime, Integer> assignedBySlot = new HashMap<>();
        for (LocalTime t : slots)
            assignedBySlot.put(t, 0);

        List<ShiftAssignment> fixedAssignments = applyFixedShiftsForDay(
                date,
                fixedShiftsByEmployee,
                dayCtx,
                assignedBySlot,
                granularity,
                workedDaysByEmployee,
                assignedToday,
                mtdTotalWorkedDays,
                mtdWeekendHolidayWorkedDays,
                isWkHol);
        if (!fixedAssignments.isEmpty()) {
            created.addAll(fixedAssignments);
            rotate += fixedAssignments.size();
        }

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

        for (DemandBlock block : demandBlocks) {
            int seats = block.seats();
            if (seats <= 0)
                continue;
            LocalTime s = block.start();
            LocalTime e = block.end();
            Skill needSkill = block.skill();
            int blockBreakMinutes = block.breakMinutes();
            String label = buildDemandLabel(needSkill, s, e);

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
                autoAssignBreaks(a, newly, blockBreakMinutes);
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
        List<Employee> employees = fetchOrderedEmployees();
        List<ScheduleGridEmployeeDto> employeeDtos = employees.stream()
                .map(ScheduleGridEmployeeDto::from)
                .toList();
        Map<Long, List<BreakPeriod>> breaksByAssignment = breakRepository.findByAssignmentWorkDateBetween(rangeStart, rangeEnd)
                .stream()
                .filter(bp -> bp.getAssignment() != null && bp.getAssignment().getId() != null)
                .collect(Collectors.groupingBy(bp -> bp.getAssignment().getId()));
        List<ScheduleGridAssignmentDto> assignments = assignmentRepository.findWithEmployeeBetween(rangeStart, rangeEnd)
                .stream()
                .map(sa -> ScheduleGridAssignmentDto.from(sa, breaksByAssignment.getOrDefault(sa.getId(), List.of())))
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
            applyBreakChanges(entity, payload, true, false, 0);
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
            applyBreakChanges(entity, payload, false, true, 0);
            registerWindow(workingState, targetEmployee, targetDate, entity);
            updated++;
        }

        if (!deleteSet.isEmpty()) {
            List<ShiftAssignment> toDelete = assignmentRepository.findAllById(deleteSet);
            List<Long> deleteIds = toDelete.stream()
                    .map(ShiftAssignment::getId)
                    .filter(Objects::nonNull)
                    .toList();
            if (!deleteIds.isEmpty()) {
                breakRepository.deleteByAssignment_IdIn(deleteIds);
            }
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

    private void applyBreakChanges(ShiftAssignment assignment,
                                   ScheduleGridBulkRequest.BasePayload payload,
                                   boolean autoWhenMissing,
                                   boolean removeWhenMissing,
                                   int seatIndex) {
        if (assignment == null) {
            return;
        }
        List<ScheduleGridBulkRequest.BreakPayload> requestedBreaks = payload != null ? payload.getBreaks() : List.of();
        if (!requestedBreaks.isEmpty()) {
            upsertBreaksFromPayload(assignment, requestedBreaks);
            return;
        }
        LocalTime breakStart = payload != null ? payload.getBreakStart() : null;
        LocalTime breakEnd = payload != null ? payload.getBreakEnd() : null;
        if (breakStart != null || breakEnd != null) {
            validateBreakRange(breakStart, breakEnd, assignment.getStartTime(), assignment.getEndTime());
            upsertBreakPeriod(assignment, BreakPeriod.BreakType.LUNCH, breakStart, breakEnd, false);
        } else if (removeWhenMissing) {
            deleteAllBreaksForAssignment(assignment.getId());
        } else if (autoWhenMissing) {
            autoAssignBreaks(assignment, seatIndex, null);
        }
    }

    private void autoAssignBreaks(ShiftAssignment assignment, int seatIndex, Integer requestedMinutes) {
        if (assignment == null) {
            return;
        }
        BreakRules.BreakWindow lunchWindow = autoAssignLunchBreak(assignment, seatIndex, requestedMinutes);
        autoAssignShortBreak(assignment, lunchWindow);
    }

    private BreakRules.BreakWindow autoAssignLunchBreak(ShiftAssignment assignment, int seatIndex, Integer requestedMinutes) {
        if (assignment == null || isPlaceholder(assignment)) {
            deleteBreaksByType(assignment != null ? assignment.getId() : null, BreakPeriod.BreakType.LUNCH);
            return null;
        }
        LocalTime start = assignment.getStartTime();
        LocalTime end = assignment.getEndTime();
        int minutes = BreakRules.normalizeMinutes(requestedMinutes, start, end);
        if (minutes <= 0) {
            deleteBreaksByType(assignment.getId(), BreakPeriod.BreakType.LUNCH);
            return null;
        }
        BreakRules.BreakWindow window = BreakRules.planWindow(start, end, minutes, seatIndex);
        if (window == null) {
            deleteBreaksByType(assignment.getId(), BreakPeriod.BreakType.LUNCH);
            return null;
        }
        upsertBreakPeriod(assignment, BreakPeriod.BreakType.LUNCH, window.start(), window.end(), true);
        return window;
    }

    private void autoAssignShortBreak(ShiftAssignment assignment, BreakRules.BreakWindow lunchWindow) {
        if (assignment == null || isPlaceholder(assignment)) {
            deleteBreaksByType(assignment != null ? assignment.getId() : null, BreakPeriod.BreakType.SHORT);
            return;
        }
        BreakSettings settings = breakSettingsRepository.findAll().stream().findFirst().orElse(null);
        if (settings == null || !Boolean.TRUE.equals(settings.getShortBreakEnabled())) {
            deleteBreaksByType(assignment.getId(), BreakPeriod.BreakType.SHORT);
            return;
        }
        int minutes = Math.max(5, Optional.ofNullable(settings.getShortBreakMinutes()).orElse(15));
        int minShiftMinutes = Math.max(60, Optional.ofNullable(settings.getMinShiftMinutes()).orElse(180));
        LocalTime start = assignment.getStartTime();
        LocalTime end = assignment.getEndTime();
        if (start == null || end == null) {
            deleteBreaksByType(assignment.getId(), BreakPeriod.BreakType.SHORT);
            return;
        }
        long duration = ChronoUnit.MINUTES.between(start, end);
        if (duration < minShiftMinutes) {
            deleteBreaksByType(assignment.getId(), BreakPeriod.BreakType.SHORT);
            return;
        }
        Integer shortStartMinutes = null;
        int shiftStartMinutes = toMinutes(start);
        int shiftEndMinutes = toMinutes(end);
        if (lunchWindow != null && lunchWindow.end() != null) {
            int lunchEndMinutes = toMinutes(lunchWindow.end());
            long remaining = ChronoUnit.MINUTES.between(lunchWindow.end(), end);
            long offset = Math.max(0, (remaining - minutes) / 2);
            shortStartMinutes = lunchEndMinutes + (int) offset;
        } else if (Boolean.TRUE.equals(settings.getApplyToShortShifts())) {
            long offset = Math.max(0, (duration - minutes) / 2);
            shortStartMinutes = shiftStartMinutes + (int) offset;
        } else {
            deleteBreaksByType(assignment.getId(), BreakPeriod.BreakType.SHORT);
            return;
        }
        if (shortStartMinutes == null) {
            deleteBreaksByType(assignment.getId(), BreakPeriod.BreakType.SHORT);
            return;
        }
        shortStartMinutes = roundToIncrementMinutes(shortStartMinutes, SHORT_BREAK_INCREMENT_MINUTES);
        if (shortStartMinutes < shiftStartMinutes) {
            shortStartMinutes = shiftStartMinutes;
        }
        if (shortStartMinutes > shiftEndMinutes - minutes) {
            shortStartMinutes = shiftEndMinutes - minutes;
        }
        if (shortStartMinutes < shiftStartMinutes || shortStartMinutes + minutes > shiftEndMinutes) {
            deleteBreaksByType(assignment.getId(), BreakPeriod.BreakType.SHORT);
            return;
        }
        LocalTime breakStart = convertToLocalTime(shortStartMinutes);
        LocalTime breakEnd = breakStart.plusMinutes(minutes);
        upsertBreakPeriod(assignment, BreakPeriod.BreakType.SHORT, breakStart, breakEnd, true);
    }

    private void upsertBreakPeriod(ShiftAssignment assignment,
                                   BreakPeriod.BreakType type,
                                   LocalTime breakStart,
                                   LocalTime breakEnd,
                                   boolean autoGenerated) {
        if (assignment == null || breakStart == null || breakEnd == null) {
            return;
        }
        BreakPeriod period = null;
        if (assignment.getId() != null) {
            period = breakRepository.findByAssignment_Id(assignment.getId()).stream()
                    .filter(bp -> bp.getType() == type)
                    .findFirst()
                    .orElse(null);
        }
        if (period == null) {
            period = new BreakPeriod(assignment, type, breakStart, breakEnd);
        } else {
            period.setAssignment(assignment);
            period.setType(type);
            period.setStartTime(breakStart);
            period.setEndTime(breakEnd);
        }
        period.setAutoGenerated(autoGenerated);
        breakRepository.save(period);
    }

    private void upsertBreaksFromPayload(ShiftAssignment assignment, List<ScheduleGridBulkRequest.BreakPayload> payloads) {
        if (assignment == null || payloads == null) {
            return;
        }
        Map<Long, BreakPeriod> existing = assignment.getId() == null ? Collections.emptyMap()
                : breakRepository.findByAssignment_Id(assignment.getId()).stream()
                .filter(bp -> bp.getId() != null)
                .collect(Collectors.toMap(BreakPeriod::getId, bp -> bp));
        Set<Long> keep = new HashSet<>();
        for (ScheduleGridBulkRequest.BreakPayload payload : payloads) {
            if (payload == null) continue;
            LocalTime start = payload.getStart();
            LocalTime end = payload.getEnd();
            validateBreakRange(start, end, assignment.getStartTime(), assignment.getEndTime());
            BreakPeriod.BreakType type = resolveBreakType(payload.getType());
            BreakPeriod period = payload.getId() != null ? existing.get(payload.getId()) : null;
            if (period == null) {
                period = new BreakPeriod(assignment, type, start, end);
            } else {
                period.setAssignment(assignment);
                period.setType(type);
                period.setStartTime(start);
                period.setEndTime(end);
            }
            period.setAutoGenerated(Boolean.TRUE.equals(payload.getAutoGenerated()));
            breakRepository.save(period);
            if (period.getId() != null) {
                keep.add(period.getId());
            }
        }
        if (assignment.getId() != null) {
            List<Long> toDelete = existing.keySet().stream()
                    .filter(id -> !keep.contains(id))
                    .toList();
            if (!toDelete.isEmpty()) {
                breakRepository.deleteAllById(toDelete);
            }
        }
    }

    private BreakPeriod.BreakType resolveBreakType(String raw) {
        if (raw == null || raw.isBlank()) {
            return BreakPeriod.BreakType.SHORT;
        }
        try {
            return BreakPeriod.BreakType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return BreakPeriod.BreakType.SHORT;
        }
    }

    private void deleteBreaksByType(Long assignmentId, BreakPeriod.BreakType type) {
        if (assignmentId == null) {
            return;
        }
        List<BreakPeriod> existing = breakRepository.findByAssignment_Id(assignmentId);
        List<BreakPeriod> targets = existing.stream()
                .filter(bp -> bp.getType() == type)
                .toList();
        if (!targets.isEmpty()) {
            breakRepository.deleteAll(targets);
        }
    }

    private void deleteAllBreaksForAssignment(Long assignmentId) {
        if (assignmentId == null) {
            return;
        }
        breakRepository.deleteByAssignment_IdIn(List.of(assignmentId));
    }

    private boolean isPlaceholder(ShiftAssignment assignment) {
        if (assignment == null) {
            return true;
        }
        return Boolean.TRUE.equals(assignment.getIsFree())
                || Boolean.TRUE.equals(assignment.getIsOff())
                || Boolean.TRUE.equals(assignment.getIsLeave());
    }

    private void validateBreakRange(LocalTime breakStart, LocalTime breakEnd, LocalTime shiftStart, LocalTime shiftEnd) {
        if (breakStart == null && breakEnd == null) {
            return;
        }
        if (breakStart == null || breakEnd == null || !breakStart.isBefore(breakEnd)) {
            throw new BusinessException("GRID_BREAK_INVALID", "休憩の開始・終了時刻を正しく入力してください");
        }
        if (shiftStart == null || shiftEnd == null) {
            throw new BusinessException("GRID_BREAK_INVALID", "勤務時間が未設定のため休憩を指定できません");
        }
        if (breakStart.isBefore(shiftStart) || breakEnd.isAfter(shiftEnd)) {
            throw new BusinessException("GRID_BREAK_RANGE", "休憩は勤務時間内に設定してください");
        }
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

    private int toMinutes(LocalTime time) {
        if (time == null) return 0;
        return time.getHour() * 60 + time.getMinute();
    }

    private LocalTime convertToLocalTime(int minutes) {
        int clamped = Math.max(0, minutes);
        int hours = clamped / 60;
        int mins = clamped % 60;
        hours = Math.min(hours, 23);
        return LocalTime.of(hours, mins);
    }

    private int roundToIncrementMinutes(int minutes, int increment) {
        if (increment <= 0) return minutes;
        int rounded = (int) Math.round(minutes / (double) increment) * increment;
        if (rounded < 0) rounded = 0;
        return rounded;
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

    private List<ShiftAssignment> applyFixedShiftsForDay(LocalDate day,
                                                         Map<Long, List<EmployeeFixedShift>> fixedShiftsByEmployee,
                                                         DayContext dayCtx,
                                                         Map<LocalTime, Integer> assignedBySlot,
                                                         int granularityMinutes,
                                                         Map<Long, Set<LocalDate>> workedDaysByEmployee,
                                                         Set<Long> assignedToday,
                                                         Map<Long, Integer> mtdTotalWorkedDays,
                                                         Map<Long, Integer> mtdWeekendHolidayWorkedDays,
                                                         boolean isWeekendOrHoliday) {
        if (fixedShiftsByEmployee == null || fixedShiftsByEmployee.isEmpty()) {
            return Collections.emptyList();
        }
        List<ShiftAssignment> created = new ArrayList<>();
        Map<Long, List<ShiftAssignment>> dayAssignmentsCache = new HashMap<>();
        java.time.DayOfWeek targetDow = day.getDayOfWeek();
        for (Map.Entry<Long, List<EmployeeFixedShift>> entry : fixedShiftsByEmployee.entrySet()) {
            Long empId = entry.getKey();
            List<EmployeeFixedShift> defs = entry.getValue();
            if (defs == null || defs.isEmpty())
                continue;
            for (EmployeeFixedShift def : defs) {
                if (def == null || def.getDayOfWeek() == null)
                    continue;
                if (!Boolean.TRUE.equals(def.getActive()))
                    continue;
                if (!def.getDayOfWeek().equals(targetDow))
                    continue;
                LocalTime start = def.getStartTime();
                LocalTime end = def.getEndTime();
                if (start == null || end == null || !start.isBefore(end))
                    continue;
                if (dayCtx.excludeByPatternStrict.getOrDefault(empId, false))
                    continue;
                if (dayCtx.hardUnavailable.getOrDefault(empId, false))
                    continue;
                Employee employee = def.getEmployee();
                if (employee == null || employee.getId() == null)
                    continue;
                List<ShiftAssignment> existingDay = dayAssignmentsCache.computeIfAbsent(empId,
                        k -> assignmentRepository.findByEmployeeAndWorkDate(employee, day));
                boolean conflict = false;
                if (existingDay != null) {
                    for (ShiftAssignment existing : existingDay) {
                        if (existing.getStartTime() == null || existing.getEndTime() == null)
                            continue;
                        if (start.isBefore(existing.getEndTime()) && end.isAfter(existing.getStartTime())) {
                            conflict = true;
                            break;
                        }
                    }
                }
                if (conflict)
                    continue;
                ShiftAssignment assignment = new ShiftAssignment(day, def.defaultLabel(), start, end, employee);
                int breakOrder = existingDay == null ? 0 : existingDay.size();
                assignmentRepository.save(assignment);
            autoAssignBreaks(assignment, breakOrder, null);
                if (existingDay != null)
                    existingDay.add(assignment);
                created.add(assignment);

                workedDaysByEmployee.computeIfAbsent(empId, k -> new HashSet<>()).add(day);
                if (assignedToday.add(empId)) {
                    mtdTotalWorkedDays.merge(empId, 1, Integer::sum);
                    if (isWeekendOrHoliday) {
                        mtdWeekendHolidayWorkedDays.merge(empId, 1, Integer::sum);
                    }
                }
                List<LocalTime> covers = slotsCoveredBy(start, end, granularityMinutes);
                for (LocalTime slot : covers) {
                    assignedBySlot.compute(slot, (k, v) -> (v == null ? 0 : v) + 1);
                }
            }
        }
        return created;
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
            autoAssignBreaks(assignment, created.size(), null);
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
        List<Employee> employees = fetchOrderedEmployees();
        if (employees == null || employees.isEmpty())
            return;
        LocalTime freeStart = parseCfgTime(cfgFreeStart, LocalTime.MIDNIGHT);
        LocalTime freeEnd = parseCfgTime(cfgFreeEnd, LocalTime.of(0, 5));
        boolean onlyWeekdays = freeSettings != null ? freeSettings.isOnlyWeekdays() : cfgFreeOnlyWeekdays;
        boolean skipHolidays = freeSettings != null ? freeSettings.isSkipHolidays() : cfgFreeSkipHolidays;
        List<DaySlot> primaryDays = new ArrayList<>();
        List<DaySlot> secondaryDays = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            boolean isWeekend = day.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            boolean isHolidayDay = skipHolidays && isHoliday(day);
            boolean preferLater = (onlyWeekdays && isWeekend) || (skipHolidays && isHolidayDay);
            if (preferLater) {
                secondaryDays.add(new DaySlot(day, isHolidayDay));
            } else {
                primaryDays.add(new DaySlot(day, isHolidayDay));
            }
        }
        processFreePlaceholderDays(primaryDays, employees, freeStart, freeEnd);
        processFreePlaceholderDays(secondaryDays, employees, freeStart, freeEnd);
    }
    private record DaySlot(LocalDate date, boolean isHoliday) {}
    private void processFreePlaceholderDays(List<DaySlot> days,
                                            List<Employee> employees,
                                            LocalTime freeStart,
                                            LocalTime freeEnd) {
        if (days == null || days.isEmpty())
            return;
        for (DaySlot slot : days) {
            LocalDate day = slot.date();
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
        List<Employee> employees = fetchOrderedEmployees();
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
        List<Employee> employees = fetchOrderedEmployees();
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
                                       boolean isHoliday,
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

        // 3) Employees who opted out of holiday work are treated as hard unavailable on holidays
        if (isHoliday) {
            for (Employee emp : employees) {
                if (emp == null || emp.getId() == null)
                    continue;
                EmployeeRule rule = rulesByEmp != null ? rulesByEmp.get(emp.getId()) : null;
                boolean allow = rule == null || rule.getAllowHolidayWork() == null || rule.getAllowHolidayWork();
                if (!allow) {
                    ctx.hardUnavailable.put(emp.getId(), true);
                }
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

    private Map<Long, List<EmployeeFixedShift>> loadFixedShiftsByEmployee(List<Employee> employees) {
        Map<Long, List<EmployeeFixedShift>> map = new HashMap<>();
        if (employees == null || employees.isEmpty())
            return map;
        List<Long> ids = employees.stream()
                .map(Employee::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty())
            return map;
        List<EmployeeFixedShift> all = fixedShiftRepository.findByEmployeeIdIn(ids);
        for (EmployeeFixedShift shift : all) {
            if (shift == null || shift.getEmployee() == null || shift.getEmployee().getId() == null)
                continue;
            map.computeIfAbsent(shift.getEmployee().getId(), k -> new ArrayList<>()).add(shift);
        }
        return map;
    }

    private List<Employee> fetchOrderedEmployees() {
        try {
            return employeeRepository.findAllOrdered();
        } catch (Exception e) {
            return employeeRepository.findAll();
        }
    }

    private List<DemandBlock> prepareDemandBlocks(List<DemandInterval> raw,
                                                  PairingRuntime pairingRuntime) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<MutableDemandBlock> merged = mergeDemands(raw);
        List<DemandBlock> blocks;
        if (pairingRuntime.canPair()) {
            blocks = applyPairing(merged, pairingRuntime);
        } else {
            blocks = merged.stream()
                    .filter(b -> b.seats() > 0)
                    .map(MutableDemandBlock::toDemandBlock)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        sortDemandBlocks(blocks);
        return blocks;
    }

    private List<MutableDemandBlock> mergeDemands(List<DemandInterval> raw) {
        Map<String, MutableDemandBlock> merged = new LinkedHashMap<>();
        for (DemandInterval d : raw) {
            if (d == null)
                continue;
            LocalTime start = d.getStartTime();
            LocalTime end = d.getEndTime();
            Integer seats = d.getRequiredSeats();
            if (start == null || end == null || seats == null || seats <= 0 || !start.isBefore(end))
                continue;
            Skill skill = d.getSkill();
            String key = demandKey(skill, start, end);
            MutableDemandBlock block = merged.computeIfAbsent(key,
                    k -> new MutableDemandBlock(skill, start, end, 0));
            block.increment(seats);
            block.mergeBreakMinutes(BreakRules.normalizeMinutes(d.getBreakMinutes(), start, end));
        }
        return new ArrayList<>(merged.values());
    }

    private String demandKey(Skill skill, LocalTime start, LocalTime end) {
        Long id = skill != null ? skill.getId() : null;
        return (id == null ? "GENERIC" : id.toString()) + "|" + start + "|" + end;
    }

    private List<DemandBlock> applyPairing(List<MutableDemandBlock> baseBlocks, PairingRuntime runtime) {
        Map<Long, List<MutableDemandBlock>> bySkill = new LinkedHashMap<>();
        for (MutableDemandBlock block : baseBlocks) {
            bySkill.computeIfAbsent(block.skillId(), k -> new ArrayList<>()).add(block);
        }
        List<DemandBlock> result = new ArrayList<>();
        for (List<MutableDemandBlock> blocks : bySkill.values()) {
            result.addAll(applyPairingForSkill(blocks, runtime));
        }
        return result;
    }

    private List<DemandBlock> applyPairingForSkill(List<MutableDemandBlock> blocks, PairingRuntime runtime) {
        List<DemandBlock> result = new ArrayList<>();
        for (PairingPreset preset : runtime.presets()) {
            MutableDemandBlock morning = findMatching(blocks, preset.morning(), runtime.toleranceMinutes());
            MutableDemandBlock afternoon = findMatching(blocks, preset.afternoon(), runtime.toleranceMinutes());
            if (morning == null || afternoon == null)
                continue;
            int pairs = Math.min(morning.seats(), afternoon.seats());
            if (pairs <= 0)
                continue;
            int pairBreakMinutes = BreakRules.normalizeMinutes(null, preset.full().start(), preset.full().end());
            result.add(new DemandBlock(preset.full().start(), preset.full().end(), morning.skill(), pairs, pairBreakMinutes));
            morning.decrement(pairs);
            afternoon.decrement(pairs);
        }
        for (MutableDemandBlock block : blocks) {
            if (block.seats() > 0) {
                result.add(block.toDemandBlock());
            }
        }
        return result;
    }

    private MutableDemandBlock findMatching(List<MutableDemandBlock> blocks, TimeWindow target, int tolerance) {
        if (target == null)
            return null;
        for (MutableDemandBlock block : blocks) {
            if (block.seats() <= 0)
                continue;
            if (matchesWindow(block.start(), block.end(), target, tolerance))
                return block;
        }
        return null;
    }

    private boolean matchesWindow(LocalTime start, LocalTime end, TimeWindow target, int toleranceMinutes) {
        if (target == null)
            return false;
        if (toleranceMinutes <= 0) {
            return start.equals(target.start()) && end.equals(target.end());
        }
        return Math.abs(ChronoUnit.MINUTES.between(start, target.start())) <= toleranceMinutes
                && Math.abs(ChronoUnit.MINUTES.between(end, target.end())) <= toleranceMinutes;
    }

    private void sortDemandBlocks(List<DemandBlock> blocks) {
        blocks.sort((a, b) -> {
            boolean aGeneric = (a.skill() == null);
            boolean bGeneric = (b.skill() == null);
            if (aGeneric != bGeneric)
                return aGeneric ? 1 : -1;
            int ap = skillPriority(a.skill());
            int bp = skillPriority(b.skill());
            if (ap != bp)
                return Integer.compare(bp, ap);
            int startCompare = a.start().compareTo(b.start());
            if (startCompare != 0)
                return startCompare;
            return a.end().compareTo(b.end());
        });
    }

    private int skillPriority(Skill skill) {
        return (skill != null && skill.getPriority() != null) ? skill.getPriority() : 0;
    }

    private PairingRuntime loadPairingRuntime() {
        PairingSettings settings = null;
        try {
            settings = pairingSettingsRepository.findAll().stream().findFirst().orElse(null);
        } catch (Exception e) {
            logger.warn("Failed to load pairing settings", e);
            return PairingRuntime.disabled();
        }
        if (settings == null || !Boolean.TRUE.equals(settings.getEnabled())) {
            return PairingRuntime.disabled();
        }
        int tolerance = Optional.ofNullable(settings.getPairToleranceMinutes()).orElse(0);
        tolerance = Math.max(0, tolerance);
        List<PairingPreset> presets = new ArrayList<>();
        for (PairingDefinitionPayload payload : parsePairingDefinitions(settings.getSkillPairings())) {
            PairingPreset preset = toPreset(payload);
            if (preset != null) {
                presets.add(preset);
            }
        }
        if (presets.isEmpty()) {
            PairingPreset legacy = toPreset(settings.getFullWindow(), settings.getMorningWindow(),
                    settings.getAfternoonWindow(), "既定");
            if (legacy != null) {
                presets.add(legacy);
            }
        }
        if (presets.isEmpty()) {
            return PairingRuntime.disabled();
        }
        return new PairingRuntime(true, tolerance, List.copyOf(presets));
    }

    private List<PairingDefinitionPayload> parsePairingDefinitions(String raw) {
        if (raw == null || raw.isBlank())
            return Collections.emptyList();
        try {
            List<PairingDefinitionPayload> payloads = PAIRING_MAPPER.readValue(raw, PAIRING_TYPE);
            return payloads == null ? Collections.emptyList() : payloads;
        } catch (Exception e) {
            logger.warn("Failed to parse pairing definitions", e);
            return Collections.emptyList();
        }
    }

    private PairingPreset toPreset(PairingDefinitionPayload payload) {
        if (payload == null)
            return null;
        return toPreset(payload.fullWindow(), payload.morningWindow(), payload.afternoonWindow(), payload.name());
    }

    private PairingPreset toPreset(String fullWindow, String morningWindow, String afternoonWindow, String name) {
        TimeWindow full = parseWindow(fullWindow);
        TimeWindow morning = parseWindow(morningWindow);
        TimeWindow afternoon = parseWindow(afternoonWindow);
        if (full == null || morning == null || afternoon == null) {
            return null;
        }
        return new PairingPreset(name == null || name.isBlank() ? "Pair" : name.trim(), full, morning, afternoon);
    }

    private TimeWindow parseWindow(String raw) {
        if (raw == null || raw.isBlank())
            return null;
        String[] parts = raw.split("-");
        if (parts.length != 2)
            return null;
        try {
            LocalTime start = LocalTime.parse(parts[0].trim());
            LocalTime end = LocalTime.parse(parts[1].trim());
            if (!start.isBefore(end))
                return null;
            return new TimeWindow(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildDemandLabel(Skill skill, LocalTime start, LocalTime end) {
        String label;
        if (skill != null) {
            label = skill.getName();
            if (label == null || label.isBlank()) {
                label = safeCode(skill.getCode());
            }
        } else {
            label = "汎用";
        }
        return String.format("需要枠(%s)", label);
    }

    private record DemandBlock(LocalTime start, LocalTime end, Skill skill, int seats, int breakMinutes) {
    }

    private static final class MutableDemandBlock {
        private final Skill skill;
        private final Long skillId;
        private final LocalTime start;
        private final LocalTime end;
        private int seats;
        private int breakMinutes;

        private MutableDemandBlock(Skill skill, LocalTime start, LocalTime end, int seats) {
            this.skill = skill;
            this.skillId = (skill == null ? null : skill.getId());
            this.start = start;
            this.end = end;
            this.seats = seats;
            this.breakMinutes = 0;
        }

        private void increment(int delta) {
            this.seats += delta;
        }

        private void decrement(int delta) {
            this.seats = Math.max(0, this.seats - delta);
        }

        private int seats() {
            return seats;
        }

        private Skill skill() {
            return skill;
        }

        private Long skillId() {
            return skillId;
        }

        private LocalTime start() {
            return start;
        }

        private LocalTime end() {
            return end;
        }

        private DemandBlock toDemandBlock() {
            return new DemandBlock(start, end, skill, seats, Math.max(0, breakMinutes));
        }

        private void mergeBreakMinutes(int minutes) {
            if (minutes <= 0) {
                return;
            }
            this.breakMinutes = Math.max(this.breakMinutes, minutes);
        }
    }

    private record PairingRuntime(boolean enabled, int toleranceMinutes, List<PairingPreset> presets) {
        static PairingRuntime disabled() {
            return new PairingRuntime(false, 0, List.of());
        }

        boolean canPair() {
            return enabled && presets != null && !presets.isEmpty();
        }
    }

    private record PairingPreset(String name, TimeWindow full, TimeWindow morning, TimeWindow afternoon) {
    }

    private record TimeWindow(LocalTime start, LocalTime end) {
    }

    private record PairingDefinitionPayload(String name, String fullWindow, String morningWindow,
                                            String afternoonWindow) {
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
