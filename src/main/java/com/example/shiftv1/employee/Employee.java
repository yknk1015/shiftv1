package com.example.shiftv1.employee;

import com.example.shiftv1.skill.Skill;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "employees")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    @NotBlank(message = "従業員名は必須です")
    @Size(min = 1, max = 50, message = "従業員名は1文字以上50文字以下で入力してください")
    private String name;

    @Column
    @Size(max = 30, message = "役職は30文字以下で入力してください")
    private String role;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "employee_skills",
            joinColumns = @JoinColumn(name = "employee_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id"))
    private Set<Skill> skills = new HashSet<>();

    // Phase A additions: block eligibility and overtime preferences
    @Column(name = "eligible_full")
    private Boolean eligibleFull = true;

    @Column(name = "eligible_short_morning")
    private Boolean eligibleShortMorning = true;

    @Column(name = "eligible_short_afternoon")
    private Boolean eligibleShortAfternoon = true;

    @Column(name = "overtime_allowed")
    private Boolean overtimeAllowed = false;

    // additional overtime allowance per day (hours)
    @Column(name = "overtime_daily_max_hours")
    private Integer overtimeDailyMaxHours = 0;

    // additional overtime allowance per week (hours) - reserved for future
    @Column(name = "overtime_weekly_max_hours")
    private Integer overtimeWeeklyMaxHours = 0;

    protected Employee() {
    }

    public Employee(String name, String role) {
        this.name = name;
        this.role = role;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Set<Skill> getSkills() { return skills; }
    public void setSkills(Set<Skill> skills) { this.skills = skills; }

    public Boolean getEligibleFull() { return eligibleFull; }
    public void setEligibleFull(Boolean eligibleFull) { this.eligibleFull = eligibleFull; }
    public Boolean getEligibleShortMorning() { return eligibleShortMorning; }
    public void setEligibleShortMorning(Boolean eligibleShortMorning) { this.eligibleShortMorning = eligibleShortMorning; }
    public Boolean getEligibleShortAfternoon() { return eligibleShortAfternoon; }
    public void setEligibleShortAfternoon(Boolean eligibleShortAfternoon) { this.eligibleShortAfternoon = eligibleShortAfternoon; }
    public Boolean getOvertimeAllowed() { return overtimeAllowed; }
    public void setOvertimeAllowed(Boolean overtimeAllowed) { this.overtimeAllowed = overtimeAllowed; }
    public Integer getOvertimeDailyMaxHours() { return overtimeDailyMaxHours; }
    public void setOvertimeDailyMaxHours(Integer overtimeDailyMaxHours) { this.overtimeDailyMaxHours = overtimeDailyMaxHours; }
    public Integer getOvertimeWeeklyMaxHours() { return overtimeWeeklyMaxHours; }
    public void setOvertimeWeeklyMaxHours(Integer overtimeWeeklyMaxHours) { this.overtimeWeeklyMaxHours = overtimeWeeklyMaxHours; }
}
