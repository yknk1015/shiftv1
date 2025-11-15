package com.example.shiftv1.analytics;

import com.example.shiftv1.breaks.BreakPeriod;
import com.example.shiftv1.breaks.BreakPeriodRepository;
import com.example.shiftv1.demand.DemandAggregationService;
import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.schedule.ShiftAssignment;
import com.example.shiftv1.schedule.ShiftAssignmentRepository;
import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DemandSupplyAnalyticsService {

    private static final long GENERIC_SKILL_ID = 0L;

    private final DemandAggregationService demandAggregationService;
    private final ShiftAssignmentRepository assignmentRepository;
    private final BreakPeriodRepository breakRepository;
    private final SkillRepository skillRepository;

    public DemandSupplyAnalyticsService(DemandAggregationService demandAggregationService,
                                        ShiftAssignmentRepository assignmentRepository,
                                        BreakPeriodRepository breakRepository,
                                        SkillRepository skillRepository) {
        this.demandAggregationService = demandAggregationService;
        this.assignmentRepository = assignmentRepository;
        this.breakRepository = breakRepository;
        this.skillRepository = skillRepository;
    }

    @Transactional(readOnly = true)
    public DemandSupplySnapshot summarize(LocalDate start,
                                          LocalDate end,
                                          int granularityMinutes,
                                          Set<Long> filterSkillIds) {
        LocalDate resolvedStart = start == null ? LocalDate.now() : start;
        LocalDate resolvedEnd = end == null ? resolvedStart : end;
        if (resolvedEnd.isBefore(resolvedStart)) {
            LocalDate tmp = resolvedStart;
            resolvedStart = resolvedEnd;
            resolvedEnd = tmp;
        }
        long span = ChronoUnit.DAYS.between(resolvedStart, resolvedEnd);
        if (span > 31) {
            resolvedEnd = resolvedStart.plusDays(30);
        }
        int granularity = Math.max(5, granularityMinutes);
        Set<Long> filters = filterSkillIds == null ? Collections.emptySet() : filterSkillIds;

        DemandAggregationService.AggregationResult demand = demandAggregationService.aggregate(
                resolvedStart, resolvedEnd, granularity, filters);

        SkillLookup skillLookup = loadSkillLookup();
        SupplyComputation supply = computeSupply(resolvedStart, resolvedEnd, granularity,
                demand.slotCount(), filters, skillLookup);

        Set<Long> skillIds = new LinkedHashSet<>(demand.matrix().keySet());
        skillIds.addAll(supply.skillIds());

        Map<Long, SkillMeta> skillMeta = new LinkedHashMap<>();
        for (DemandAggregationService.SkillSummary summary : demand.skills()) {
            if (summary.id() == null) continue;
            skillMeta.put(summary.id(), new SkillMeta(summary.id(), summary.code(), summary.name(), null));
        }
        List<Long> missing = skillIds.stream()
                .filter(id -> id != null && !skillMeta.containsKey(id) && id != GENERIC_SKILL_ID)
                .toList();
        if (!missing.isEmpty()) {
            Map<Long, Skill> fetched = skillRepository.findAllById(missing).stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(Skill::getId, s -> s));
            for (Long id : missing) {
                Skill skill = fetched.get(id);
                if (skill == null) continue;
                skillMeta.put(id, new SkillMeta(id, skill.getCode(), skill.getName(), skill.getPriority()));
            }
        }
        if (!skillMeta.containsKey(GENERIC_SKILL_ID) && supply.skillIds().contains(GENERIC_SKILL_ID)) {
            skillMeta.put(GENERIC_SKILL_ID, new SkillMeta(GENERIC_SKILL_ID, "GEN", "汎用", Integer.MAX_VALUE));
        }

        List<Long> orderedSkillIds = new ArrayList<>(skillMeta.keySet());
        orderedSkillIds.sort(Comparator
                .comparing((Long id) -> {
                    SkillMeta meta = skillMeta.get(id);
                    return meta == null || meta.priority() == null ? Integer.MAX_VALUE : meta.priority();
                })
                .thenComparing(id -> id == null ? Long.MAX_VALUE : id));

        List<String> slots = Arrays.asList(demand.slotLabels());
        List<SkillSeries> skillSeries = new ArrayList<>();
        int slotCount = demand.slotCount();
        double[] totalsDemand = new double[slotCount];
        double[] totalsSupply = new double[slotCount];
        double[] totalsBreak = new double[slotCount];
        double[] totalsNetGap = new double[slotCount];
        double[] totalsBreakGap = new double[slotCount];
        double[] totalsAssignGap = new double[slotCount];

        for (Long skillId : orderedSkillIds) {
            int[] demandArr = demand.matrix().get(skillId);
            double[] demandSeries = toDoubleArray(demandArr, slotCount);
            double[] supplyArr = supply.working().getOrDefault(skillId, new double[slotCount]);
            double[] breakArr = supply.breaks().getOrDefault(skillId, new double[slotCount]);

            GapSeries gaps = calculateGaps(demandSeries, supplyArr, breakArr);
            SkillMeta meta = skillMeta.get(skillId);
            SkillSeries series = new SkillSeries(
                    skillId,
                    meta == null ? null : meta.code(),
                    meta == null ? null : meta.name(),
                    demandSeries,
                    supplyArr,
                    breakArr,
                    gaps.netGap(),
                    gaps.breakGap(),
                    gaps.assignGap()
            );
            skillSeries.add(series);
            accumulate(totalsDemand, demandSeries);
            accumulate(totalsSupply, supplyArr);
            accumulate(totalsBreak, breakArr);
            accumulate(totalsNetGap, gaps.netGap());
            accumulate(totalsBreakGap, gaps.breakGap());
            accumulate(totalsAssignGap, gaps.assignGap());
        }

        double[] freeWorking = supply.freeWorking();
        double[] freeCoverBreak = new double[slotCount];
        double[] freeCoverDeficit = new double[slotCount];
        for (int i = 0; i < slotCount; i++) {
            double coverBreak = Math.min(freeWorking[i], totalsBreakGap[i]);
            double remaining = Math.max(0, freeWorking[i] - coverBreak);
            double coverDeficit = Math.min(remaining, totalsAssignGap[i]);
            freeCoverBreak[i] = round2(coverBreak);
            freeCoverDeficit[i] = round2(coverDeficit);
        }

        FreePoolSeries freePool = new FreePoolSeries(freeWorking, freeCoverBreak, freeCoverDeficit);
        Totals totals = new Totals(totalsDemand, totalsSupply, totalsBreak, totalsNetGap, totalsBreakGap, totalsAssignGap);

        Map<String, Object> meta = new HashMap<>();
        meta.put("maxDemand", maxValue(totalsDemand));
        meta.put("maxSupply", maxValue(totalsSupply));
        meta.put("maxNetGap", maxValue(totalsNetGap));
        meta.put("maxBreakGap", maxValue(totalsBreakGap));
        meta.put("assignmentCount", supply.assignmentCount());

        int rangeDays = (int) (ChronoUnit.DAYS.between(resolvedStart, resolvedEnd) + 1);
        return new DemandSupplySnapshot(
                resolvedStart,
                resolvedEnd,
                granularity,
                rangeDays,
                slots,
                skillSeries,
                totals,
                freePool,
                meta
        );
    }

    private SupplyComputation computeSupply(LocalDate start,
                                            LocalDate end,
                                            int granularity,
                                            int slotCount,
                                            Set<Long> filterSkillIds,
                                            SkillLookup skillLookup) {
        List<ShiftAssignment> assignments = assignmentRepository.findWithEmployeeBetween(start, end);
        Map<Long, List<BreakPeriod>> breaksByAssignment = breakRepository.findByAssignmentWorkDateBetween(start, end).stream()
                .filter(bp -> bp.getAssignment() != null && bp.getAssignment().getId() != null)
                .collect(Collectors.groupingBy(bp -> bp.getAssignment().getId()));

        Map<Long, double[]> working = new HashMap<>();
        Map<Long, double[]> breaks = new HashMap<>();
        double[] freeWorking = new double[slotCount];
        Set<Long> skillIds = new HashSet<>();
        Map<Long, Long> employeeSkillCache = new HashMap<>();
        int contributingAssignments = 0;

        for (ShiftAssignment assignment : assignments) {
            if (assignment == null || assignment.getStartTime() == null || assignment.getEndTime() == null) {
                continue;
            }
            if (Boolean.TRUE.equals(assignment.getIsOff()) || Boolean.TRUE.equals(assignment.getIsLeave())) {
                continue;
            }
            boolean isFree = Boolean.TRUE.equals(assignment.getIsFree());
            Long skillId = resolveSkillId(assignment, employeeSkillCache, skillLookup);
            if (skillId == null) {
                skillId = GENERIC_SKILL_ID;
            }
            if (!isFree && filterSkillIds != null && !filterSkillIds.isEmpty() && !filterSkillIds.contains(skillId)) {
                continue;
            }

            int assignStart = Math.max(0, toMinutes(assignment.getStartTime()));
            int assignEnd = Math.min(24 * 60, toMinutes(assignment.getEndTime()));
            if (assignEnd <= assignStart) {
                continue;
            }
            List<BreakPeriod> assignmentBreaks = breaksByAssignment.getOrDefault(assignment.getId(), Collections.emptyList());
            boolean contributed = false;

            for (int slot = 0; slot < slotCount; slot++) {
                int slotStart = slot * granularity;
                int slotEnd = Math.min(24 * 60, slotStart + granularity);
                double overlap = overlap(assignStart, assignEnd, slotStart, slotEnd);
                if (overlap <= 0) {
                    continue;
                }
                double breakMinutes = overlapBreaks(assignmentBreaks, slotStart, slotEnd);
                double workingMinutes = Math.max(0, overlap - breakMinutes);
                double workingFte = workingMinutes / granularity;
                double breakFte = breakMinutes / granularity;
                if (workingFte <= 0 && breakFte <= 0) {
                    continue;
                }
                if (isFree) {
                    freeWorking[slot] += workingFte;
                    contributed = true;
                    continue;
                }
                skillIds.add(skillId);
                double[] workArr = working.computeIfAbsent(skillId, k -> new double[slotCount]);
                double[] breakArr = breaks.computeIfAbsent(skillId, k -> new double[slotCount]);
                workArr[slot] += workingFte;
                breakArr[slot] += breakFte;
                contributed = true;
            }
            if (contributed) {
                contributingAssignments++;
            }
        }
        return new SupplyComputation(working, breaks, freeWorking, contributingAssignments, skillIds);
    }

    private GapSeries calculateGaps(double[] demand, double[] supply, double[] breakImpact) {
        int len = demand.length;
        double[] netGap = new double[len];
        double[] breakGap = new double[len];
        double[] assignGap = new double[len];
        for (int i = 0; i < len; i++) {
            double d = demand[i];
            double s = supply[i];
            double b = breakImpact[i];
            double net = Math.max(0, round2(d - s));
            double assigned = s + b;
            double shortageAfterAssign = Math.max(0, round2(d - assigned));
            double breakOnly = Math.max(0, round2(net - shortageAfterAssign));
            netGap[i] = net;
            assignGap[i] = shortageAfterAssign;
            breakGap[i] = breakOnly;
        }
        return new GapSeries(netGap, breakGap, assignGap);
    }

    private void accumulate(double[] totals, double[] addition) {
        for (int i = 0; i < totals.length; i++) {
            totals[i] += addition[i];
        }
    }

    private double[] toDoubleArray(int[] source, int len) {
        double[] arr = new double[len];
        if (source == null) {
            return arr;
        }
        int limit = Math.min(len, source.length);
        for (int i = 0; i < limit; i++) {
            arr[i] = source[i];
        }
        return arr;
    }

    private double maxValue(double[] arr) {
        double max = 0;
        for (double v : arr) {
            if (v > max) {
                max = v;
            }
        }
        return round2(max);
    }

    private double overlap(int aStart, int aEnd, int bStart, int bEnd) {
        int start = Math.max(aStart, bStart);
        int end = Math.min(aEnd, bEnd);
        return Math.max(0, end - start);
    }

    private double overlapBreaks(List<BreakPeriod> breaks, int slotStart, int slotEnd) {
        if (breaks == null || breaks.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (BreakPeriod period : breaks) {
            if (period.getStartTime() == null || period.getEndTime() == null) {
                continue;
            }
            int bStart = Math.max(0, toMinutes(period.getStartTime()));
            int bEnd = Math.min(24 * 60, toMinutes(period.getEndTime()));
            total += overlap(bStart, bEnd, slotStart, slotEnd);
        }
        return total;
    }

    private int toMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private Long resolveSkillId(ShiftAssignment assignment,
                                Map<Long, Long> employeeCache,
                                SkillLookup lookup) {
        Long inferred = lookup.extractFromShiftName(assignment.getShiftName());
        if (inferred != null) {
            return inferred;
        }
        Employee employee = assignment.getEmployee();
        if (employee == null || employee.getId() == null) {
            return GENERIC_SKILL_ID;
        }
        Long cached = employeeCache.get(employee.getId());
        if (cached != null) {
            return cached;
        }
        Set<Skill> skills = employee.getSkills();
        if (skills == null || skills.isEmpty()) {
            employeeCache.put(employee.getId(), GENERIC_SKILL_ID);
            return GENERIC_SKILL_ID;
        }
        Skill chosen = skills.stream()
                .filter(Objects::nonNull)
                .filter(s -> s.getId() != null)
                .sorted(Comparator
                        .comparing((Skill s) -> s.getPriority() == null ? Integer.MAX_VALUE : s.getPriority())
                        .thenComparing(Skill::getId))
                .findFirst()
                .orElse(null);
        Long resolved = chosen == null ? GENERIC_SKILL_ID : chosen.getId();
        employeeCache.put(employee.getId(), resolved);
        return resolved;
    }

    private SkillLookup loadSkillLookup() {
        List<Skill> skills = skillRepository.findAll();
        return new SkillLookup(skills);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record SkillMeta(Long id, String code, String name, Integer priority) {
    }

    private record GapSeries(double[] netGap, double[] breakGap, double[] assignGap) {
    }

    private record SupplyComputation(Map<Long, double[]> working,
                                     Map<Long, double[]> breaks,
                                     double[] freeWorking,
                                     int assignmentCount,
                                     Set<Long> skillIds) {
    }

    public record SkillSeries(Long id,
                              String code,
                              String name,
                              double[] demand,
                              double[] supply,
                              double[] breakImpact,
                              double[] netGap,
                              double[] breakGap,
                              double[] assignGap) {
    }

    public record Totals(double[] demand,
                         double[] supply,
                         double[] breakImpact,
                         double[] netGap,
                         double[] breakGap,
                         double[] assignGap) {
    }

    public record FreePoolSeries(double[] working,
                                 double[] coverBreak,
                                 double[] coverDeficit) {
    }

    public record DemandSupplySnapshot(LocalDate startDate,
                                       LocalDate endDate,
                                       int granularityMinutes,
                                       int rangeDays,
                                       List<String> slots,
                                       List<SkillSeries> skills,
                                       Totals totals,
                                       FreePoolSeries freePool,
                                       Map<String, Object> meta) {
    }

    private static final Pattern DEMAND_LABEL_PATTERN = Pattern.compile("需要枠\\((.+)\\)");

    private static final class SkillLookup {
        private final Map<String, Long> byName;
        private final Map<String, Long> byCode;

        SkillLookup(List<Skill> skills) {
            Map<String, Long> nameMap = new HashMap<>();
            Map<String, Long> codeMap = new HashMap<>();
            if (skills != null) {
                for (Skill skill : skills) {
                    if (skill == null || skill.getId() == null) {
                        continue;
                    }
                    if (skill.getName() != null) {
                        nameMap.put(skill.getName().trim().toLowerCase(Locale.ROOT), skill.getId());
                    }
                    if (skill.getCode() != null) {
                        codeMap.put(skill.getCode().trim().toLowerCase(Locale.ROOT), skill.getId());
                    }
                }
            }
            this.byName = nameMap;
            this.byCode = codeMap;
        }

        Long extractFromShiftName(String shiftName) {
            if (shiftName == null) {
                return null;
            }
            Matcher matcher = DEMAND_LABEL_PATTERN.matcher(shiftName);
            if (!matcher.find()) {
                return null;
            }
            String token = matcher.group(1).trim();
            if (token.isEmpty()) {
                return null;
            }
            if ("汎用".equals(token)) {
                return GENERIC_SKILL_ID;
            }
            Long id = find(token);
            if (id != null) {
                return id;
            }
            return null;
        }

        private Long find(String token) {
            if (token == null || token.isBlank()) {
                return null;
            }
            String key = token.trim().toLowerCase(Locale.ROOT);
            Long id = byName.get(key);
            if (id != null) {
                return id;
            }
            return byCode.get(key);
        }
    }
}
