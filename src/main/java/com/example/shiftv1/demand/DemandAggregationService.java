package com.example.shiftv1.demand;

import com.example.shiftv1.holiday.HolidayRepository;
import com.example.shiftv1.skill.Skill;
import com.example.shiftv1.skill.SkillRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DemandAggregationService {

    private final DemandIntervalRepository repository;
    private final HolidayRepository holidayRepository;
    private final SkillRepository skillRepository;

    public DemandAggregationService(DemandIntervalRepository repository,
                                    HolidayRepository holidayRepository,
                                    SkillRepository skillRepository) {
        this.repository = repository;
        this.holidayRepository = holidayRepository;
        this.skillRepository = skillRepository;
    }

    @Transactional(readOnly = true)
    public AggregationResult aggregate(LocalDate start,
                                       LocalDate end,
                                       int granularityMinutes,
                                       Set<Long> filterSkillIds) {
        if (start == null && end == null) {
            LocalDate today = LocalDate.now();
            start = today;
            end = today;
        } else if (start == null) {
            start = end;
        } else if (end == null) {
            end = start;
        }
        if (end.isBefore(start)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }
        int granularity = Math.max(1, granularityMinutes);
        int slotCount = (int) Math.ceil(24 * 60.0 / granularity);

        Map<Long, int[]> totalsBySkill = new LinkedHashMap<>();
        Map<LocalDate, Boolean> holidayCache = new HashMap<>();
        Map<Long, Skill> skillCache = new HashMap<>();

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            boolean holiday = holidayCache.computeIfAbsent(day, d -> isHolidaySafe(d));
            List<DemandInterval> intervals = repository.findEffectiveForDate(day, day.getDayOfWeek(), holiday);
            if (intervals.isEmpty()) {
                continue;
            }
            Map<Long, int[]> weekly = new HashMap<>();
            Map<Long, int[]> dateSpecific = new HashMap<>();
            Set<Long> dailySkillIds = new HashSet<>();

            for (DemandInterval interval : intervals) {
                Skill skill = interval.getSkill();
                if (skill == null || skill.getId() == null) {
                    continue;
                }
                Long skillId = skill.getId();
                if (filterSkillIds != null && !filterSkillIds.isEmpty() && !filterSkillIds.contains(skillId)) {
                    continue;
                }
                int startMin = toMinutes(interval.getStartTime());
                int endMin = toMinutes(interval.getEndTime());
                if (endMin <= 0 || startMin >= 24 * 60 || endMin <= startMin) {
                    continue;
                }
                int startIndex = Math.max(0, (int) Math.floor(startMin / (double) granularity));
                int endIndex = Math.min(slotCount, (int) Math.ceil(endMin / (double) granularity));
                boolean dateOverride = interval.getDate() != null;
                int[] arr = (dateOverride ? dateSpecific : weekly)
                        .computeIfAbsent(skillId, k -> new int[slotCount]);
                int seats = Math.max(0, interval.getRequiredSeats());
                if (seats == 0) {
                    continue;
                }
                for (int slot = startIndex; slot < endIndex; slot++) {
                    arr[slot] += seats;
                }
                dailySkillIds.add(skillId);
                skillCache.putIfAbsent(skillId, skill);
            }

            for (Long skillId : dailySkillIds) {
                int[] weeklyArr = weekly.get(skillId);
                int[] dateArr = dateSpecific.get(skillId);
                if (weeklyArr == null && dateArr == null) {
                    continue;
                }
                int[] effective = new int[slotCount];
                for (int i = 0; i < slotCount; i++) {
                    int w = weeklyArr == null ? 0 : weeklyArr[i];
                    int d = dateArr == null ? 0 : dateArr[i];
                    effective[i] = d > 0 ? d : w;
                }
                int[] totalArr = totalsBySkill.computeIfAbsent(skillId, k -> new int[slotCount]);
                for (int i = 0; i < slotCount; i++) {
                    totalArr[i] += effective[i];
                }
            }
        }

        List<Long> orderedSkillIds = new ArrayList<>(totalsBySkill.keySet());
        orderedSkillIds.sort(Comparator.nullsLast(Comparator.naturalOrder()));

        Map<Long, Skill> resolvedSkills = orderedSkillIds.isEmpty()
                ? Collections.emptyMap()
                : skillRepository.findAllById(orderedSkillIds).stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(Skill::getId, s -> s));
        List<SkillSummary> skills = new ArrayList<>();
        for (Long skillId : orderedSkillIds) {
            Skill skill = resolvedSkills.getOrDefault(skillId, skillCache.get(skillId));
            String code = skill != null ? skill.getCode() : null;
            String name = skill != null ? skill.getName() : null;
            skills.add(new SkillSummary(skillId, code, name));
        }

        String[] slotLabels = buildSlotLabels(slotCount, granularity);
        int[] totalsPerSlot = new int[slotCount];
        Map<Long, Integer> totalsPerSkill = new LinkedHashMap<>();
        for (Map.Entry<Long, int[]> entry : totalsBySkill.entrySet()) {
            Long skillId = entry.getKey();
            int[] arr = entry.getValue();
            int sum = 0;
            for (int i = 0; i < slotCount; i++) {
                totalsPerSlot[i] += arr[i];
                sum += arr[i];
            }
            totalsPerSkill.put(skillId, sum);
        }

        return new AggregationResult(
                start,
                end,
                granularity,
                slotLabels,
                totalsBySkill,
                skills,
                totalsPerSlot,
                totalsPerSkill,
                slotCount
        );
    }

    private boolean isHolidaySafe(LocalDate date) {
        try {
            return holidayRepository.existsByDate(date);
        } catch (Exception e) {
            return false;
        }
    }

    private int toMinutes(LocalTime time) {
        if (time == null) {
            return 0;
        }
        return time.getHour() * 60 + time.getMinute();
    }

    private String[] buildSlotLabels(int slotCount, int granularityMinutes) {
        String[] labels = new String[slotCount];
        for (int i = 0; i < slotCount; i++) {
            int startMinutes = i * granularityMinutes;
            int endMinutes = Math.min(24 * 60, (i + 1) * granularityMinutes);
            LocalTime start = LocalTime.of(startMinutes / 60, startMinutes % 60);
            int adjEndMinutes = endMinutes == 24 * 60 ? endMinutes - 1 : endMinutes;
            LocalTime end = LocalTime.of(adjEndMinutes / 60, adjEndMinutes % 60);
            labels[i] = String.format("%02d:%02d-%02d:%02d",
                    start.getHour(), start.getMinute(),
                    end.getHour(), end.getMinute());
        }
        return labels;
    }

    public record AggregationResult(
            LocalDate startDate,
            LocalDate endDate,
            int granularityMinutes,
            String[] slotLabels,
            Map<Long, int[]> matrix,
            List<SkillSummary> skills,
            int[] totalsPerSlot,
            Map<Long, Integer> totalsPerSkill,
            int slotCount
    ) {
    }

    public record SkillSummary(Long id, String code, String name) {
    }
}
