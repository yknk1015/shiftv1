package com.example.shiftv1.employee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class EmployeeRuleFixer implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(EmployeeRuleFixer.class);

    private final EmployeeRuleRepository ruleRepository;

    public EmployeeRuleFixer(EmployeeRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Override
    public void run(String... args) {
        try {
            var rules = ruleRepository.findAll();
            int updated = 0;
            for (EmployeeRule r : rules) {
                Integer d = r.getDailyMaxHours();
                if (d == null || d < 9) {
                    r.setDailyMaxHours(9);
                    ruleRepository.save(r);
                    updated++;
                }
            }
            if (updated > 0) {
                logger.info("EmployeeRule dailyMaxHours updated to >=9 for {} employees", updated);
            }
        } catch (Exception ex) {
            logger.warn("EmployeeRuleFixer failed: {}", ex.toString());
        }
    }
}

