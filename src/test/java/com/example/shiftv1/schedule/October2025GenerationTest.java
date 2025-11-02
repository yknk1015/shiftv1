package com.example.shiftv1.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class October2025GenerationTest {

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ShiftAssignmentRepository assignmentRepository;

    @Test
    void generateMonthlySchedule_2025_10_persistsAndReturnsAssignments() {
        int year = 2025;
        int month = 10;

        List<ShiftAssignment> result = scheduleService.generateMonthlySchedule(year, month);
        assertThat(result).as("Generated assignments should not be empty for %d-%02d", year, month).isNotEmpty();

        YearMonth ym = YearMonth.of(year, month);
        var saved = assignmentRepository.findByWorkDateBetween(ym.atDay(1), ym.atEndOfMonth());
        assertThat(saved).as("Assignments should be persisted").isNotEmpty();
    }
}

