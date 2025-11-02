package com.example.shiftv1.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class UserDataInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(UserDataInitializer.class);
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserDataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) throws Exception {
        // デフォルト管理者ユーザーの作成
        if (!userRepository.existsByUsername("admin")) {
            User adminUser = new User(
                "admin",
                passwordEncoder.encode("admin123"),
                "admin@example.com",
                User.Role.ADMIN
            );
            userRepository.save(adminUser);
            logger.info("デフォルト管理者ユーザーを作成しました: admin/admin123");
        }

        // デフォルト一般ユーザーの作成
        if (!userRepository.existsByUsername("user")) {
            User normalUser = new User(
                "user",
                passwordEncoder.encode("user123"),
                "user@example.com",
                User.Role.USER
            );
            userRepository.save(normalUser);
            logger.info("デフォルト一般ユーザーを作成しました: user/user123");
        }

        logger.info("ユーザーデータの初期化が完了しました");
    }
}

