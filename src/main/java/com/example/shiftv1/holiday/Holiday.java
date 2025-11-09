package com.example.shiftv1.holiday;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "holidays")
public class Holiday {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "date", unique = true, nullable = false)
    private LocalDate date;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    protected Holiday() {}

    public Holiday(LocalDate date) {
        this(date, null);
    }

    public Holiday(LocalDate date, String name) {
        this.date = date;
        this.name = normalizeName(name);
    }

    public Long getId() { return id; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getName() { return name; }
    public void setName(String name) { this.name = normalizeName(name); }

    @PrePersist
    @PreUpdate
    void ensureName() {
        this.name = normalizeName(this.name);
    }

    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            return "祝日";
        }
        return value.trim();
    }
}
