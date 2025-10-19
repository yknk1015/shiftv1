package com.example.shiftv1.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ShiftConfigDataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ShiftConfigDataInitializer.class);
    
    private final ShiftConfigRepository shiftConfigRepository;

    public ShiftConfigDataInitializer(ShiftConfigRepository shiftConfigRepository) {
        this.shiftConfigRepository = shiftConfigRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        // Legacy ShiftConfig seeding is fully disabled.
        logger.info("ShiftConfig seeding is disabled (legacy morning/evening/weekend not created).");
    }
}
