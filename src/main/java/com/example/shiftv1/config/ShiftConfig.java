package com.example.shiftv1.config;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;
import java.util.Objects;

@Entity
@Table(name = "shift_config")
public class ShiftConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "シフト名は必須です")
    @Column(unique = true, nullable = false)
    private String name;

    @NotNull(message = "開始時間は必須です")
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @NotNull(message = "終了時間は必須です")
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Min(value = 1, message = "従業員数は1人以上である必要があります")
    @Max(value = 20, message = "従業員数は20人以下である必要があります")
    @Column(name = "required_employees", nullable = false)
    private Integer requiredEmployees = 4;

    @Column(name = "is_active")
    private Boolean active = true;

    @Column(name = "is_weekend")
    private Boolean weekend = false;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    @Column(name = "updated_at")
    private java.time.LocalDateTime updatedAt;

    // デフォルトコンストラクタ
    public ShiftConfig() {
    }

    public ShiftConfig(String name, LocalTime startTime, LocalTime endTime, Integer requiredEmployees, Boolean weekend) {
        this.name = name;
        this.startTime = startTime;
        this.endTime = endTime;
        this.requiredEmployees = requiredEmployees;
        this.weekend = weekend;
        this.createdAt = java.time.LocalDateTime.now();
        this.updatedAt = java.time.LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        createdAt = java.time.LocalDateTime.now();
        updatedAt = java.time.LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = java.time.LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public Integer getRequiredEmployees() {
        return requiredEmployees;
    }

    public void setRequiredEmployees(Integer requiredEmployees) {
        this.requiredEmployees = requiredEmployees;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Boolean getWeekend() {
        return weekend;
    }

    public void setWeekend(Boolean weekend) {
        this.weekend = weekend;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public java.time.LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(java.time.LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShiftConfig that = (ShiftConfig) o;
        return Objects.equals(id, that.id) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name);
    }

    @Override
    public String toString() {
        return "ShiftConfig{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                ", requiredEmployees=" + requiredEmployees +
                ", active=" + active +
                ", weekend=" + weekend +
                '}';
    }
}





