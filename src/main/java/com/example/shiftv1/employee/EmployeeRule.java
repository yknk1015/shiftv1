package com.example.shiftv1.employee;

import jakarta.persistence.*;

@Entity
@Table(name = "employee_rules")
public class EmployeeRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", unique = true, nullable = false)
    private Employee employee;

    @Column(name = "weekly_max_hours")
    private Integer weeklyMaxHours = 40; // 週上限（時間）

    @Column(name = "daily_max_hours")
    private Integer dailyMaxHours = 8; // 1日上限（時間）

    @Column(name = "max_consecutive_days")
    private Integer maxConsecutiveDays = 5; // 連続勤務上限（日）

    @Column(name = "min_rest_hours")
    private Integer minRestHours = 11; // 勤務間インターバル（時間）

    @Column(name = "allow_multiple_shifts_per_day")
    private Boolean allowMultipleShiftsPerDay = false; // 1日に複数シフト可

    @Column(name = "allow_holiday_work")
    private Boolean allowHolidayWork = true; // 祝日勤務可

    // 週の最低休日日数（通常は2日）
    @Column(name = "weekly_rest_days")
    private Integer weeklyRestDays = 2;

    public Long getId() { return id; }
    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }
    public Integer getWeeklyMaxHours() { return weeklyMaxHours; }
    public void setWeeklyMaxHours(Integer weeklyMaxHours) { this.weeklyMaxHours = weeklyMaxHours; }
    public Integer getDailyMaxHours() { return dailyMaxHours; }
    public void setDailyMaxHours(Integer dailyMaxHours) { this.dailyMaxHours = dailyMaxHours; }
    public Integer getMaxConsecutiveDays() { return maxConsecutiveDays; }
    public void setMaxConsecutiveDays(Integer maxConsecutiveDays) { this.maxConsecutiveDays = maxConsecutiveDays; }
    public Integer getMinRestHours() { return minRestHours; }
    public void setMinRestHours(Integer minRestHours) { this.minRestHours = minRestHours; }
    public Boolean getAllowMultipleShiftsPerDay() { return allowMultipleShiftsPerDay; }
    public void setAllowMultipleShiftsPerDay(Boolean allowMultipleShiftsPerDay) { this.allowMultipleShiftsPerDay = allowMultipleShiftsPerDay; }
    public Boolean getAllowHolidayWork() { return allowHolidayWork; }
    public void setAllowHolidayWork(Boolean allowHolidayWork) { this.allowHolidayWork = allowHolidayWork; }
    public Integer getWeeklyRestDays() { return weeklyRestDays; }
    public void setWeeklyRestDays(Integer weeklyRestDays) { this.weeklyRestDays = weeklyRestDays; }
}
