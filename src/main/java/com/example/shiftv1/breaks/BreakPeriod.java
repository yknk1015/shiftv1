package com.example.shiftv1.breaks;

import com.example.shiftv1.schedule.ShiftAssignment;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "break_periods")
public class BreakPeriod {

    public enum BreakType { LUNCH, SHORT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false)
    @NotNull
    private ShiftAssignment assignment;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private BreakType type;

    @NotNull
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @NotNull
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected BreakPeriod() {}

    public BreakPeriod(ShiftAssignment assignment, BreakType type, LocalTime startTime, LocalTime endTime) {
        this.assignment = assignment;
        this.type = type;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); this.updatedAt = LocalDateTime.now(); }
    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public ShiftAssignment getAssignment() { return assignment; }
    public void setAssignment(ShiftAssignment assignment) { this.assignment = assignment; }
    public BreakType getType() { return type; }
    public void setType(BreakType type) { this.type = type; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
}

