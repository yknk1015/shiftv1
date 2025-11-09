package com.example.shiftv1.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface ShiftReservationRepository extends JpaRepository<ShiftReservation, Long> {

    List<ShiftReservation> findByWorkDateBetweenAndStatusIn(LocalDate start,
                                                           LocalDate end,
                                                           Collection<ShiftReservation.Status> statuses);

    List<ShiftReservation> findByWorkDateBetween(LocalDate start, LocalDate end);

    void deleteByWorkDateBetween(LocalDate start, LocalDate end);
}
