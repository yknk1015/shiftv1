package com.example.shiftv1.schedule;

import com.example.shiftv1.demand.DemandInterval;
import com.example.shiftv1.demand.DemandIntervalRepository;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.employee.EmployeeRepository;
import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;
import com.example.shiftv1.employee.EmployeeRuleRepository;
import com.example.shiftv1.employee.EmployeeRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.*;

@Service
public class ScheduleService {
    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);

    private final EmployeeRepository employeeRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final DemandIntervalRepository demandRepository;
    private final SkillRepository skillRepository;
    private final EmployeeRuleRepository employeeRuleRepository;

    @Value("${shift.placeholder.free.start:00:00}")
    private String cfgFreeStart;
    @Value("${shift.placeholder.free.end:00:05}")
    private String cfgFreeEnd;
    @Value("${shift.placeholder.off.start:00:00}")
    private String cfgOffStart;
    @Value("${shift.placeholder.off.end:00:05}")
    private String cfgOffEnd;

    public ScheduleService(EmployeeRepository employeeRepository,
                           ShiftAssignmentRepository assignmentRepository,
                           DemandIntervalRepository demandRepository,
                           SkillRepository skillRepository,
                           EmployeeRuleRepository employeeRuleRepository) {
        this.employeeRepository = employeeRepository;
        this.assignmentRepository = assignmentRepository;
        this.demandRepository = demandRepository;
        this.skillRepository = skillRepository;
        this.employeeRuleRepository = employeeRuleRepository;
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
        if (resetMonth) assignmentRepository.deleteByWorkDateBetween(start, end);
        if (employeeRepository.findAll().isEmpty()) return Collections.emptyList();

        int rotate = 0;
        List<ShiftAssignment> createdAll = new ArrayList<>();
        int granularity = 60;

        // Preload per-employee allowed working days per week (7 - weeklyRestDays)
        Map<Long, Integer> allowedWorkDaysPerWeek = new HashMap<>();
        for (var emp : employeeRepository.findAll()) {
            int rest = employeeRuleRepository.findByEmployeeId(emp.getId())
                    .map(EmployeeRule::getWeeklyRestDays)
                    .filter(v -> v != null && v >= 0)
                    .orElse(2);
            int allowed = Math.max(0, 7 - rest);
            allowedWorkDaysPerWeek.put(emp.getId(), allowed);
        }
        // Track worked days (real assignments only) per employee across spillover weeks (Sun..Sat)
        LocalDate outerStart = weekStartSunday(start);
        LocalDate outerEnd = weekStartSunday(end).plusDays(6);
        Map<Long, Set<LocalDate>> workedDaysByEmployee = new HashMap<>();
        for (ShiftAssignment sa : assignmentRepository.findByWorkDateBetween(outerStart, outerEnd)) {
            boolean isFree = Boolean.TRUE.equals(sa.getIsFree()) || (sa.getShiftName() != null && "FREE".equalsIgnoreCase(sa.getShiftName()));
            boolean isOff = Boolean.TRUE.equals(sa.getIsOff()) || (sa.getShiftName() != null && ("休日".equals(sa.getShiftName()) || "OFF".equalsIgnoreCase(sa.getShiftName())));
            if (isFree || isOff) continue;
            Long empId = sa.getEmployee() != null ? sa.getEmployee().getId() : null;
            if (empId == null) continue;
            workedDaysByEmployee.computeIfAbsent(empId, k -> new HashSet<>()).add(sa.getWorkDate());
        }
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            List<DemandInterval> demands = demandRepository.findEffectiveForDate(day, day.getDayOfWeek());
            // Process skill-specific first (by higher priority), then generic
            demands.sort((a, b) -> {
                boolean aGeneric = (a.getSkill() == null);
                boolean bGeneric = (b.getSkill() == null);
                if (aGeneric != bGeneric) return aGeneric ? 1 : -1; // skill first
                int ap = (a.getSkill() != null && a.getSkill().getPriority() != null) ? a.getSkill().getPriority() : 0;
                int bp = (b.getSkill() != null && b.getSkill().getPriority() != null) ? b.getSkill().getPriority() : 0;
                if (ap != bp) return Integer.compare(bp, ap); // higher first
                int c1 = a.getStartTime().compareTo(b.getStartTime());
                if (c1 != 0) return c1;
                return a.getEndTime().compareTo(b.getEndTime());
            });

            List<LocalTime> slots = buildSlots(granularity);
            Map<LocalTime, Integer> requiredBySlot = new HashMap<>();
            Map<LocalTime, Integer> reservedSkillBySlot = new HashMap<>(); // remaining seats reserved for skill-specific
            for (LocalTime t : slots) { requiredBySlot.put(t, 0); reservedSkillBySlot.put(t, 0); }
            for (DemandInterval d : demands) {
                int seats = Optional.ofNullable(d.getRequiredSeats()).orElse(0);
                if (seats <= 0) continue;
                List<LocalTime> cov = slotsCoveredBy(d.getStartTime(), d.getEndTime(), granularity);
                for (LocalTime t : cov) {
                    requiredBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
                    if (d.getSkill() != null) reservedSkillBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
                }
            }
            // Build demanded skill IDs per slot for reservation by employee
            Map<LocalTime, Set<Long>> demandedSkillIdsBySlot = new HashMap<>();
            for (LocalTime t : slots) demandedSkillIdsBySlot.put(t, new HashSet<>());
            for (DemandInterval d : demands) {
                if (d.getSkill() == null) continue;
                Long sid = d.getSkill().getId();
                if (sid == null) continue;
                for (LocalTime t : slotsCoveredBy(d.getStartTime(), d.getEndTime(), granularity)) {
                    demandedSkillIdsBySlot.get(t).add(sid);
                }
            }

            Map<LocalTime, Integer> assignedBySlot = new HashMap<>();
            for (LocalTime t : slots) assignedBySlot.put(t, 0);

            for (DemandInterval d : demands) {
                Integer seatsObj = d.getRequiredSeats();
                if (seatsObj == null || seatsObj <= 0) continue;
                LocalTime s = d.getStartTime();
                LocalTime e = d.getEndTime();
                Skill needSkill = d.getSkill();
                String code = needSkill != null ? safeCode(needSkill.getCode()) : "A";
                String label = String.format("Demand-%s %s-%s", code, timeLabel(s), timeLabel(e));

                List<Employee> avail = assignmentRepository.findAvailableEmployeesForTimeSlot(day, s, e);
                if (needSkill != null) {
                    Long sid = needSkill.getId();
                    avail = avail.stream().filter(emp -> emp.getSkills() != null && emp.getSkills().stream().anyMatch(sk -> Objects.equals(sk.getId(), sid))).toList();
                }
                if (avail.isEmpty()) continue;

                // For generic demand, preserve skilled employees if critically needed in these slots
                if (needSkill == null) {
                    List<LocalTime> covers = slotsCoveredBy(s, e, granularity);
                    Map<LocalTime, Integer> skilledAvailCount = new HashMap<>();
                    for (LocalTime t : covers) skilledAvailCount.put(t, 0);
                    for (Employee emp : avail) {
                        for (LocalTime t : covers) {
                            Set<Long> ds = demandedSkillIdsBySlot.getOrDefault(t, Collections.emptySet());
                            boolean empSkilled = emp.getSkills() != null && emp.getSkills().stream().anyMatch(sk -> ds.contains(sk.getId()));
                            if (empSkilled) skilledAvailCount.compute(t, (k, v) -> (v == null ? 0 : v) + 1);
                        }
                    }
                    List<Employee> filtered = new ArrayList<>();
                    for (Employee emp : avail) {
                        boolean reserved = false;
                        for (LocalTime t : covers) {
                            int reservedSeats = reservedSkillBySlot.getOrDefault(t, 0);
                            if (reservedSeats <= 0) continue;
                            Set<Long> ds = demandedSkillIdsBySlot.getOrDefault(t, Collections.emptySet());
                            boolean empSkilled = emp.getSkills() != null && emp.getSkills().stream().anyMatch(sk -> ds.contains(sk.getId()));
                            if (empSkilled) {
                                int skilledCount = skilledAvailCount.getOrDefault(t, 0);
                                if (skilledCount <= reservedSeats) { reserved = true; break; }
                            }
                        }
                        if (!reserved) filtered.add(emp);
                    }
                    if (!filtered.isEmpty()) avail = filtered; // fallback to original if empty
                }

                // Enforce weekly rest-days: in a Sunday-Saturday window, limit distinct workdays to allowed
                final LocalDate weekStart = weekStartSunday(day);
                final LocalDate weekEnd = weekStart.plusDays(6);
                List<Employee> weeklyOk = new ArrayList<>();
                for (Employee emp : avail) {
                    Long empId = emp.getId();
                    int allowedDays = allowedWorkDaysPerWeek.getOrDefault(empId, 5);
                    Set<LocalDate> days = workedDaysByEmployee.getOrDefault(empId, Collections.emptySet());
                    int used = 0;
                    for (LocalDate d0 : days) {
                        if (!d0.isBefore(weekStart) && !d0.isAfter(weekEnd)) used++;
                    }
                    boolean alreadyToday = days.contains(day);
                    if (used < allowedDays || alreadyToday) {
                        weeklyOk.add(emp);
                    }
                }
                if (weeklyOk.isEmpty()) continue;
                avail = weeklyOk;

                List<Employee> rotated = new ArrayList<>(avail);
                if (!rotated.isEmpty()) {
                    int r = rotated.size() == 0 ? 0 : (rotate % rotated.size());
                    if (r != 0) Collections.rotate(rotated, -r);
                }
                List<LocalTime> covers = slotsCoveredBy(s, e, granularity);
                int newly = 0;
                int seats = seatsObj;
                for (Employee emp : rotated) {
                    if (newly >= seats) break;
                    boolean fits = true;
                    for (LocalTime t : covers) {
                        int req = requiredBySlot.getOrDefault(t, 0);
                        int asn = assignedBySlot.getOrDefault(t, 0);
                        int reserved = reservedSkillBySlot.getOrDefault(t, 0);
                        int cap = (needSkill == null) ? Math.max(0, req - reserved) : req;
                        if (asn >= cap) { fits = false; break; }
                    }
                    if (!fits) continue;
                    boolean freeNow = assignmentRepository.findAvailableEmployeesForTimeSlot(day, s, e).stream()
                            .anyMatch(e2 -> Objects.equals(e2.getId(), emp.getId()));
                    if (!freeNow) continue;
                    ShiftAssignment a = new ShiftAssignment(day, label, s, e, emp);
                    assignmentRepository.save(a);
                    createdAll.add(a);
                    // Mark today's date as a worked day for weekly limit counting (once per employee)
                    workedDaysByEmployee.computeIfAbsent(emp.getId(), k -> new HashSet<>()).add(day);
                    newly++;
                    for (LocalTime t : covers) {
                        assignedBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + 1);
                        if (needSkill != null) reservedSkillBySlot.compute(t, (k, v) -> Math.max(0, (v == null ? 0 : v) - 1));
                    }
                }
                rotate += newly;
            }
        }

        ensureWeeklyHolidays(year, month);
        ensureFreePlaceholders(year, month);
        logger.info("generateMonthlyFromDemandSimple finished: {}-{} -> {} assignments", year, month, createdAll.size());
        return createdAll;
    }

    private LocalDate weekStartSunday(LocalDate d) {
        int dow = d.getDayOfWeek().getValue() % 7; // Sunday=0
        return d.minusDays(dow);
    }

    @Transactional
    public List<ShiftAssignment> generateMonthlyFromDemand(int year, int month, int granularityMinutes, boolean resetMonth) {
        return generateMonthlyFromDemandSimple(year, month, resetMonth);
    }

    @Async("scheduleExecutor")
    @Transactional
    public void generateMonthlyFromDemandAsync(int year, int month, int granularityMinutes, boolean resetMonth) {
        generateMonthlyFromDemandSimple(year, month, resetMonth);
    }

    @Transactional
    public List<ShiftAssignment> generateForDateFromDemand(LocalDate date, boolean resetDay) {
        if (resetDay) assignmentRepository.deleteByWorkDate(date);
        List<DemandInterval> demands = demandRepository.findEffectiveForDate(date, date.getDayOfWeek());
        if (demands.isEmpty() || employeeRepository.findAll().isEmpty()) return Collections.emptyList();

        List<ShiftAssignment> created = new ArrayList<>();
        int rotate = 0;
        int granularity = 60;
        demands.sort((a, b) -> {
            boolean aGeneric = (a.getSkill() == null);
            boolean bGeneric = (b.getSkill() == null);
            if (aGeneric != bGeneric) return aGeneric ? 1 : -1;
            int ap = (a.getSkill() != null && a.getSkill().getPriority() != null) ? a.getSkill().getPriority() : 0;
            int bp = (b.getSkill() != null && b.getSkill().getPriority() != null) ? b.getSkill().getPriority() : 0;
            if (ap != bp) return Integer.compare(bp, ap);
            int c1 = a.getStartTime().compareTo(b.getStartTime());
            if (c1 != 0) return c1;
            return a.getEndTime().compareTo(b.getEndTime());
        });
        List<LocalTime> slots = buildSlots(granularity);
        Map<LocalTime, Integer> requiredBySlot = new HashMap<>();
        Map<LocalTime, Integer> reservedSkillBySlot = new HashMap<>();
        for (LocalTime t : slots) { requiredBySlot.put(t, 0); reservedSkillBySlot.put(t, 0); }
        for (DemandInterval d : demands) {
            int seats = Optional.ofNullable(d.getRequiredSeats()).orElse(0);
            if (seats <= 0) continue;
            List<LocalTime> cov = slotsCoveredBy(d.getStartTime(), d.getEndTime(), granularity);
            for (LocalTime t : cov) {
                requiredBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
                if (d.getSkill() != null) reservedSkillBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + seats);
            }
        }
        Map<LocalTime, Set<Long>> demandedSkillIdsBySlot = new HashMap<>();
        for (LocalTime t : slots) demandedSkillIdsBySlot.put(t, new HashSet<>());
        for (DemandInterval d : demands) {
            if (d.getSkill() == null) continue;
            Long sid = d.getSkill().getId();
            if (sid == null) continue;
            for (LocalTime t : slotsCoveredBy(d.getStartTime(), d.getEndTime(), granularity)) {
                demandedSkillIdsBySlot.get(t).add(sid);
            }
        }

        Map<LocalTime, Integer> assignedBySlot = new HashMap<>();
        for (LocalTime t : slots) assignedBySlot.put(t, 0);

        for (DemandInterval d : demands) {
            Integer seatsObj = d.getRequiredSeats();
            if (seatsObj == null || seatsObj <= 0) continue;
            LocalTime s = d.getStartTime();
            LocalTime e = d.getEndTime();
            Skill needSkill = d.getSkill();
            String code = needSkill != null ? safeCode(needSkill.getCode()) : "A";
            String label = String.format("Demand-%s %s-%s", code, timeLabel(s), timeLabel(e));

            List<Employee> avail = assignmentRepository.findAvailableEmployeesForTimeSlot(date, s, e);
            if (needSkill != null) {
                Long sid = needSkill.getId();
                avail = avail.stream().filter(emp -> emp.getSkills() != null && emp.getSkills().stream().anyMatch(sk -> Objects.equals(sk.getId(), sid))).toList();
            }
            if (avail.isEmpty()) continue;

            if (needSkill == null) {
                List<LocalTime> covers = slotsCoveredBy(s, e, granularity);
                Map<LocalTime, Integer> skilledAvailCount = new HashMap<>();
                for (LocalTime t : covers) skilledAvailCount.put(t, 0);
                for (Employee emp : avail) {
                    for (LocalTime t : covers) {
                        Set<Long> ds = demandedSkillIdsBySlot.getOrDefault(t, Collections.emptySet());
                        boolean empSkilled = emp.getSkills() != null && emp.getSkills().stream().anyMatch(sk -> ds.contains(sk.getId()));
                        if (empSkilled) skilledAvailCount.compute(t, (k, v) -> (v == null ? 0 : v) + 1);
                    }
                }
                List<Employee> filtered = new ArrayList<>();
                for (Employee emp : avail) {
                    boolean reserved = false;
                    for (LocalTime t : covers) {
                        int reservedSeats = reservedSkillBySlot.getOrDefault(t, 0);
                        if (reservedSeats <= 0) continue;
                        Set<Long> ds = demandedSkillIdsBySlot.getOrDefault(t, Collections.emptySet());
                        boolean empSkilled = emp.getSkills() != null && emp.getSkills().stream().anyMatch(sk -> ds.contains(sk.getId()));
                        if (empSkilled) {
                            int skilledCount = skilledAvailCount.getOrDefault(t, 0);
                            if (skilledCount <= reservedSeats) { reserved = true; break; }
                        }
                    }
                    if (!reserved) filtered.add(emp);
                }
                if (!filtered.isEmpty()) avail = filtered;
            }

            List<Employee> rotated = new ArrayList<>(avail);
            if (!rotated.isEmpty()) {
                int r = rotated.size() == 0 ? 0 : (rotate % rotated.size());
                if (r != 0) Collections.rotate(rotated, -r);
            }
            List<LocalTime> covers = slotsCoveredBy(s, e, granularity);
            int newly = 0;
            int seats = seatsObj;
            for (Employee emp : rotated) {
                if (newly >= seats) break;
                boolean fits = true;
                for (LocalTime t : covers) {
                    int req = requiredBySlot.getOrDefault(t, 0);
                    int asn = assignedBySlot.getOrDefault(t, 0);
                    int reserved = reservedSkillBySlot.getOrDefault(t, 0);
                    int cap = (needSkill == null) ? Math.max(0, req - reserved) : req;
                    if (asn >= cap) { fits = false; break; }
                }
                if (!fits) continue;
                boolean free = assignmentRepository.findAvailableEmployeesForTimeSlot(date, s, e).stream()
                        .anyMatch(e2 -> Objects.equals(e2.getId(), emp.getId()));
                if (!free) continue;
                ShiftAssignment a = new ShiftAssignment(date, label, s, e, emp);
                assignmentRepository.save(a);
                created.add(a);
                newly++;
                for (LocalTime t : covers) {
                    assignedBySlot.compute(t, (k, v) -> (v == null ? 0 : v) + 1);
                    if (needSkill != null) reservedSkillBySlot.compute(t, (k, v) -> Math.max(0, (v == null ? 0 : v) - 1));
                }
            }
            rotate += newly;
        }
        ensureFreePlaceholders(date.getYear(), date.getMonthValue());
        return created;
    }

    private List<LocalTime> buildSlots(int granularityMinutes) {
        List<LocalTime> slots = new ArrayList<>();
        int steps = Math.max(1, (24 * 60) / granularityMinutes);
        for (int i = 0; i < steps; i++) slots.add(LocalTime.MIDNIGHT.plusMinutes((long) i * granularityMinutes));
        return slots;
    }

    private List<LocalTime> slotsCoveredBy(LocalTime start, LocalTime end, int granularityMinutes) {
        List<LocalTime> res = new ArrayList<>();
        List<LocalTime> base = buildSlots(granularityMinutes);
        LocalTime lastEnd = LocalTime.of(23, 59);
        for (int i = 0; i < base.size(); i++) {
            LocalTime s = base.get(i);
            LocalTime e = (i == base.size() - 1) ? lastEnd : base.get(i + 1);
            if (start.isBefore(e) && end.isAfter(s)) res.add(s);
        }
        return res;
    }

    // ---------------- Placeholders ----------------
    @Transactional
    public void ensureFreePlaceholders(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        List<Employee> employees = employeeRepository.findAll();
        if (employees == null || employees.isEmpty()) return;
        LocalTime freeStart = parseCfgTime(cfgFreeStart, LocalTime.MIDNIGHT);
        LocalTime freeEnd = parseCfgTime(cfgFreeEnd, LocalTime.of(0, 5));
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            List<ShiftAssignment> dayList = assignmentRepository.findByWorkDate(day);
            Set<Long> hasReal = new HashSet<>();
            Set<Long> hasFree = new HashSet<>();
            Set<Long> hasOff = new HashSet<>();
            for (ShiftAssignment sa : dayList) {
                boolean isFree = Boolean.TRUE.equals(sa.getIsFree()) || (sa.getShiftName() != null && "FREE".equalsIgnoreCase(sa.getShiftName()));
                boolean isOff = Boolean.TRUE.equals(sa.getIsOff()) || (sa.getShiftName() != null && ("休日".equals(sa.getShiftName()) || "OFF".equalsIgnoreCase(sa.getShiftName())));
                if (isFree) hasFree.add(sa.getEmployee().getId());
                if (isOff) hasOff.add(sa.getEmployee().getId());
                if (!isFree && !isOff) hasReal.add(sa.getEmployee().getId());
            }
            for (Employee emp : employees) {
                Long id = emp.getId();
                if (!hasReal.contains(id) && !hasFree.contains(id) && !hasOff.contains(id)) {
                    ShiftAssignment free = new ShiftAssignment(day, "FREE", freeStart, freeEnd, emp);
                    free.setIsFree(true);
                    assignmentRepository.save(free);
                }
            }
        }
    }

    @Transactional
    public void ensureWeeklyHolidays(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd = ym.atEndOfMonth();
        List<Employee> employees = employeeRepository.findAll();
        if (employees == null || employees.isEmpty()) return;
        LocalTime offStart = parseCfgTime(cfgOffStart, LocalTime.MIDNIGHT);
        LocalTime offEnd = parseCfgTime(cfgOffEnd, LocalTime.of(0, 5));

        // Evaluate full weeks spanning the month: Sunday .. Saturday
        LocalDate outerStart = weekStartSunday(monthStart);           // may be in previous month
        LocalDate outerEnd = weekStartSunday(monthEnd).plusDays(6);   // may be in next month

        // Creation window: allow creating OFF inside the month, and optionally through to the first Saturday after month end
        LocalDate creationEnd = outerEnd; // implement "翌月の土曜日まで" 作成
        LocalDate creationStart = monthStart; // do not touch days before month start

        for (LocalDate cursor = outerStart; !cursor.isAfter(outerEnd); cursor = cursor.plusWeeks(1)) {
            LocalDate weekStart = cursor;
            LocalDate weekEnd = weekStart.plusDays(6);
            for (Employee emp : employees) {
                Long empId = emp.getId();
                int targetRest = employeeRuleRepository.findByEmployeeId(empId)
                        .map(EmployeeRule::getWeeklyRestDays)
                        .filter(v -> v != null && v >= 0)
                        .orElse(2);

                int currentOff = 0;
                Set<LocalDate> realAssignedDays = new HashSet<>();
                Set<LocalDate> offDays = new HashSet<>();
                for (LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) {
                    List<ShiftAssignment> dayList = assignmentRepository.findByWorkDate(d);
                    boolean hasReal = false;
                    boolean hasOff = false;
                    for (ShiftAssignment sa : dayList) {
                        if (!Objects.equals(sa.getEmployee().getId(), empId)) continue;
                        boolean isFree = Boolean.TRUE.equals(sa.getIsFree()) || (sa.getShiftName() != null && "FREE".equalsIgnoreCase(sa.getShiftName()));
                        boolean isOff = Boolean.TRUE.equals(sa.getIsOff()) || (sa.getShiftName() != null && ("休日".equals(sa.getShiftName()) || "OFF".equalsIgnoreCase(sa.getShiftName())));
                        if (isOff) { hasOff = true; }
                        if (!isFree && !isOff) { hasReal = true; }
                    }
                    if (hasOff) { currentOff++; offDays.add(d); }
                    if (hasReal) { realAssignedDays.add(d); }
                }

                int need = Math.max(0, targetRest - currentOff);
                if (need == 0) continue;

                // Select candidate days to mark OFF: prefer days inside current month portion first, then spillover after month end
                List<LocalDate> candidates = new ArrayList<>();
                // 1) within month
                for (LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) {
                    if (d.isBefore(creationStart) || d.isAfter(creationEnd)) continue;
                    if (d.isAfter(monthEnd)) continue; // handled in second pass
                    if (offDays.contains(d)) continue; // already off
                    if (!realAssignedDays.contains(d)) candidates.add(d);
                }
                // 2) spillover after month end up to Saturday
                for (LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) {
                    if (d.isBefore(creationStart) || d.isAfter(creationEnd)) continue;
                    if (!d.isAfter(monthEnd)) continue; // only after month end here
                    if (offDays.contains(d)) continue;
                    if (!realAssignedDays.contains(d)) candidates.add(d);
                }

                for (int i = 0; i < need && i < candidates.size(); i++) {
                    LocalDate offDay = candidates.get(i);
                    ShiftAssignment off = new ShiftAssignment(offDay, "休日", offStart, offEnd, emp);
                    off.setIsOff(true);
                    assignmentRepository.save(off);
                }
            }
        }
    }

    // Utilities
    private String timeLabel(LocalTime t) { return String.format("%02d:%02d", t.getHour(), t.getMinute()); }
    private String safeCode(String code) { return (code == null || code.isBlank()) ? "A" : code.trim(); }
    private LocalTime parseCfgTime(String v, LocalTime def) { try { return LocalTime.parse(v.length() == 5 ? v + ":00" : v); } catch (Exception e) { return def; } }
}
