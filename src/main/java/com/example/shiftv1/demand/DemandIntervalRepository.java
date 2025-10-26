package com.example.shiftv1.demand;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

public interface DemandIntervalRepository extends JpaRepository<DemandInterval, Long> {
    List<DemandInterval> findByDate(LocalDate date);
    List<DemandInterval> findByDayOfWeek(DayOfWeek dayOfWeek);

    // Ordered variants for UI display
    List<DemandInterval> findAllByOrderBySortOrderAscIdAsc();
    List<DemandInterval> findByDateOrderBySortOrderAscIdAsc(LocalDate date);
    List<DemandInterval> findByDayOfWeekOrderBySortOrderAscIdAsc(DayOfWeek dayOfWeek);

    // Neighbors for reordering
    DemandInterval findFirstBySortOrderLessThanOrderBySortOrderDesc(Integer sortOrder);
    DemandInterval findFirstBySortOrderGreaterThanOrderBySortOrderAsc(Integer sortOrder);

    // For assigning next order
    @Query("SELECT COALESCE(MAX(d.sortOrder), 0) FROM DemandInterval d")
    Integer findMaxSortOrder();

    @Query("SELECT d FROM DemandInterval d WHERE (d.date = :date OR (d.date IS NULL AND d.dayOfWeek = :dow)) AND (d.active = true OR d.active IS NULL) AND d.skill IS NOT NULL")
    List<DemandInterval> findEffectiveForDate(@Param("date") LocalDate date, @Param("dow") DayOfWeek dow);

    long countBySkill_Id(Long skillId);
}
