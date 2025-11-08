package com.example.shiftv1.constraint;

import com.example.shiftv1.employee.Employee;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "employee_constraints")
public class EmployeeConstraint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    @NotNull(message = "従業員は必須です")
    private Employee employee;

    @Column(nullable = false)
    @NotNull(message = "日付は必須です")
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @NotNull(message = "制約タイプは必須です")
    private ConstraintType type;

    @Column(length = 200)
    private String reason;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "is_active")
    private Boolean active = true;

    public enum Severity { HARD, SOFT }

    @Enumerated(EnumType.STRING)
    @Column(name = "severity")
    private Severity severity = Severity.SOFT;

    protected EmployeeConstraint() {
    }

    public EmployeeConstraint(Employee employee, LocalDate date, ConstraintType type, String reason) {
        this.employee = employee;
        this.date = date;
        this.type = type;
        this.reason = reason;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public EmployeeConstraint(Employee employee, LocalDate date, ConstraintType type, 
                            String reason, LocalTime startTime, LocalTime endTime) {
        this.employee = employee;
        this.date = date;
        this.type = type;
        this.reason = reason;
        this.startTime = startTime;
        this.endTime = endTime;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public ConstraintType getType() {
        return type;
    }

    public void setType(ConstraintType type) {
        this.type = type;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public enum ConstraintType {
        UNAVAILABLE("勤務不可"),
        PREFERRED("希望"),
        LIMITED("制限あり"),
        VACATION("休暇"),
        SICK_LEAVE("病欠"),
        PERSONAL("私用");

        private final String displayName;

        ConstraintType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    @Override
    public String toString() {
        return "EmployeeConstraint{" +
                "id=" + id +
                ", employee=" + (employee != null ? employee.getName() : null) +
                ", date=" + date +
                ", type=" + type +
                ", reason='" + reason + '\'' +
                ", active=" + active +
                '}';
    }
}
