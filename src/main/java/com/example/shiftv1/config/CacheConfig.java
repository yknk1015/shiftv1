package com.example.shiftv1.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    @Profile("!prod")
    public CacheManager cacheManager() {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager();
        cacheManager.setCacheNames(java.util.Arrays.asList(
                "monthly-schedules",
                "employee-constraints",
                "shift-change-requests",
                "employees",
                "users",
                "schedule-statistics"
        ));
        return cacheManager;
    }

    // 本番環境用のRedisキャッシュ設定（必要に応じて実装）
    // @Bean
    // @Profile("prod")
    // public CacheManager redisCacheManager() {
    //     // Redis設定
    // }
}
