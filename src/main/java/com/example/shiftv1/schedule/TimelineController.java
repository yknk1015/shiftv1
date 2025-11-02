package com.example.shiftv1.schedule;

import com.example.shiftv1.common.ApiResponse;
import com.example.shiftv1.demand.DemandInterval;
import com.example.shiftv1.demand.DemandIntervalRepository;
import org.springframework.http.ResponseEntity;
import com.example.shiftv1.breaks.BreakPeriod;
import com.example.shiftv1.breaks.BreakPeriodRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/schedule/grid")
public class TimelineController {

    private final ShiftAssignmentRepository assignmentRepository;
    private final DemandIntervalRepository demandRepository;
    private final BreakPeriodRepository breakRepository;

    public TimelineController(ShiftAssignmentRepository assignmentRepository,
                              DemandIntervalRepository demandRepository,
                              BreakPeriodRepository breakRepository) {
        this.assignmentRepository = assignmentRepository;
        this.demandRepository = demandRepository;
        this.breakRepository = breakRepository;
    }

    @GetMapping("/day")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDayGrid(
            @RequestParam(name = "date") LocalDate date,
            @RequestParam(name = "granularity", defaultValue = "60") Integer granularityMinutes,
            @RequestParam(name = "skillId", required = false) Long skillId) {
        if (granularityMinutes != 15 && granularityMinutes != 60) {
            return ResponseEntity.badRequest().body(ApiResponse.failure("granularity は 15 または 60 を指定してください"));
        }
        // Fetch with employee to avoid LAZY loading issues and N+1 queries
        List<ShiftAssignment> dayAssignments = assignmentRepository.findByWorkDateFetchEmployee(date);
        List<BreakPeriod> breaks = breakRepository.findByWorkDate(date);
        List<DemandInterval> demand = demandRepository.findEffectiveForDate(date, date.getDayOfWeek());
        Map<String, Object> result = buildGrid(dayAssignments, demand, breaks, granularityMinutes, skillId);
        return ResponseEntity.ok(ApiResponse.success("タイムラインを取得しました", result));
    }

    private Map<String, Object> buildGrid(List<ShiftAssignment> assignments,
                                          List<DemandInterval> demand,
                                          List<BreakPeriod> breaks,
                                          int granularityMinutes,
                                          Long skillId) {
        LocalDate any = assignments.isEmpty() ? LocalDate.now() : assignments.get(0).getWorkDate();
        final java.time.LocalTime midnight = java.time.LocalTime.MIDNIGHT;
        List<Map<String, Object>> slots = new ArrayList<>();
        // Avoid wrap-around infinite loop when t.plusMinutes wraps to 00:00.
        // Compute a fixed number of slots for the day instead.
        int slotsPerDay = Math.max(1, (24 * 60) / granularityMinutes);
        for (int i = 0; i < slotsPerDay; i++) {
            java.time.LocalTime t = midnight.plusMinutes((long) i * granularityMinutes);
            java.time.LocalTime tEnd = midnight.plusMinutes((long) (i + 1) * granularityMinutes);
            // Cap the last slot to 23:59 to keep comparisons well-defined in LocalTime
            if (i == slotsPerDay - 1) {
                tEnd = java.time.LocalTime.of(23, 59);
            }
            final java.time.LocalTime tt = t;
            final java.time.LocalTime ttEnd = tEnd;
            final Long sid = skillId;
            // assigned (optionally filter by skill) and exclude placeholders (FREE/OFF)
            List<String> names = assignments.stream()
                    .filter(a -> !a.getStartTime().isAfter(tt) && a.getEndTime().isAfter(tt))
                    // exclude FREE/OFF placeholders by flags and by conventional names
                    .filter(a -> {
                        Boolean free = a.getIsFree(); Boolean off = a.getIsOff();
                        String nm = a.getShiftName();
                        boolean isFree = Boolean.TRUE.equals(free) || (nm != null && "FREE".equalsIgnoreCase(nm));
                        boolean isOff = Boolean.TRUE.equals(off) || (nm != null && ("休日".equals(nm) || "OFF".equalsIgnoreCase(nm)));
                        return !isFree && !isOff;
                    })
                    .filter(a -> sid == null || (a.getEmployee().getSkills() != null && a.getEmployee().getSkills().stream().anyMatch(s -> Objects.equals(s.getId(), sid))))
                    // exclude employees currently on break
                    .filter(a -> breaks.stream().noneMatch(b ->
                            b.getAssignment().getId().equals(a.getId()) &&
                            b.getStartTime().isBefore(ttEnd) && b.getEndTime().isAfter(tt)
                    ))
                    .map(a -> a.getEmployee().getName())
                    .distinct()
                    .sorted()
                    .toList();

            // required seats from demand intervals (sum)
            boolean overlap = true; // helper placeholder
            int required;
            if (sid == null) {
                // When no skill filter, show total demand = generic + sum of all skill-specific
                required = demand.stream()
                        .filter(d -> d.getActive() == null || d.getActive())
                        .filter(d -> d.getStartTime().isBefore(ttEnd) && d.getEndTime().isAfter(tt))
                        .mapToInt(DemandInterval::getRequiredSeats)
                        .sum();
            } else {
                // With skill filter, show generic + the selected skill's demand
                int global = demand.stream()
                        .filter(d -> d.getActive() == null || d.getActive())
                        .filter(d -> d.getSkill() == null)
                        .filter(d -> d.getStartTime().isBefore(ttEnd) && d.getEndTime().isAfter(tt))
                        .mapToInt(DemandInterval::getRequiredSeats)
                        .sum();
                int skillReq = demand.stream()
                        .filter(d -> d.getActive() == null || d.getActive())
                        .filter(d -> d.getSkill() != null && Objects.equals(d.getSkill().getId(), sid))
                        .filter(d -> d.getStartTime().isBefore(ttEnd) && d.getEndTime().isAfter(tt))
                        .mapToInt(DemandInterval::getRequiredSeats)
                        .sum();
                required = global + skillReq;
            }

            Map<String, Object> slot = new HashMap<>();
            slot.put("start", t.toString());
            slot.put("end", tEnd.toString());
            slot.put("employees", names);
            slot.put("assigned", names.size());
            slot.put("required", required);
            slot.put("shortage", Math.max(0, required - names.size()));
            slots.add(slot);
        }
        Map<String, Object> grid = new HashMap<>();
        grid.put("date", any.toString());
        grid.put("granularity", granularityMinutes);
        grid.put("skillId", skillId);
        grid.put("slots", slots);
        return grid;
    }
}
