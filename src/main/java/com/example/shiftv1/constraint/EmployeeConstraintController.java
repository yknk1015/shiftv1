package com.example.shiftv1.constraint;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/constraints")
public class EmployeeConstraintController {

    private final EmployeeConstraintService constraintService;

    public EmployeeConstraintController(EmployeeConstraintService constraintService) {
        this.constraintService = constraintService;
    }

    /**
     * 制約を作成
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createConstraint(@RequestBody @Valid ConstraintRequest request) {
        try {
            EmployeeConstraint constraint = constraintService.createConstraint(
                    request.getEmployeeId(),
                    request.getDate(),
                    request.getType(),
                    request.getReason()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "制約を作成しました");
            response.put("data", constraint);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "制約の作成に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 時間指定の制約を作成
     */
    @PostMapping("/time-constraint")
    public ResponseEntity<Map<String, Object>> createTimeConstraint(@RequestBody @Valid TimeConstraintRequest request) {
        try {
            EmployeeConstraint constraint = constraintService.createTimeConstraint(
                    request.getEmployeeId(),
                    request.getDate(),
                    request.getType(),
                    request.getReason(),
                    request.getStartTime(),
                    request.getEndTime()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "時間指定制約を作成しました");
            response.put("data", constraint);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "時間指定制約の作成に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 制約を更新
     */
    @PutMapping("/{constraintId}")
    public ResponseEntity<Map<String, Object>> updateConstraint(
            @PathVariable Long constraintId,
            @RequestBody @Valid ConstraintUpdateRequest request) {
        try {
            EmployeeConstraint constraint = constraintService.updateConstraint(
                    constraintId,
                    request.getReason(),
                    request.getStartTime(),
                    request.getEndTime()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "制約を更新しました");
            response.put("data", constraint);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "制約の更新に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 制約を削除
     */
    @DeleteMapping("/{constraintId}")
    public ResponseEntity<Map<String, Object>> deleteConstraint(@PathVariable Long constraintId) {
        try {
            constraintService.deleteConstraint(constraintId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "制約を削除しました");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "制約の削除に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 従業員の制約一覧を取得
     */
    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<Map<String, Object>> getEmployeeConstraints(@PathVariable Long employeeId) {
        try {
            List<EmployeeConstraint> constraints = constraintService.getEmployeeConstraints(employeeId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", constraints);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "制約の取得に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 指定日付範囲の制約一覧を取得
     */
    @GetMapping("/date-range")
    public ResponseEntity<Map<String, Object>> getConstraintsByDateRange(
            @RequestParam(name = "startDate") LocalDate startDate,
            @RequestParam(name = "endDate") LocalDate endDate) {
        try {
            List<EmployeeConstraint> constraints = constraintService.getConstraintsByDateRange(startDate, endDate);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", constraints);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "制約の取得に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * メンテ用: 指定日・タイプの制約を一括で非アクティブ化
     */
    @PostMapping("/bulk/deactivate")
    public ResponseEntity<Map<String, Object>> bulkDeactivate(
            @RequestParam(name = "date") LocalDate date,
            @RequestParam(name = "type") EmployeeConstraint.ConstraintType type) {
        try {
            int affected = constraintService.bulkDeactivateByTypeAndDate(type, date);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "一括無効化を実行しました");
            response.put("affected", affected);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "一括無効化に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * メンテ用: 指定日のLIMITED制約の時間帯を一括更新
     */
    @PostMapping("/bulk/update-limited-time")
    public ResponseEntity<Map<String, Object>> bulkUpdateLimitedTime(
            @RequestParam(name = "date") LocalDate date,
            @RequestParam(name = "start") String start,
            @RequestParam(name = "end") String end) {
        try {
            LocalTime startTime = LocalTime.parse(start);
            LocalTime endTime = LocalTime.parse(end);
            int affected = constraintService.bulkUpdateLimitedTime(date, startTime, endTime);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "LIMITED時間帯を一括更新しました");
            response.put("affected", affected);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "LIMITED時間帯の一括更新に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 指定日付で勤務不可の従業員一覧を取得
     */
    @GetMapping("/unavailable/{date}")
    public ResponseEntity<Map<String, Object>> getUnavailableEmployees(@PathVariable LocalDate date) {
        try {
            var unavailableEmployees = constraintService.getUnavailableEmployees(date);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", unavailableEmployees);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "勤務不可従業員の取得に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 従業員の勤務可能性をチェック
     */
    @GetMapping("/availability/{employeeId}/{date}")
    public ResponseEntity<Map<String, Object>> checkEmployeeAvailability(
            @PathVariable Long employeeId,
            @PathVariable LocalDate date,
            @RequestParam(name = "startTime", required = false) String startTime,
            @RequestParam(name = "endTime", required = false) String endTime) {
        try {
            boolean available;
            
            if (startTime != null && endTime != null) {
                LocalTime shiftStart = LocalTime.parse(startTime);
                LocalTime shiftEnd = LocalTime.parse(endTime);
                available = constraintService.isEmployeeAvailableForShift(employeeId, date, shiftStart, shiftEnd);
            } else {
                available = constraintService.isEmployeeAvailable(employeeId, date);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("available", available);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "勤務可能性のチェックに失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 制約統計を取得
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getConstraintStatistics(
            @RequestParam(name = "startDate") LocalDate startDate,
            @RequestParam(name = "endDate") LocalDate endDate) {
        try {
            List<Object[]> statistics = constraintService.getConstraintStatistics(startDate, endDate);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", statistics);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "統計の取得に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 制約詳細を取得
     */
    @GetMapping("/{constraintId}")
    public ResponseEntity<Map<String, Object>> getConstraint(@PathVariable Long constraintId) {
        try {
            Optional<EmployeeConstraint> constraint = constraintService.getConstraint(constraintId);

            Map<String, Object> response = new HashMap<>();
            if (constraint.isPresent()) {
                response.put("success", true);
                response.put("data", constraint.get());
            } else {
                response.put("success", false);
                response.put("message", "制約が見つかりません");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "制約の取得に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    // DTOクラス
    public static class ConstraintRequest {
        private Long employeeId;
        private LocalDate date;
        private EmployeeConstraint.ConstraintType type;
        private String reason;

        // Getters and Setters
        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }
        public EmployeeConstraint.ConstraintType getType() { return type; }
        public void setType(EmployeeConstraint.ConstraintType type) { this.type = type; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class TimeConstraintRequest {
        private Long employeeId;
        private LocalDate date;
        private EmployeeConstraint.ConstraintType type;
        private String reason;
        private LocalTime startTime;
        private LocalTime endTime;

        // Getters and Setters
        public Long getEmployeeId() { return employeeId; }
        public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }
        public EmployeeConstraint.ConstraintType getType() { return type; }
        public void setType(EmployeeConstraint.ConstraintType type) { this.type = type; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public LocalTime getStartTime() { return startTime; }
        public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
        public LocalTime getEndTime() { return endTime; }
        public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    }

    public static class ConstraintUpdateRequest {
        private String reason;
        private LocalTime startTime;
        private LocalTime endTime;

        // Getters and Setters
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public LocalTime getStartTime() { return startTime; }
        public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
        public LocalTime getEndTime() { return endTime; }
        public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    }
}
