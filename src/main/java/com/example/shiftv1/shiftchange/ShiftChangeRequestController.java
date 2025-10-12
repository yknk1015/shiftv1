package com.example.shiftv1.shiftchange;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/shift-changes")
public class ShiftChangeRequestController {

    private final ShiftChangeRequestService changeRequestService;

    public ShiftChangeRequestController(ShiftChangeRequestService changeRequestService) {
        this.changeRequestService = changeRequestService;
    }

    /**
     * シフト変更申請を作成（代行者指定なし）
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createChangeRequest(@RequestBody @Valid ChangeRequestDto request) {
        try {
            ShiftChangeRequest changeRequest = changeRequestService.createChangeRequest(
                    request.getOriginalShiftId(),
                    request.getRequesterId(),
                    request.getReason()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "シフト変更申請を作成しました");
            response.put("data", changeRequest);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "変更申請の作成に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * シフト変更申請を作成（代行者指定あり）
     */
    @PostMapping("/with-substitute")
    public ResponseEntity<Map<String, Object>> createChangeRequestWithSubstitute(@RequestBody @Valid ChangeRequestWithSubstituteDto request) {
        try {
            ShiftChangeRequest changeRequest = changeRequestService.createChangeRequestWithSubstitute(
                    request.getOriginalShiftId(),
                    request.getRequesterId(),
                    request.getSubstituteId(),
                    request.getReason()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "代行者指定のシフト変更申請を作成しました");
            response.put("data", changeRequest);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "変更申請の作成に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 変更申請を承認
     */
    @PutMapping("/{requestId}/approve")
    public ResponseEntity<Map<String, Object>> approveRequest(
            @PathVariable Long requestId,
            @RequestBody @Valid AdminActionDto action) {
        try {
            ShiftChangeRequest changeRequest = changeRequestService.approveRequest(
                    requestId,
                    action.getAdminId(),
                    action.getComment()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "変更申請を承認しました");
            response.put("data", changeRequest);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "承認処理に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 変更申請を却下
     */
    @PutMapping("/{requestId}/reject")
    public ResponseEntity<Map<String, Object>> rejectRequest(
            @PathVariable Long requestId,
            @RequestBody @Valid AdminActionDto action) {
        try {
            ShiftChangeRequest changeRequest = changeRequestService.rejectRequest(
                    requestId,
                    action.getAdminId(),
                    action.getComment()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "変更申請を却下しました");
            response.put("data", changeRequest);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "却下処理に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 変更申請をキャンセル
     */
    @PutMapping("/{requestId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelRequest(
            @PathVariable Long requestId,
            @RequestBody @Valid CancelRequestDto request) {
        try {
            ShiftChangeRequest changeRequest = changeRequestService.cancelRequest(
                    requestId,
                    request.getRequesterId()
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "変更申請をキャンセルしました");
            response.put("data", changeRequest);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "キャンセル処理に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 申請者の変更申請一覧を取得
     */
    @GetMapping("/requester/{employeeId}")
    public ResponseEntity<Map<String, Object>> getRequestsByRequester(@PathVariable Long employeeId) {
        try {
            List<ShiftChangeRequest> requests = changeRequestService.getRequestsByRequester(employeeId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", requests);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "申請一覧の取得に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 代行者の変更申請一覧を取得
     */
    @GetMapping("/substitute/{employeeId}")
    public ResponseEntity<Map<String, Object>> getRequestsBySubstitute(@PathVariable Long employeeId) {
        try {
            List<ShiftChangeRequest> requests = changeRequestService.getRequestsBySubstitute(employeeId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", requests);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "代行申請一覧の取得に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 承認待ちの変更申請一覧を取得
     */
    @GetMapping("/pending")
    public ResponseEntity<Map<String, Object>> getPendingRequests() {
        try {
            List<ShiftChangeRequest> requests = changeRequestService.getPendingRequests();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", requests);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "承認待ち申請の取得に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 変更申請詳細を取得
     */
    @GetMapping("/{requestId}")
    public ResponseEntity<Map<String, Object>> getRequest(@PathVariable Long requestId) {
        try {
            Optional<ShiftChangeRequest> request = changeRequestService.getRequest(requestId);

            Map<String, Object> response = new HashMap<>();
            if (request.isPresent()) {
                response.put("success", true);
                response.put("data", request.get());
            } else {
                response.put("success", false);
                response.put("message", "変更申請が見つかりません");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "変更申請の取得に失敗しました: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    /**
     * 変更申請統計を取得
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getRequestStatistics(
            @RequestParam LocalDateTime startDate,
            @RequestParam LocalDateTime endDate) {
        try {
            List<Object[]> statistics = changeRequestService.getRequestStatistics(startDate, endDate);

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

    // DTOクラス
    public static class ChangeRequestDto {
        private Long originalShiftId;
        private Long requesterId;
        private String reason;

        // Getters and Setters
        public Long getOriginalShiftId() { return originalShiftId; }
        public void setOriginalShiftId(Long originalShiftId) { this.originalShiftId = originalShiftId; }
        public Long getRequesterId() { return requesterId; }
        public void setRequesterId(Long requesterId) { this.requesterId = requesterId; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class ChangeRequestWithSubstituteDto {
        private Long originalShiftId;
        private Long requesterId;
        private Long substituteId;
        private String reason;

        // Getters and Setters
        public Long getOriginalShiftId() { return originalShiftId; }
        public void setOriginalShiftId(Long originalShiftId) { this.originalShiftId = originalShiftId; }
        public Long getRequesterId() { return requesterId; }
        public void setRequesterId(Long requesterId) { this.requesterId = requesterId; }
        public Long getSubstituteId() { return substituteId; }
        public void setSubstituteId(Long substituteId) { this.substituteId = substituteId; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class AdminActionDto {
        private Long adminId;
        private String comment;

        // Getters and Setters
        public Long getAdminId() { return adminId; }
        public void setAdminId(Long adminId) { this.adminId = adminId; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }

    public static class CancelRequestDto {
        private Long requesterId;

        // Getters and Setters
        public Long getRequesterId() { return requesterId; }
        public void setRequesterId(Long requesterId) { this.requesterId = requesterId; }
    }
}
