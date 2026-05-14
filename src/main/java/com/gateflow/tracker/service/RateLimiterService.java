package com.gateflow.tracker.service;

import com.gateflow.tracker.config.TrackerProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Bucket4j 的限流服务
 * 每个 clientId 一个独立的 Bucket
 */
@Service
@Slf4j
public class RateLimiterService {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final TrackerProperties properties;

    public RateLimiterService(TrackerProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试获取令牌
     * @param clientId 客户端标识
     * @return true 表示请求通过，false 表示被限流
     */
    public boolean tryAcquire(String clientId) {
        if (clientId == null || clientId.isEmpty()) {
            clientId = "default";
        }

        Bucket bucket = buckets.computeIfAbsent(clientId, this::createBucket);
        boolean acquired = bucket.tryConsume(1);

        if (!acquired) {
            log.warn("Rate limit exceeded for clientId: {}", clientId);
        }

        return acquired;
    }

    private Bucket createBucket(String clientId) {
        TrackerProperties.RateLimit config = properties.getRateLimit();

        // 使用 Token Bucket 算法
        Bandwidth limit = Bandwidth.classic(
                config.getMaxPerSecond(),
                Refill.greedy(config.getMaxPerSecond(), Duration.ofSeconds(1))
        );

        // 突发流量
        Bandwidth burst = Bandwidth.classic(
                config.getBurst(),
                Refill.intervally(config.getBurst(), Duration.ofSeconds(1))
        );

        return Bucket.builder()
                .addLimit(limit)
                .addLimit(burst)
                .build();
    }

    /**
     * 获取桶的剩余容量
     */
    public long getAvailableTokens(String clientId) {
        if (clientId == null || clientId.isEmpty()) {
            clientId = "default";
        }
        Bucket bucket = buckets.get(clientId);
        return bucket != null ? bucket.getAvailableTokens() : properties.getRateLimit().getMaxPerSecond();
    }

    /**
     * 重置桶（用于测试）
     */
    public void resetBucket(String clientId) {
        buckets.remove(clientId);
    }
}
