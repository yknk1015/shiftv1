package com.example.shiftv1.breaks;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface BreakPeriodRepository extends JpaRepository<BreakPeriod, Long> {

    @Query("SELECT b FROM BreakPeriod b WHERE b.assignment.workDate = :date")
    List<BreakPeriod> findByWorkDate(@Param("date") LocalDate date);
}

