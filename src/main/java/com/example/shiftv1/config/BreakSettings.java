package com.example.shiftv1.config;

import jakarta.persistence.*;

@Entity
@Table(name = "break_settings")
public class BreakSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_break_enabled")
    private Boolean shortBreakEnabled = Boolean.FALSE;

    @Column(name = "short_break_minutes")
    private Integer shortBreakMinutes = 15;

    @Column(name = "min_shift_minutes")
    private Integer minShiftMinutes = 180;

    @Column(name = "apply_to_short_shifts")
    private Boolean applyToShortShifts = Boolean.TRUE;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Boolean getShortBreakEnabled() {
        return shortBreakEnabled;
    }

    public void setShortBreakEnabled(Boolean shortBreakEnabled) {
        this.shortBreakEnabled = shortBreakEnabled;
    }

    public Integer getShortBreakMinutes() {
        return shortBreakMinutes;
    }

    public void setShortBreakMinutes(Integer shortBreakMinutes) {
        this.shortBreakMinutes = shortBreakMinutes;
    }

    public Integer getMinShiftMinutes() {
        return minShiftMinutes;
    }

    public void setMinShiftMinutes(Integer minShiftMinutes) {
        this.minShiftMinutes = minShiftMinutes;
    }

    public Boolean getApplyToShortShifts() {
        return applyToShortShifts;
    }

    public void setApplyToShortShifts(Boolean applyToShortShifts) {
        this.applyToShortShifts = applyToShortShifts;
    }
}
