package com.example.shiftv1.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

@Component
public class ShiftConfigDataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ShiftConfigDataInitializer.class);
    
    private final ShiftConfigRepository shiftConfigRepository;

    public ShiftConfigDataInitializer(ShiftConfigRepository shiftConfigRepository) {
        this.shiftConfigRepository = shiftConfigRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        // デフォルトのシフト設定を作成
        createDefaultShiftConfigs();
        logger.info("シフト設定データの初期化が完了しました");
    }

    private void createDefaultShiftConfigs() {
        // 朝シフト (平日)
        if (!shiftConfigRepository.existsByName("朝シフト")) {
            ShiftConfig morningShift = new ShiftConfig(
                "朝シフト",
                LocalTime.of(9, 0),
                LocalTime.of(15, 0),
                4,
                false
            );
            shiftConfigRepository.save(morningShift);
            logger.info("デフォルト朝シフト設定を作成しました");
        }

        // 夜シフト (平日)
        if (!shiftConfigRepository.existsByName("夜シフト")) {
            ShiftConfig eveningShift = new ShiftConfig(
                "夜シフト",
                LocalTime.of(15, 0),
                LocalTime.of(21, 0),
                4,
                false
            );
            shiftConfigRepository.save(eveningShift);
            logger.info("デフォルト夜シフト設定を作成しました");
        }

        // 土日シフト
        if (!shiftConfigRepository.existsByName("土日シフト")) {
            ShiftConfig weekendShift = new ShiftConfig(
                "土日シフト",
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                5,
                true
            );
            shiftConfigRepository.save(weekendShift);
            logger.info("デフォルト土日シフト設定を作成しました");
        }
    }
}




