package com.gateflow.tracker.service;

import com.gateflow.tracker.config.TrackerProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 基于 Bucket4j + Caffeine 的限流服务。
 * 使用 Caffeine 缓存管理 Bucket 生命周期，过期自动淘汰，防止内存泄漏。
 */
@Service
@Slf4j
public class RateLimiterService {

    private final Cache<String, Bucket> buckets;
    private final TrackerProperties properties;

    public RateLimiterService(TrackerProperties properties) {
        this.properties = properties;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofMinutes(10))
                .removalListener((String key, Bucket value, RemovalCause cause) -> {
                    if (cause.wasEvicted()) {
                        log.debug("Rate limiter bucket evicted for clientId: {}", key);
                    }
                })
                .build();
    }

    public boolean tryAcquire(String clientId) {
        if (clientId == null || clientId.isEmpty()) {
            clientId = "default";
        }

        Bucket bucket = buckets.get(clientId, this::createBucket);
        if (bucket == null) {
            bucket = createBucket(clientId);
        }

        boolean acquired = bucket.tryConsume(1);

        if (!acquired) {
            log.warn("Rate limit exceeded for clientId: {}", clientId);
        }

        return acquired;
    }

    private Bucket createBucket(String clientId) {
        TrackerProperties.RateLimit config = properties.getRateLimit();

        Bandwidth limit = Bandwidth.classic(
                config.getMaxPerSecond(),
                Refill.greedy(config.getMaxPerSecond(), Duration.ofSeconds(1))
        );

        Bandwidth burst = Bandwidth.classic(
                config.getBurst(),
                Refill.intervally(config.getBurst(), Duration.ofSeconds(1))
        );

        return Bucket.builder()
                .addLimit(limit)
                .addLimit(burst)
                .build();
    }

    public long getAvailableTokens(String clientId) {
        if (clientId == null || clientId.isEmpty()) {
            clientId = "default";
        }
        Bucket bucket = buckets.getIfPresent(clientId);
        return bucket != null ? bucket.getAvailableTokens() : properties.getRateLimit().getMaxPerSecond();
    }

    public void resetBucket(String clientId) {
        if (clientId != null) {
            buckets.invalidate(clientId);
        }
    }
}
