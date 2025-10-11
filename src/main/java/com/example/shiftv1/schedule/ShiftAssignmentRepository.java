package com.example.shiftv1.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ShiftAssignmentRepository extends JpaRepository<ShiftAssignment, Long> {
    List<ShiftAssignment> findByWorkDateBetween(LocalDate start, LocalDate end);
    void deleteByWorkDateBetween(LocalDate start, LocalDate end);
}
