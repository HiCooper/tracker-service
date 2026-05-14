package com.gateflow.tracker.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.util.RedisKeyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 两阶段去重服务
 * Stage 1: Caffeine 本地缓存 (10万容量, 60秒 TTL) → 命中率 ~80%
 * Stage 2: Redis SET NX (5分钟窗口) → 命中率 ~15%
 * 综合: 95%
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeduplicationService {

    private final StringRedisTemplate redisTemplate;
    private final Cache<String, Boolean> localDeduplicationCache;
    private final TrackerProperties properties;

    private static final String DEDUP_KEY_PREFIX = "dedup";

    /**
     * 检查事件是否重复
     */
    public boolean isDuplicate(String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            return false;
        }

        // Stage 1: 本地缓存检查
        if (Boolean.TRUE.equals(localDeduplicationCache.getIfPresent(eventId))) {
            log.debug("Event {} found in local cache (duplicate)", eventId);
            return true;
        }

        // Stage 2: Redis 检查
        String key = RedisKeyUtils.toShardedKey(DEDUP_KEY_PREFIX, eventId);
        try {
            Duration window = properties.getDedup().getWindowMinutes();
            Boolean result = redisTemplate.opsForValue().setIfAbsent(key, "1", window);

            if (Boolean.TRUE.equals(result)) {
                // 新事件，添加到本地缓存
                localDeduplicationCache.put(eventId, true);
                return false;
            } else {
                // 已存在，添加到本地缓存并返回重复
                localDeduplicationCache.put(eventId, true);
                log.debug("Event {} found in Redis (duplicate)", eventId);
                return true;
            }
        } catch (Exception e) {
            // Redis 故障时默认为非重复（宁可重复，不可丢失）
            log.warn("Redis deduplication check failed for {}, treating as non-duplicate: {}",
                    eventId, e.getMessage());
            return false;
        }
    }

    /**
     * 标记事件已处理（用于 DLQ 重放后）
     */
    public void markProcessed(String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            return;
        }

        String key = RedisKeyUtils.toShardedKey(DEDUP_KEY_PREFIX, eventId);
        localDeduplicationCache.put(eventId, true);

        try {
            Duration window = properties.getDedup().getWindowMinutes();
            redisTemplate.opsForValue().set(key, "1", window);
        } catch (Exception e) {
            log.warn("Failed to mark event {} as processed in Redis", eventId, e);
        }
    }

    /**
     * 获取本地缓存命中率统计
     */
    public double getLocalCacheHitRate() {
        return localDeduplicationCache.stats().hitRate();
    }
}
