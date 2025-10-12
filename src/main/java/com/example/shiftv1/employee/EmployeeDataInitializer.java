package com.example.shiftv1.employee;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

@Configuration
public class EmployeeDataInitializer {

    private static final Logger logger = LoggerFactory.getLogger(EmployeeDataInitializer.class);

    @Bean
    CommandLineRunner loadEmployees(EmployeeRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                logger.info("従業員データは既に存在します。スキップします。");
                return;
            }
            
            logger.info("学習用従業員データを生成中...");
            
            // 多様性のある従業員データを生成
            List<Employee> employees = IntStream.rangeClosed(1, 30)
                    .mapToObj(i -> createDiverseEmployee(i))
                    .toList();
            
            repository.saveAll(employees);
            logger.info("{} 名の従業員データを生成しました。", employees.size());
        };
    }
    
    /**
     * 多様性のある従業員データを作成（学習用）
     */
    private Employee createDiverseEmployee(int index) {
        Random random = new Random(index); // 再現可能な乱数
        
        String name = "従業員%02d".formatted(index);
        
        // 役職をランダムに設定
        String[] roles = {"スタッフ", "リーダー", "マネージャー", "アシスタント"};
        String role = roles[random.nextInt(roles.length)];
        
        // スキルレベルをランダムに設定（1-5）
        Integer skillLevel = 1 + random.nextInt(5);
        
        // 土日勤務可能度（80%の確率で可能）
        Boolean canWorkWeekends = random.nextDouble() < 0.8;
        
        // 夜勤可能度（70%の確率で可能）
        Boolean canWorkEvenings = random.nextDouble() < 0.7;
        
        // 希望勤務日数（3-6日）
        Integer preferredWorkingDays = 3 + random.nextInt(4);
        
        Employee employee = new Employee(name, role, skillLevel, canWorkWeekends, canWorkEvenings, preferredWorkingDays);
        
        logger.debug("従業員作成: {} - 役職:{}, スキル:{}, 土日:{}, 夜勤:{}, 希望日数:{}", 
            name, role, skillLevel, canWorkWeekends, canWorkEvenings, preferredWorkingDays);
        
        return employee;
    }
}
