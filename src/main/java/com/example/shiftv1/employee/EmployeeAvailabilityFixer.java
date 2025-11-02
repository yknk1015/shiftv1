package com.example.shiftv1.employee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;

@Component
public class EmployeeAvailabilityFixer implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(EmployeeAvailabilityFixer.class);

    private final EmployeeRepository employeeRepository;
    private final EmployeeAvailabilityRepository availabilityRepository;

    public EmployeeAvailabilityFixer(EmployeeRepository employeeRepository,
                                     EmployeeAvailabilityRepository availabilityRepository) {
        this.employeeRepository = employeeRepository;
        this.availabilityRepository = availabilityRepository;
    }

    @Override
    public void run(String... args) {
        int createdOrUpdated = 0;
        List<Employee> employees = employeeRepository.findAll();
        for (Employee e : employees) {
            for (DayOfWeek dow : EnumSet.allOf(DayOfWeek.class)) {
                List<EmployeeAvailability> list = availabilityRepository.findAll().stream()
                        .filter(a -> a.getEmployee().getId().equals(e.getId()) && a.getDayOfWeek() == dow)
                        .toList();
                if (list.isEmpty()) {
                    EmployeeAvailability av = new EmployeeAvailability();
                    av.setEmployee(e);
                    av.setDayOfWeek(dow);
                    av.setStartTime(LocalTime.of(8, 0));
                    av.setEndTime(LocalTime.of(21, 0));
                    availabilityRepository.save(av);
                    createdOrUpdated++;
                } else {
                    EmployeeAvailability av0 = list.get(0);
                    boolean changed = false;
                    if (av0.getStartTime() == null || av0.getStartTime().isAfter(LocalTime.of(8,0))) {
                        av0.setStartTime(LocalTime.of(8,0));
                        changed = true;
                    }
                    if (av0.getEndTime() == null || av0.getEndTime().isBefore(LocalTime.of(21,0))) {
                        av0.setEndTime(LocalTime.of(21,0));
                        changed = true;
                    }
                    if (changed) { availabilityRepository.save(av0); createdOrUpdated++; }
                }
            }
        }
        if (createdOrUpdated > 0) {
            logger.info("EmployeeAvailability widened/created: {} records", createdOrUpdated);
        }
    }
}

