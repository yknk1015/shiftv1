package com.example.shiftv1.schedule;

import com.example.shiftv1.employee.Employee;
import com.example.shiftv1.skill.Skill;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "shift_reservations")
public class ShiftReservation {

    public enum Status {
        PENDING,
        APPLIED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id")
    private Skill skill;

    @Column(nullable = false)
    private LocalDate workDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(length = 128)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(length = 255)
    private String note;

    public ShiftReservation() {
    }

    public ShiftReservation(Employee employee,
                            Skill skill,
                            LocalDate workDate,
                            LocalTime startTime,
                            LocalTime endTime,
                            String label) {
        this.employee = employee;
        this.skill = skill;
        this.workDate = workDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public Skill getSkill() {
        return skill;
    }

    public void setSkill(Skill skill) {
        this.skill = skill;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public void setWorkDate(LocalDate workDate) {
        this.workDate = workDate;
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

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public boolean isPending() {
        return this.status == Status.PENDING;
    }
}
