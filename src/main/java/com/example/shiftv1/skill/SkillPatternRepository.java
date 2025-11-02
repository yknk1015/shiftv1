package com.example.shiftv1.skill;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.DayOfWeek;
import java.util.List;

public interface SkillPatternRepository extends JpaRepository<SkillPattern, Long> {
    List<SkillPattern> findBySkill_IdAndActiveTrue(Long skillId);
    List<SkillPattern> findByActiveTrue();
}

