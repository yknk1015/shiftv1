package com.example.shiftv1.config;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShiftConfigRepository extends JpaRepository<ShiftConfig, Long> {
    
    List<ShiftConfig> findByActiveTrue();
    
    List<ShiftConfig> findByWeekend(Boolean weekend);
    
    List<ShiftConfig> findByActiveTrueAndWeekend(Boolean weekend);
    
    Optional<ShiftConfig> findByName(String name);
    
    boolean existsByName(String name);
}




