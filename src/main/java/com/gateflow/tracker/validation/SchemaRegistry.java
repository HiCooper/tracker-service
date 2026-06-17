package com.gateflow.tracker.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateflow.tracker.model.AppSchema;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 事件契约注册表:从 Redis({@code tracker:schema:{appKey}})读取由 tracker-admin 发布的 app schema,
 * 本地短 TTL 缓存以降低 Redis 压力。任何读取/解析失败都降级为「无 schema」(直通),绝不影响采集。
 */
@Slf4j
@Component
public class SchemaRegistry {

    static final String KEY_PREFIX = "tracker:schema:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Cache<String, Optional<AppSchema>> cache;

    public SchemaRegistry(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();
    }

    /** 返回 app 的事件契约;不存在或读取失败返回 empty(直通)。 */
    public Optional<AppSchema> get(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return Optional.empty();
        }
        return cache.get(appKey, this::loadFromRedis);
    }

    private Optional<AppSchema> loadFromRedis(String appKey) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + appKey);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, AppSchema.class));
        } catch (Exception e) {
            log.warn("Failed to load/parse schema for app {}: {}", appKey, e.getMessage());
            return Optional.empty();
        }
    }

    /** 主动失效缓存(发布新版本后可调用)。 */
    public void invalidate(String appKey) {
        cache.invalidate(appKey);
    }
}
