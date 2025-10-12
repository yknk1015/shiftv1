package com.example.shiftv1.employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

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

    @Column
    @Min(value = 1, message = "スキルレベルは1以上である必要があります")
    @Max(value = 5, message = "スキルレベルは5以下である必要があります")
    private Integer skillLevel = 3; // デフォルトは中級レベル

    @Column
    private Boolean canWorkWeekends = true; // 土日勤務可能かどうか

    @Column
    private Boolean canWorkEvenings = true; // 夜勤可能かどうか

    @Column
    private Integer preferredWorkingDays = 5; // 希望勤務日数（週）

    protected Employee() {
    }

    public Employee(String name, String role) {
        this.name = name;
        this.role = role;
        this.skillLevel = 3;
        this.canWorkWeekends = true;
        this.canWorkEvenings = true;
        this.preferredWorkingDays = 5;
    }

    public Employee(String name, String role, Integer skillLevel, Boolean canWorkWeekends, Boolean canWorkEvenings, Integer preferredWorkingDays) {
        this.name = name;
        this.role = role;
        this.skillLevel = skillLevel;
        this.canWorkWeekends = canWorkWeekends;
        this.canWorkEvenings = canWorkEvenings;
        this.preferredWorkingDays = preferredWorkingDays;
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

    public Integer getSkillLevel() {
        return skillLevel;
    }

    public void setSkillLevel(Integer skillLevel) {
        this.skillLevel = skillLevel;
    }

    public Boolean getCanWorkWeekends() {
        return canWorkWeekends;
    }

    public void setCanWorkWeekends(Boolean canWorkWeekends) {
        this.canWorkWeekends = canWorkWeekends;
    }

    public Boolean getCanWorkEvenings() {
        return canWorkEvenings;
    }

    public void setCanWorkEvenings(Boolean canWorkEvenings) {
        this.canWorkEvenings = canWorkEvenings;
    }

    public Integer getPreferredWorkingDays() {
        return preferredWorkingDays;
    }

    public void setPreferredWorkingDays(Integer preferredWorkingDays) {
        this.preferredWorkingDays = preferredWorkingDays;
    }
}
