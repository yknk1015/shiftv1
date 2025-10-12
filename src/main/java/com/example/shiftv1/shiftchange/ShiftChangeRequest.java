package com.example.shiftv1.shiftchange;

import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.schedule.ShiftAssignment;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "shift_change_requests")
public class ShiftChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_shift_id", nullable = false)
    private ShiftAssignment originalShift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private Employee requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "substitute_id")
    private Employee substitute;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShiftChangeStatus status = ShiftChangeStatus.PENDING;

    @Column(length = 500)
    private String reason;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processed_by")
    private Employee processedBy;

    @Column(length = 500)
    private String adminComment;

    @Column(name = "is_active")
    private Boolean active = true;

    protected ShiftChangeRequest() {
    }

    public ShiftChangeRequest(ShiftAssignment originalShift, Employee requester, String reason) {
        this.originalShift = originalShift;
        this.requester = requester;
        this.reason = reason;
        this.requestedAt = LocalDateTime.now();
    }

    public ShiftChangeRequest(ShiftAssignment originalShift, Employee requester, Employee substitute, String reason) {
        this.originalShift = originalShift;
        this.requester = requester;
        this.substitute = substitute;
        this.reason = reason;
        this.requestedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public ShiftAssignment getOriginalShift() {
        return originalShift;
    }

    public void setOriginalShift(ShiftAssignment originalShift) {
        this.originalShift = originalShift;
    }

    public Employee getRequester() {
        return requester;
    }

    public void setRequester(Employee requester) {
        this.requester = requester;
    }

    public Employee getSubstitute() {
        return substitute;
    }

    public void setSubstitute(Employee substitute) {
        this.substitute = substitute;
    }

    public ShiftChangeStatus getStatus() {
        return status;
    }

    public void setStatus(ShiftChangeStatus status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }

    public Employee getProcessedBy() {
        return processedBy;
    }

    public void setProcessedBy(Employee processedBy) {
        this.processedBy = processedBy;
    }

    public String getAdminComment() {
        return adminComment;
    }

    public void setAdminComment(String adminComment) {
        this.adminComment = adminComment;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public enum ShiftChangeStatus {
        PENDING("申請中"),
        APPROVED("承認済み"),
        REJECTED("却下"),
        CANCELLED("キャンセル"),
        COMPLETED("完了");

        private final String displayName;

        ShiftChangeStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    @Override
    public String toString() {
        return "ShiftChangeRequest{" +
                "id=" + id +
                ", requester=" + (requester != null ? requester.getName() : null) +
                ", substitute=" + (substitute != null ? substitute.getName() : null) +
                ", status=" + status +
                ", reason='" + reason + '\'' +
                ", requestedAt=" + requestedAt +
                '}';
    }
}
