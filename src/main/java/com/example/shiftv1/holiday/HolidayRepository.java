package com.example.shiftv1.holiday;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface HolidayRepository extends JpaRepository<Holiday, Long> {
    boolean existsByDate(LocalDate date);
    void deleteByDate(LocalDate date);

    @Query("select h.date from Holiday h where h.date between :start and :end")
    List<LocalDate> findDatesBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
}

