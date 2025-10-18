package com.example.shiftv1.skill;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SkillRepository extends JpaRepository<Skill, Long> {
    boolean existsByCode(String code);
    Optional<Skill> findByCode(String code);
}

