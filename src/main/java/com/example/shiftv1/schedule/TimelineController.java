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
        List<ShiftAssignment> dayAssignments = assignmentRepository.findByWorkDate(date);
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
        java.time.LocalTime start = java.time.LocalTime.of(0, 0);
        java.time.LocalTime end = java.time.LocalTime.of(23, 59);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (java.time.LocalTime t = start; !t.isAfter(end); t = t.plusMinutes(granularityMinutes)) {
            java.time.LocalTime tEnd = t.plusMinutes(granularityMinutes);
            final java.time.LocalTime tt = t;
            final java.time.LocalTime ttEnd = tEnd;
            final Long sid = skillId;
            // assigned (optionally filter by skill)
            List<String> names = assignments.stream()
                    .filter(a -> !a.getStartTime().isAfter(tt) && a.getEndTime().isAfter(tt))
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
            int global = demand.stream()
                    .filter(d -> d.getActive() == null || d.getActive())
                    .filter(d -> d.getSkill() == null)
                    .filter(d -> d.getStartTime().isBefore(ttEnd) && d.getEndTime().isAfter(tt))
                    .mapToInt(DemandInterval::getRequiredSeats)
                    .sum();
            int skillReq = 0;
            if (sid != null) {
                skillReq = demand.stream()
                        .filter(d -> d.getActive() == null || d.getActive())
                        .filter(d -> d.getSkill() != null && Objects.equals(d.getSkill().getId(), sid))
                        .filter(d -> d.getStartTime().isBefore(ttEnd) && d.getEndTime().isAfter(tt))
                        .mapToInt(DemandInterval::getRequiredSeats)
                        .sum();
            }
            int required = global + skillReq;

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
