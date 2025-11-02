package com.example.shiftv1.breaks;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface BreakPeriodRepository extends JpaRepository<BreakPeriod, Long> {

    @Query("SELECT b FROM BreakPeriod b JOIN FETCH b.assignment WHERE b.assignment.workDate = :date")
    List<BreakPeriod> findByWorkDate(@Param("date") LocalDate date);

    @Modifying
    @Transactional
    void deleteByAssignment_WorkDateBetween(LocalDate startDate, LocalDate endDate);

    @Modifying
    @Transactional
    @Query("DELETE FROM BreakPeriod b WHERE b.assignment.id IN (SELECT sa.id FROM ShiftAssignment sa WHERE sa.workDate BETWEEN :start AND :end)")
    void deleteAllByAssignmentWorkDateBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
