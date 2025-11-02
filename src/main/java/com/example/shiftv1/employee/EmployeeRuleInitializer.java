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
public class EmployeeRuleInitializer implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(EmployeeRuleInitializer.class);

    private final EmployeeRepository employeeRepository;
    private final EmployeeRuleRepository ruleRepository;
    private final EmployeeAvailabilityRepository availabilityRepository;

    public EmployeeRuleInitializer(EmployeeRepository employeeRepository,
                                   EmployeeRuleRepository ruleRepository,
                                   EmployeeAvailabilityRepository availabilityRepository) {
        this.employeeRepository = employeeRepository;
        this.ruleRepository = ruleRepository;
        this.availabilityRepository = availabilityRepository;
    }

    @Override
    public void run(String... args) {
        List<Employee> employees = employeeRepository.findAll();
        for (Employee e : employees) {
            ruleRepository.findByEmployeeId(e.getId()).orElseGet(() -> {
                EmployeeRule r = new EmployeeRule();
                r.setEmployee(e);
                r.setWeeklyMaxHours(40);
                // 実務の9:00-18:00(9h)ブロックに対応するため既定を9hに
                r.setDailyMaxHours(9);
                r.setMaxConsecutiveDays(5);
                r.setMinRestHours(11);
                r.setAllowMultipleShiftsPerDay(false);
                r.setAllowHolidayWork(true);
                EmployeeRule saved = ruleRepository.save(r);

                // 平日 09:00-21:00 をデフォルト可用に設定
                for (DayOfWeek dow : java.util.EnumSet.allOf(DayOfWeek.class)) {
                    EmployeeAvailability av = new EmployeeAvailability();
                    av.setEmployee(e);
                    av.setDayOfWeek(dow);
                    av.setStartTime(LocalTime.of(8, 0));
                    av.setEndTime(LocalTime.of(21, 0));
                    availabilityRepository.save(av);
                }
                logger.info("従業員ルール初期化: {}", e.getName());
                return saved;
            });
        }
    }
}
