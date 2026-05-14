package com.gateflow.tracker.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CaffeineConfig {

    @Bean
    public Cache<String, Boolean> localDeduplicationCache(TrackerProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(properties.getDedup().getLocalCacheSize())
                .expireAfterWrite(properties.getDedup().getLocalCacheTtlSeconds().getSeconds(), TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
