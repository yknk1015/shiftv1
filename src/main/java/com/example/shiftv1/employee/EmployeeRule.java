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
    private Integer dailyMaxHours = 8; // 日上限（時間）

    @Column(name = "max_consecutive_days")
    private Integer maxConsecutiveDays = 5; // 連勤上限（日）

    @Column(name = "min_rest_hours")
    private Integer minRestHours = 11; // 最低休息（時間）

    @Column(name = "allow_multiple_shifts_per_day")
    private Boolean allowMultipleShiftsPerDay = false; // 同日複数シフト許可

    @Column(name = "allow_holiday_work")
    private Boolean allowHolidayWork = true; // 祝日勤務許可

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
}

