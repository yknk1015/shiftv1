package com.example.shiftv1.employee;

import com.example.shiftv1.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;

@RestController
@RequestMapping("/api/employees")
public class EmployeeRuleController {

    private final EmployeeRepository employeeRepository;
    private final EmployeeRuleRepository ruleRepository;
    private final EmployeeAvailabilityRepository availabilityRepository;

    public EmployeeRuleController(EmployeeRepository employeeRepository,
                                  EmployeeRuleRepository ruleRepository,
                                  EmployeeAvailabilityRepository availabilityRepository) {
        this.employeeRepository = employeeRepository;
        this.ruleRepository = ruleRepository;
        this.availabilityRepository = availabilityRepository;
    }

    @GetMapping("/{id}/rule")
    public ResponseEntity<ApiResponse<EmployeeRule>> getRule(@PathVariable Long id) {
        return employeeRepository.findById(id)
                .map(emp -> {
                    EmployeeRule rule = ruleRepository.findByEmployeeId(id).orElseGet(() -> {
                        EmployeeRule r = new EmployeeRule();
                        r.setEmployee(emp);
                        return ruleRepository.save(r);
                    });
                    return ResponseEntity.ok(ApiResponse.success("勤務ルールを取得しました", rule));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません")));
    }

    public static class RuleRequest {
        public Integer weeklyMaxHours;
        public Integer dailyMaxHours;
        public Integer maxConsecutiveDays;
        public Integer minRestHours;
        public Boolean allowMultipleShiftsPerDay;
        public Boolean allowHolidayWork;
    }

    @PutMapping("/{id}/rule")
    public ResponseEntity<ApiResponse<EmployeeRule>> updateRule(@PathVariable Long id, @RequestBody RuleRequest req) {
        return employeeRepository.findById(id)
                .map(emp -> {
                    EmployeeRule rule = ruleRepository.findByEmployeeId(id).orElseGet(() -> {
                        EmployeeRule r = new EmployeeRule();
                        r.setEmployee(emp);
                        return r;
                    });
                    if (req.weeklyMaxHours != null) rule.setWeeklyMaxHours(req.weeklyMaxHours);
                    if (req.dailyMaxHours != null) rule.setDailyMaxHours(req.dailyMaxHours);
                    if (req.maxConsecutiveDays != null) rule.setMaxConsecutiveDays(req.maxConsecutiveDays);
                    if (req.minRestHours != null) rule.setMinRestHours(req.minRestHours);
                    if (req.allowMultipleShiftsPerDay != null) rule.setAllowMultipleShiftsPerDay(req.allowMultipleShiftsPerDay);
                    if (req.allowHolidayWork != null) rule.setAllowHolidayWork(req.allowHolidayWork);
                    EmployeeRule saved = ruleRepository.save(rule);
                    return ResponseEntity.ok(ApiResponse.success("勤務ルールを更新しました", saved));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません")));
    }

    public static class AvailabilityItem {
        public String dayOfWeek; // MONDAY..SUNDAY
        public String startTime; // HH:mm
        public String endTime;   // HH:mm
    }

    @GetMapping("/{id}/availability")
    public ResponseEntity<ApiResponse<List<EmployeeAvailability>>> getAvailability(@PathVariable Long id) {
        if (!employeeRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません"));
        }
        List<EmployeeAvailability> list = availabilityRepository.findByEmployeeId(id);
        return ResponseEntity.ok(ApiResponse.success("可用時間帯を取得しました", list));
    }

    @PutMapping("/{id}/availability")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateAvailability(@PathVariable Long id,
                                                                               @RequestBody List<AvailabilityItem> items) {
        return employeeRepository.findById(id)
                .map(emp -> {
                    // 全削除→再登録の単純化
                    availabilityRepository.findByEmployeeId(id).forEach(a -> availabilityRepository.deleteById(a.getId()));
                    int count = 0;
                    if (items != null) {
                        for (AvailabilityItem it : items) {
                            try {
                                DayOfWeek dow = DayOfWeek.valueOf(it.dayOfWeek);
                                LocalTime st = LocalTime.parse(it.startTime.length() == 5 ? it.startTime + ":00" : it.startTime);
                                LocalTime et = LocalTime.parse(it.endTime.length() == 5 ? it.endTime + ":00" : it.endTime);
                                EmployeeAvailability av = new EmployeeAvailability();
                                av.setEmployee(emp);
                                av.setDayOfWeek(dow);
                                av.setStartTime(st);
                                av.setEndTime(et);
                                availabilityRepository.save(av);
                                count++;
                            } catch (Exception ignored) {}
                        }
                    }
                    Map<String, Object> data = new HashMap<>();
                    data.put("updated", count);
                    return ResponseEntity.ok(ApiResponse.success("可用時間帯を更新しました", data));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("従業員が見つかりません")));
    }
}
