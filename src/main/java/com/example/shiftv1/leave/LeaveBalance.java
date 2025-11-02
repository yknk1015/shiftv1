package com.example.shiftv1.leave;

import com.example.shiftv1.employee.Employee;
import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "leave_balances")
public class LeaveBalance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "annual_granted")
    private Integer annualGranted = 0;

    @Column(name = "carried_over")
    private Integer carriedOver = 0;

    @Column(name = "used")
    private Integer used = 0;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    protected LeaveBalance() {}

    public LeaveBalance(Employee employee, Integer annualGranted, Integer carriedOver, LocalDate validFrom, LocalDate validTo) {
        this.employee = employee;
        this.annualGranted = annualGranted;
        this.carriedOver = carriedOver;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    public Long getId() { return id; }
    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }
    public Integer getAnnualGranted() { return annualGranted; }
    public void setAnnualGranted(Integer annualGranted) { this.annualGranted = annualGranted; }
    public Integer getCarriedOver() { return carriedOver; }
    public void setCarriedOver(Integer carriedOver) { this.carriedOver = carriedOver; }
    public Integer getUsed() { return used; }
    public void setUsed(Integer used) { this.used = used; }
    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public void setValidTo(LocalDate validTo) { this.validTo = validTo; }

    public int remaining() { return (annualGranted != null ? annualGranted : 0) + (carriedOver != null ? carriedOver : 0) - (used != null ? used : 0); }
}

