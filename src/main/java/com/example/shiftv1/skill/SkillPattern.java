package com.example.shiftv1.skill;

import jakarta.persistence.*;
import java.time.DayOfWeek;
import java.time.LocalTime;

@Entity
@Table(name = "skill_patterns")
public class SkillPattern {

    public enum PriorityHint { FIRST, LAST, MIDDLE, ANY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week")
    private DayOfWeek dayOfWeek; // null=all days

    @Column(name = "start_time")
    private LocalTime startTime; // null=any

    @Column(name = "end_time")
    private LocalTime endTime; // null=any

    @Column(name = "allowed_lengths_csv", length = 50)
    private String allowedLengthsCsv = "6,4"; // CSV of integer hours

    @Enumerated(EnumType.STRING)
    @Column(name = "priority_hint")
    private PriorityHint priorityHint = PriorityHint.ANY;

    @Column(name = "active")
    private Boolean active = true;

    public Long getId() { return id; }
    public Skill getSkill() { return skill; }
    public void setSkill(Skill skill) { this.skill = skill; }
    public DayOfWeek getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(DayOfWeek dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public String getAllowedLengthsCsv() { return allowedLengthsCsv; }
    public void setAllowedLengthsCsv(String allowedLengthsCsv) { this.allowedLengthsCsv = allowedLengthsCsv; }
    public PriorityHint getPriorityHint() { return priorityHint; }
    public void setPriorityHint(PriorityHint priorityHint) { this.priorityHint = priorityHint; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}

