package com.gateflow.tracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateflow.tracker.config.TrackerProperties;
import com.gateflow.tracker.model.DLQEntry;
import com.gateflow.tracker.model.EventRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DLQService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TrackerProperties properties;

    private static final String DLQ_KEY_PREFIX = "dlq:";
    private static final String DLQ_SCORE_KEY = "dlq:score";

    /**
     * 存储失败事件到 DLQ
     */
    public void store(EventRecord event, String reason) {
        try {
            DLQEntry entry = DLQEntry.builder()
                    .eventId(event.getEventId())
                    .eventType(event.getEventType())
                    .userId(event.getUserId())
                    .eventJson(objectMapper.writeValueAsString(event))
                    .reason(reason)
                    .failedAt(Instant.now())
                    .retryCount(0)
                    .nextRetryAt(Instant.now())
                    .build();

            String key = buildKey(reason, event.getEventId());
            Duration ttl = properties.getDlq().getTtlDays();
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(entry), ttl);

            // 维护按时间排序的集合
            redisTemplate.opsForZSet().add(DLQ_SCORE_KEY, key, entry.getFailedAt().toEpochMilli());

            log.info("Event {} stored in DLQ, reason: {}", event.getEventId(), reason);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event {} to JSON", event.getEventId(), e);
        } catch (Exception e) {
            log.error("Failed to store event {} in DLQ", event.getEventId(), e);
        }
    }

    /**
     * 获取待重放事件（按失败时间排序）
     */
    public List<DLQEntry> fetchForReplay(int count) {
        long now = Instant.now().toEpochMilli();
        Set<String> keys = redisTemplate.opsForZSet().rangeByScore(DLQ_SCORE_KEY, 0, now);

        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        List<DLQEntry> entries = new ArrayList<>();
        int maxRetry = properties.getDlq().getMaxRetryCount();

        for (String key : keys) {
            if (entries.size() >= count) break;

            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                redisTemplate.opsForZSet().remove(DLQ_SCORE_KEY, key);
                continue;
            }

            try {
                DLQEntry entry = objectMapper.readValue(json, DLQEntry.class);

                // 检查是否在退避期内
                if (entry.getNextRetryAt() != null && entry.getNextRetryAt().isAfter(Instant.now())) {
                    continue;
                }

                // 检查重试次数
                if (entry.getRetryCount() >= maxRetry) {
                    log.warn("Event {} exceeded max retry count, moving to dead storage", entry.getEventId());
                    moveToDeadStorage(entry);
                    redisTemplate.opsForZSet().remove(DLQ_SCORE_KEY, key);
                    continue;
                }

                entries.add(entry);
            } catch (Exception e) {
                log.error("Failed to parse DLQ entry for key {}", key, e);
                redisTemplate.opsForZSet().remove(DLQ_SCORE_KEY, key);
            }
        }

        return entries;
    }

    /**
     * 更新重试信息
     */
    public void updateRetryInfo(DLQEntry entry, boolean success) {
        String key = buildKey(entry.getReason(), entry.getEventId());

        if (success) {
            redisTemplate.delete(key);
            redisTemplate.opsForZSet().remove(DLQ_SCORE_KEY, key);
            log.info("DLQ entry {} replayed successfully", entry.getEventId());
        } else {
            entry.setRetryCount(entry.getRetryCount() + 1);
            entry.setNextRetryAt(calculateNextRetry(entry));
            try {
                Duration ttl = properties.getDlq().getTtlDays();
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(entry), ttl);
                log.info("DLQ entry {} retry count updated to {}", entry.getEventId(), entry.getRetryCount());
            } catch (JsonProcessingException e) {
                log.error("Failed to update DLQ entry {}", entry.getEventId(), e);
            }
        }
    }

    /**
     * 计算下次重试时间（指数退避 + Jitter）
     */
    private Instant calculateNextRetry(DLQEntry entry) {
        int retryCount = entry.getRetryCount();
        // 基础延迟：1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s, 256s, 512s
        long delaySeconds = (long) Math.pow(2, Math.min(retryCount, 10));
        // 添加 jitter: +/- 20%
        long jitter = (long) (delaySeconds * 0.2 * (Math.random() - 0.5));
        return Instant.now().plusSeconds(delaySeconds + jitter);
    }

    /**
     * 移动到死信存储（超过最大重试次数）
     */
    private void moveToDeadStorage(DLQEntry entry) {
        String deadKey = "dlq:dead:" + entry.getEventId();
        try {
            Duration deadTtl = Duration.ofDays(30);
            redisTemplate.opsForValue().set(deadKey, objectMapper.writeValueAsString(entry), deadTtl);
            log.warn("Event {} moved to dead storage after {} retries", entry.getEventId(), entry.getRetryCount());
        } catch (JsonProcessingException e) {
            log.error("Failed to move event {} to dead storage", entry.getEventId(), e);
        }
    }

    private String buildKey(String reason, String eventId) {
        return DLQ_KEY_PREFIX + reason + ":" + eventId;
    }

    /**
     * 获取 DLQ 当前积压数量
     */
    public long getDLQSize() {
        Long size = redisTemplate.opsForZSet().size(DLQ_SCORE_KEY);
        return size != null ? size : 0;
    }
}
